/*
 * ************************************************************************
 *  HttpImageLoader.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.max

/**
 * Remote artwork loader.
 *
 * Callers only wait. One shared flight per URL runs on [flightScope] and takes
 * a permit from a fair FIFO semaphore before it touches the network, so at most
 * [MAX_CONCURRENT_DOWNLOADS] downloads allocate buffers or sockets. Cancelling
 * a caller drops only that caller's interest. The flight is disconnected and
 * removed when the last caller leaves, including while it is still queued, so
 * an abandoned screen does not keep a worker or a later playback request behind
 * it. A caller gives up after [callTimeoutMs] measured from its own entry, so
 * queue time counts. The socket deadline starts before [HttpURLConnection.connect],
 * which is where DNS runs.
 *
 * The body is one buffer of at most [DEFAULT_MAX_BODY_BYTES]. A known
 * Content-Length is allocated exactly once and decoded in place. There is no
 * second copy via [java.io.ByteArrayOutputStream.toByteArray]. Four workers
 * therefore peak at about 8MB of compressed bytes, not two copies of a larger cap.
 * 2MB is enough for poster and cover art that this loader samples down to 150dp.
 */
object HttpImageLoader {

    private const val TAG = "HttpImageLoader"

    /** Longest decoded edge, in dp. Callers reuse this bitmap at several sizes. */
    internal const val MAX_DECODE_EDGE_DP = 150

    /** Reject decoded bitmaps larger than this so a crafted image cannot expand without bound. */
    internal const val MAX_DECODED_PIXELS = 8 * 1024 * 1024

    /** Network reads in flight. Matches the loader pool so queued flights do not sit on those threads. */
    internal const val MAX_CONCURRENT_DOWNLOADS = 4

    internal const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    internal const val DEFAULT_READ_TIMEOUT_MS = 15_000
    internal const val DEFAULT_CALL_TIMEOUT_MS = 20_000

    /**
     * Compressed-body cap. Artwork is sampled to 150dp, so a multi-megabyte
     * original is not useful here. 2MB times [MAX_CONCURRENT_DOWNLOADS] is the
     * peak of the single in-place buffers.
     */
    internal const val DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024

    @VisibleForTesting
    internal var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS

    @VisibleForTesting
    internal var readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS

    @VisibleForTesting
    internal var callTimeoutMs: Int = DEFAULT_CALL_TIMEOUT_MS

    @VisibleForTesting
    internal var maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES

    private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "HttpImageLoader-deadline").apply { isDaemon = true }
    }

    private val loaderExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS) { runnable ->
        Thread(runnable, "HttpImageLoader").apply { isDaemon = true }
    }
    private val loaderDispatcher = loaderExecutor.asCoroutineDispatcher()

    private var flightJob = SupervisorJob()
    private var flightScope = CoroutineScope(flightJob + Dispatchers.Default)
    private var loaderJob = SupervisorJob()
    private var loaderScope = CoroutineScope(loaderJob + loaderDispatcher)

    /** Replaced on test reset. Flights capture the instance they acquired. */
    private var slots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /** Guarded by its own monitor. */
    private val inFlight = HashMap<String, Flight>()

    private val openConnections: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(IdentityHashMap())

    private class Flight(
        val connectMs: Int,
        val readMs: Int,
        val callMs: Int,
        val maxBytes: Int,
    ) {
        val result = CompletableDeferred<Bitmap?>()
        var waiters: Int = 0
        var abandoned: Boolean = false
        @Volatile var connection: HttpURLConnection? = null
        var job: Job? = null

        val acceptsWaiters: Boolean
            get() = !abandoned && !result.isCompleted
    }

    /** Bytes actually occupied by the image. [bytes] may be larger only when the length was unknown. */
    internal class ImageBytes(val bytes: ByteArray, val length: Int)

    suspend fun downloadBitmap(imageUrl: String): Bitmap? {
        BitmapCache.getBitmapFromMemCache(imageUrl)?.let { return it }
        val callerTimeoutMs = callTimeoutMs.coerceAtLeast(1)
        val flight: Flight
        val started: Job?
        synchronized(inFlight) {
            BitmapCache.getBitmapFromMemCache(imageUrl)?.let { return it }
            val existing = inFlight[imageUrl]
            if (existing != null && existing.acceptsWaiters) {
                existing.waiters++
                flight = existing
                started = null
            } else {
                val created = Flight(
                    connectTimeoutMs.coerceAtLeast(1),
                    readTimeoutMs.coerceAtLeast(1),
                    callTimeoutMs.coerceAtLeast(1),
                    maxBodyBytes.coerceAtLeast(1),
                )
                created.waiters = 1
                // Lazy so the body cannot run on this thread while the map lock is held.
                created.job = flightScope.launch(start = CoroutineStart.LAZY) {
                    runFlight(imageUrl, created)
                }
                inFlight[imageUrl] = created
                flight = created
                started = created.job
            }
        }
        started?.start()
        try {
            try {
                return withTimeout(callerTimeoutMs.toLong()) { flight.result.await() }
            } catch (timeout: TimeoutCancellationException) {
                return null
            }
        } finally {
            dropWaiter(imageUrl, flight)
        }
    }

    private suspend fun runFlight(imageUrl: String, flight: Flight) {
        val permits = slots
        var acquired = false
        try {
            permits.acquire()
            acquired = true
            if (!coroutineContext.isActive || flight.abandoned) {
                flight.result.complete(null)
                return
            }
            val bitmap = withContext(loaderDispatcher) { fetch(imageUrl, flight) }
            flight.result.complete(bitmap)
        } catch (cancelled: CancellationException) {
            flight.connection?.disconnect()
            flight.result.complete(null)
            throw cancelled
        } finally {
            if (acquired) permits.release()
            synchronized(inFlight) {
                if (inFlight[imageUrl] === flight) inFlight.remove(imageUrl)
            }
            flight.connection?.disconnect()
        }
    }

    /**
     * Last caller left. The flight is taken out of the map before it is
     * cancelled so a retry starts a new one instead of joining a dead result.
     */
    private fun dropWaiter(imageUrl: String, flight: Flight) {
        val job: Job?
        synchronized(inFlight) {
            flight.waiters--
            if (flight.waiters > 0 || flight.result.isCompleted) return
            flight.abandoned = true
            if (inFlight[imageUrl] === flight) inFlight.remove(imageUrl)
            job = flight.job
        }
        flight.connection?.disconnect()
        flight.result.complete(null)
        job?.cancel()
    }

    private fun fetch(imageUrl: String, flight: Flight): Bitmap? {
        return try {
            downloadAndDecode(imageUrl, flight)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "download timed out: ${e.message}")
            null
        } catch (e: SocketException) {
            Log.w(TAG, "download aborted: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "download failed: ${e.message}", e)
            null
        }
    }

    private fun downloadAndDecode(imageUrl: String, flight: Flight): Bitmap? {
        var stream: InputStream? = null
        var errorStream: InputStream? = null
        var abort: ScheduledFuture<*>? = null
        var connection: HttpURLConnection? = null
        val deadline = System.nanoTime() + flight.callMs.toLong() * 1_000_000L
        try {
            // Armed before connect so a stalled DNS lookup is inside the deadline.
            abort = deadlines.schedule({
                try {
                    flight.connection?.disconnect()
                } catch (ignored: Exception) {
                }
            }, flight.callMs.toLong(), TimeUnit.MILLISECONDS)
            val opened = URL(imageUrl).openConnection()
            if (opened !is HttpURLConnection) return null
            connection = opened
            flight.connection = connection
            synchronized(openConnections) { openConnections.add(connection) }
            if (flight.abandoned) return null
            connection.connectTimeout = flight.connectMs
            connection.readTimeout = flight.readMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.doInput = true
            connection.requestMethod = "GET"
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                errorStream = connection.errorStream
                return null
            }
            val declaredLength = connection.contentLength
            if (declaredLength > flight.maxBytes) return null
            stream = connection.inputStream
            val body = readImageBytes(stream, declaredLength, flight.maxBytes, deadline) ?: return null
            if (flight.abandoned) return null
            val bitmap = decodeSampled(body.bytes, MAX_DECODE_EDGE_DP.dp, body.length)
            if (bitmap != null && !flight.abandoned) BitmapCache.addBitmapToMemCache(imageUrl, bitmap)
            return if (flight.abandoned) null else bitmap
        } finally {
            abort?.cancel(false)
            CloseableUtils.close(stream)
            CloseableUtils.close(errorStream)
            if (connection != null) {
                synchronized(openConnections) { openConnections.remove(connection) }
                connection.disconnect()
            }
        }
    }

    /**
     * One buffer, no trailing copy. A positive [declaredLength] within the cap
     * is allocated at that exact size. An unknown length grows a single array
     * and the decoder uses [ImageBytes.length], not the spare capacity.
     */
    internal fun readImageBytes(
        input: InputStream,
        declaredLength: Int,
        maxBytes: Int,
        deadlineNanos: Long,
    ): ImageBytes? {
        if (maxBytes <= 0) return null
        if (declaredLength > maxBytes) return null
        if (declaredLength == 0) return null
        if (declaredLength > 0) {
            val exact = ByteArray(declaredLength)
            var offset = 0
            while (offset < declaredLength) {
                if (System.nanoTime() >= deadlineNanos) return null
                val read = input.read(exact, offset, declaredLength - offset)
                if (read < 0) return null
                offset += read
            }
            return ImageBytes(exact, declaredLength)
        }
        var buffer = ByteArray(minOf(16 * 1024, maxBytes))
        var size = 0
        while (true) {
            if (System.nanoTime() >= deadlineNanos) return null
            if (size == buffer.size) {
                if (size >= maxBytes) return null
                val grown = minOf(maxBytes.toLong(), buffer.size.toLong() * 2L).toInt()
                if (grown <= buffer.size) return null
                buffer = buffer.copyOf(grown)
            }
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
            if (size > maxBytes) return null
        }
        if (size == 0) return null
        return ImageBytes(buffer, size)
    }

    internal fun decodeSampled(bytes: ByteArray, maxEdgePx: Int, length: Int = bytes.size): Bitmap? {
        val count = length.coerceIn(0, bytes.size)
        if (count == 0 || maxEdgePx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, count, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val ratio = max(width, height).toFloat() / maxEdgePx.toFloat()
        var sample = if (ratio > 1f) floor(ratio).toInt().coerceAtLeast(1) else 1
        while (sample < max(width, height)) {
            val decodedW = width / sample
            val decodedH = height / sample
            if (decodedW <= 0 || decodedH <= 0) return null
            if (decodedW.toLong() * decodedH.toLong() <= MAX_DECODED_PIXELS) break
            val next = sample.toLong() * 2L
            if (next > Int.MAX_VALUE) return null
            sample = next.toInt()
        }
        val decodedW = width / sample
        val decodedH = height / sample
        if (decodedW <= 0 || decodedH <= 0) return null
        if (decodedW.toLong() * decodedH.toLong() > MAX_DECODED_PIXELS) return null
        val pixels = BitmapFactory.Options().apply {
            inSampleSize = sample
            inJustDecodeBounds = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, count, pixels)
    }

    @VisibleForTesting
    internal fun inFlightCount(): Int = synchronized(inFlight) { inFlight.size }

    @VisibleForTesting
    internal fun waiterCount(imageUrl: String): Int = synchronized(inFlight) {
        inFlight[imageUrl]?.waiters ?: 0
    }

    @VisibleForTesting
    internal fun openConnectionCount(): Int = synchronized(openConnections) { openConnections.size }

    @VisibleForTesting
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun retainedCompletedBitmapCount(): Int = synchronized(inFlight) {
        inFlight.values.count { flight ->
            flight.result.isCompleted && !flight.result.isCancelled &&
                runCatching { flight.result.getCompleted() }.getOrNull() != null
        }
    }

    @VisibleForTesting
    internal fun scrubWorkerStacksForTests() {
        val done = CountDownLatch(MAX_CONCURRENT_DOWNLOADS)
        repeat(MAX_CONCURRENT_DOWNLOADS) {
            loaderScope.launch {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
    }

    @VisibleForTesting
    internal fun resetForTests() {
        val connections = synchronized(openConnections) { openConnections.toList() }
        connections.forEach { connection ->
            try {
                connection.disconnect()
            } catch (ignored: Exception) {
            }
        }
        flightJob.cancel()
        flightJob = SupervisorJob()
        flightScope = CoroutineScope(flightJob + Dispatchers.Default)
        loaderJob.cancel()
        loaderJob = SupervisorJob()
        loaderScope = CoroutineScope(loaderJob + loaderDispatcher)
        slots = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        synchronized(inFlight) { inFlight.clear() }
        synchronized(openConnections) { openConnections.clear() }
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS
        callTimeoutMs = DEFAULT_CALL_TIMEOUT_MS
        maxBodyBytes = DEFAULT_MAX_BODY_BYTES
        BitmapCache.clear()
    }
}
