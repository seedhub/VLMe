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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.max

/**
 * Remote artwork loader.
 *
 * Callers (a screen's lifecycle scope, or `runBlocking` from playback metadata)
 * only wait. The download itself runs on [loaderDispatcher], which never
 * executes inline on the caller, so cancelling a caller cannot abandon the
 * shared job in an active state and the in-flight lock is not held across I/O.
 * Finished jobs leave the map immediately; [BitmapCache] is the only long-lived
 * bitmap owner.
 *
 * One HTTP GET is read into a size-capped buffer and decoded twice from those
 * bytes (bounds, then pixels). Connect, read, and overall deadlines are finite.
 */
object HttpImageLoader {

    private const val TAG = "HttpImageLoader"

    /** Longest decoded edge, in dp. Callers reuse this bitmap at several sizes. */
    internal const val MAX_DECODE_EDGE_DP = 150

    /** Reject decoded bitmaps larger than this so a crafted image cannot expand without bound. */
    internal const val MAX_DECODED_PIXELS = 8 * 1024 * 1024

    internal const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    internal const val DEFAULT_READ_TIMEOUT_MS = 15_000
    internal const val DEFAULT_CALL_TIMEOUT_MS = 20_000
    internal const val DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024

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

    /**
     * Separate from [kotlinx.coroutines.Dispatchers.IO] so a blocked socket cannot
     * take a shared pool thread, and so starting a job never runs the download
     * on the thread that holds [inFlight].
     */
    private val loaderDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "HttpImageLoader").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var loaderJob = SupervisorJob()
    private var loaderScope = CoroutineScope(loaderJob + loaderDispatcher)

    /** Guarded by its own monitor. Values are the shared download, never a finished bitmap kept on purpose. */
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()

    /** Connections currently inside [downloadAndDecode], so a deadline or test reset can close them. */
    private val openConnections: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(IdentityHashMap())

    suspend fun downloadBitmap(imageUrl: String): Bitmap? {
        BitmapCache.getBitmapFromMemCache(imageUrl)?.let { return it }
        val deferred = synchronized(inFlight) {
            BitmapCache.getBitmapFromMemCache(imageUrl)?.let { return it }
            val existing = inFlight[imageUrl]
            // Not completed covers a job that is queued but not started yet.
            // A finished success, failure, or cancellation is replaced so the
            // next caller can retry instead of observing a stale result.
            if (existing != null && !existing.isCompleted) existing else beginFetchLocked(imageUrl)
        }
        // start() is not a suspension point, so a caller cancelled before
        // await() still publishes the shared work. Starting outside the lock
        // keeps the download off the thread that owns [inFlight].
        deferred.start()
        return deferred.await()
    }

    /**
     * Replaces the previous entry for [imageUrl]. The caller must hold the
     * [inFlight] monitor. The completion handler is registered before the
     * caller starts the job, so a fast failure cannot leave the entry behind.
     */
    private fun beginFetchLocked(imageUrl: String): Deferred<Bitmap?> {
        val job = loaderScope.async(start = CoroutineStart.LAZY) { fetch(imageUrl) }
        inFlight[imageUrl] = job
        job.invokeOnCompletion {
            synchronized(inFlight) {
                if (inFlight[imageUrl] === job) inFlight.remove(imageUrl)
            }
        }
        return job
    }

    private fun fetch(imageUrl: String): Bitmap? {
        return try {
            downloadAndDecode(imageUrl)
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

    private fun downloadAndDecode(imageUrl: String): Bitmap? {
        val opened = URL(imageUrl).openConnection()
        if (opened !is HttpURLConnection) return null
        val connection = opened
        var stream: InputStream? = null
        var errorStream: InputStream? = null
        var abort: ScheduledFuture<*>? = null
        synchronized(openConnections) { openConnections.add(connection) }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.doInput = true
            connection.requestMethod = "GET"
            abort = deadlines.schedule({
                try {
                    connection.disconnect()
                } catch (ignored: Exception) {
                }
            }, callTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                errorStream = connection.errorStream
                return null
            }
            val declaredLength = connection.contentLength
            if (declaredLength > maxBodyBytes) return null
            stream = connection.inputStream
            val bytes = readBody(stream, maxBodyBytes, callTimeoutMs) ?: return null
            val bitmap = decodeSampled(bytes, MAX_DECODE_EDGE_DP.dp)
            if (bitmap != null) BitmapCache.addBitmapToMemCache(imageUrl, bitmap)
            return bitmap
        } finally {
            abort?.cancel(false)
            CloseableUtils.close(stream)
            CloseableUtils.close(errorStream)
            synchronized(openConnections) { openConnections.remove(connection) }
            connection.disconnect()
        }
    }

    /**
     * Reads at most [maxBytes] and gives up if the overall deadline passes between
     * reads. The socket read timeout bounds a single stalled read; the scheduled
     * disconnect bounds the whole call, including a trickle that keeps resetting
     * the read timeout.
     */
    private fun readBody(input: InputStream, maxBytes: Int, overallTimeoutMs: Int): ByteArray? {
        val deadline = System.nanoTime() + overallTimeoutMs.toLong() * 1_000_000L
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).coerceAtLeast(0))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            if (System.nanoTime() >= deadline) return null
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            if (total > maxBytes - read) return null
            output.write(chunk, 0, read)
            total += read
        }
        if (total == 0) return null
        return output.toByteArray()
    }

    internal fun decodeSampled(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
        if (bytes.isEmpty() || maxEdgePx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
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
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, pixels)
    }

    @VisibleForTesting
    internal fun inFlightCount(): Int = synchronized(inFlight) { inFlight.size }

    @VisibleForTesting
    internal fun openConnectionCount(): Int = synchronized(openConnections) { openConnections.size }

    /**
     * Bitmaps still reachable from completed in-flight entries. Zero after a
     * request has finished and its completion handler has run.
     */
    @VisibleForTesting
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun retainedCompletedBitmapCount(): Int = synchronized(inFlight) {
        inFlight.values.count { deferred ->
            deferred.isCompleted && !deferred.isCancelled &&
                runCatching { deferred.getCompleted() }.getOrNull() != null
        }
    }

    /**
     * Runs one empty task on every loader thread so a finished download's
     * stack slot is not the only thing still visible to a GC check.
     */
    @VisibleForTesting
    internal fun scrubWorkerStacksForTests() {
        val done = CountDownLatch(4)
        repeat(4) {
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
        loaderJob.cancel()
        loaderJob = SupervisorJob()
        loaderScope = CoroutineScope(loaderJob + loaderDispatcher)
        synchronized(inFlight) { inFlight.clear() }
        synchronized(openConnections) { openConnections.clear() }
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS
        callTimeoutMs = DEFAULT_CALL_TIMEOUT_MS
        maxBodyBytes = DEFAULT_MAX_BODY_BYTES
        BitmapCache.clear()
    }
}
