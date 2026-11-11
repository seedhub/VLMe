package org.videolan.tools

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HttpImageLoaderTest {

    private lateinit var server: LocalImageServer

    @Before
    fun setUp() {
        // Robolectric otherwise returns a bitmap for bytes a device would reject.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        HttpImageLoader.resetForTests()
        server = LocalImageServer()
    }

    @After
    fun tearDown() {
        server.close()
        HttpImageLoader.resetForTests()
        ShadowBitmapFactory.reset()
    }

    @Test(timeout = 20_000)
    fun cancelledMidDownloadThenEvictThenRetry() = runBlocking {
        val url = server.url("/cancel.png")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        stallFirstRequest(started, release)

        val caller = CoroutineScope(Job())
        val first = caller.async { HttpImageLoader.downloadBitmap(url) }
        assertTrue("download never reached the server", started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        caller.cancel()
        try {
            first.await()
            fail("cancelled caller should not return a bitmap")
        } catch (expected: CancellationException) {
            // The caller's wait is cancelled. The shared download keeps running.
        }

        release.countDown()
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
        assertEquals(0, HttpImageLoader.openConnectionCount())
        assertNotNull("successful download must still fill the cache", BitmapCache.getBitmapFromMemCache(url))
        assertTrue(server.awaitClientCloses(1, 3_000))

        BitmapCache.clear()
        assertNull(BitmapCache.getBitmapFromMemCache(url))
        val retried = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(retried)
        assertEquals(1, retried!!.width)
        assertEquals(1, retried.height)
        assertEquals(2, server.requests.get())
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
    }

    @Test(timeout = 20_000)
    fun failedResponseDoesNotPoisonRetry() = runBlocking {
        val url = server.url("/fails-then-ok.png")
        val seen = AtomicInteger(0)
        server.handler.set { session ->
            if (seen.getAndIncrement() == 0) session.respond(500, ByteArray(0), "Error")
            else session.respond(200, LocalImageServer.TINY_PNG)
            session.trackClientClose(3_000)
        }
        assertNull(HttpImageLoader.downloadBitmap(url))
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
        assertNull(BitmapCache.getBitmapFromMemCache(url))

        val retried = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(retried)
        assertEquals(2, server.requests.get())
        assertTrue(server.awaitClientCloses(2, 3_000))
        assertEquals(0, HttpImageLoader.openConnectionCount())
    }

    @Test(timeout = 20_000)
    fun undecodableBodyCanBeRetried() = runBlocking {
        val url = server.url("/garbage-then-png")
        val seen = AtomicInteger(0)
        server.handler.set { session ->
            if (seen.getAndIncrement() == 0) session.respond(200, "this is not an image".toByteArray())
            else session.respond(200, LocalImageServer.TINY_PNG)
            session.trackClientClose(3_000)
        }
        assertNull(HttpImageLoader.downloadBitmap(url))
        awaitIdle()
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
        val retried = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(retried)
        assertEquals(2, server.requests.get())
    }

    @Test(timeout = 20_000)
    fun sharedCallersIssueOneRequest() = runBlocking {
        val url = server.url("/shared.png")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.handler.set { session ->
            session.holdAfterFirstByte(LocalImageServer.TINY_PNG, started, release)
            session.trackClientClose(3_000)
        }
        val first = async(Dispatchers.IO) { HttpImageLoader.downloadBitmap(url) }
        val second = async(Dispatchers.IO) { HttpImageLoader.downloadBitmap(url) }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        delay(200)
        assertEquals(1, server.requests.get())
        release.countDown()
        val a = first.await()
        val b = second.await()
        assertNotNull(a)
        assertSame(a, b)
        assertEquals(1, server.requests.get())
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
        assertEquals(0, HttpImageLoader.openConnectionCount())
        assertTrue(server.awaitClientCloses(1, 3_000))
    }

    @Test(timeout = 20_000)
    fun cancellingOneWaiterLeavesTheOtherAndASingleRequest() = runBlocking {
        val url = server.url("/one-waiter.png")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        stallFirstRequest(started, release)

        val scopeA = CoroutineScope(Job())
        val scopeB = CoroutineScope(Job())
        val waiterA = scopeA.async { HttpImageLoader.downloadBitmap(url) }
        val waiterB = scopeB.async { HttpImageLoader.downloadBitmap(url) }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        scopeA.cancel()
        try {
            waiterA.await()
            fail("cancelled waiter should throw")
        } catch (expected: CancellationException) {
        }
        assertEquals(1, server.requests.get())
        release.countDown()
        val bitmap = withTimeout(5_000) { waiterB.await() }
        assertNotNull(bitmap)
        assertEquals(1, server.requests.get())
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())

        BitmapCache.clear()
        val again = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(again)
        assertEquals(2, server.requests.get())
    }

    @Test(timeout = 20_000)
    fun completedDownloadsKeepNoBitmapOutsideTheCache() {
        // GC runs after this blocking scope returns, so the coroutine that
        // awaited the bitmap is no longer a live root.
        val refs = runBlocking {
            val loaded = ArrayList<WeakReference<Bitmap>>()
            repeat(3) { index ->
                loaded.add(loadWeak(server.url("/done-$index.png")))
            }
            awaitIdle()
            assertEquals(3, server.requests.get())
            assertEquals(0, HttpImageLoader.inFlightCount())
            assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
            assertEquals(0, HttpImageLoader.openConnectionCount())
            assertTrue(server.awaitClientCloses(3, 3_000))
            BitmapCache.clear()
            repeat(3) { index ->
                assertNull(BitmapCache.getBitmapFromMemCache(server.url("/done-$index.png")))
            }
            HttpImageLoader.scrubWorkerStacksForTests()
            loaded
        }
        assertCollected(refs)
    }

    @Test
    fun decodeUsesTheSameBytesForBoundsAndPixels() {
        val bitmap = HttpImageLoader.decodeSampled(LocalImageServer.TINY_PNG, 150)
        assertNotNull(bitmap)
        assertEquals(1, bitmap!!.width)
        assertEquals(1, bitmap.height)
        assertNull(HttpImageLoader.decodeSampled("this is not an image".toByteArray(), 150))
        assertNull(HttpImageLoader.decodeSampled(ByteArray(0), 150))
    }

    @Test(timeout = 20_000)
    fun readTimeoutClosesAndAllowsRetry() = runBlocking {
        HttpImageLoader.connectTimeoutMs = 2_000
        HttpImageLoader.readTimeoutMs = 400
        HttpImageLoader.callTimeoutMs = 15_000
        val url = server.url("/read-timeout.png")
        val seen = AtomicInteger(0)
        server.handler.set { session ->
            if (seen.getAndIncrement() == 0) {
                session.trackClientClose(5_000)
            } else {
                session.respond(200, LocalImageServer.TINY_PNG)
                session.trackClientClose(3_000)
            }
        }
        val startedAt = System.nanoTime()
        assertNull(HttpImageLoader.downloadBitmap(url))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("read timeout returned too fast (${elapsedMs}ms)", elapsedMs >= 250)
        assertTrue("read timeout did not fire (${elapsedMs}ms)", elapsedMs < 3_000)
        awaitIdle()
        assertEquals(0, HttpImageLoader.openConnectionCount())
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertTrue("socket was not closed after read timeout", server.awaitClientCloses(1, 3_000))

        val retried = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(retried)
        assertEquals(2, server.requests.get())
    }

    @Test(timeout = 20_000)
    fun overallDeadlineAbortsAStalledRead() = runBlocking {
        HttpImageLoader.connectTimeoutMs = 5_000
        HttpImageLoader.readTimeoutMs = 15_000
        HttpImageLoader.callTimeoutMs = 400
        val url = server.url("/overall-timeout.png")
        server.handler.set { session ->
            session.trackClientClose(5_000)
        }
        val startedAt = System.nanoTime()
        assertNull(HttpImageLoader.downloadBitmap(url))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("overall deadline returned too fast (${elapsedMs}ms)", elapsedMs >= 250)
        assertTrue("overall deadline did not abort the read (${elapsedMs}ms)", elapsedMs < 3_000)
        awaitIdle()
        assertEquals(0, HttpImageLoader.openConnectionCount())
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertTrue(server.awaitClientCloses(1, 3_000))

        server.handler.set { session ->
            session.respond(200, LocalImageServer.TINY_PNG)
            session.trackClientClose(3_000)
        }
        assertNotNull(withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) })
    }

    @Test(timeout = 20_000)
    fun connectTimeoutIsFiniteAndDoesNotPoisonTheLoader() = runBlocking {
        HttpImageLoader.connectTimeoutMs = 500
        HttpImageLoader.readTimeoutMs = 500
        HttpImageLoader.callTimeoutMs = 3_000
        val startedAt = System.nanoTime()
        assertNull(HttpImageLoader.downloadBitmap("http://192.0.2.1:81/blackhole.png"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("connect did not give up (${elapsedMs}ms)", elapsedMs < 4_000)
        awaitIdle()
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.openConnectionCount())

        val recovered = withTimeout(5_000) { HttpImageLoader.downloadBitmap(server.url("/after-connect-timeout.png")) }
        assertNotNull(recovered)
    }

    @Test(timeout = 20_000)
    fun bodyOverTheCapIsClosedAndCanBeRetried() = runBlocking {
        HttpImageLoader.maxBodyBytes = 32
        val url = server.url("/too-big.png")
        val seen = AtomicInteger(0)
        server.handler.set { session ->
            if (seen.getAndIncrement() == 0) session.respond(200, ByteArray(256) { 7 })
            else session.respond(200, LocalImageServer.TINY_PNG)
            session.trackClientClose(3_000)
        }
        assertNull(HttpImageLoader.downloadBitmap(url))
        awaitIdle()
        assertEquals(0, HttpImageLoader.openConnectionCount())
        assertEquals(0, HttpImageLoader.inFlightCount())
        assertEquals(0, HttpImageLoader.retainedCompletedBitmapCount())
        assertNull(BitmapCache.getBitmapFromMemCache(url))
        assertTrue(server.awaitClientCloses(1, 3_000))

        HttpImageLoader.maxBodyBytes = HttpImageLoader.DEFAULT_MAX_BODY_BYTES
        val retried = withTimeout(5_000) { HttpImageLoader.downloadBitmap(url) }
        assertNotNull(retried)
        assertEquals(2, server.requests.get())
    }

    private fun stallFirstRequest(started: CountDownLatch, release: CountDownLatch) {
        val seen = AtomicInteger(0)
        server.handler.set { session ->
            if (seen.getAndIncrement() == 0) {
                session.holdAfterFirstByte(LocalImageServer.TINY_PNG, started, release)
            } else {
                session.respond(200, LocalImageServer.TINY_PNG)
            }
            session.trackClientClose(3_000)
        }
    }

    private suspend fun awaitIdle() {
        withTimeout(5_000) {
            while (HttpImageLoader.inFlightCount() != 0) delay(20)
        }
    }

    private suspend fun loadWeak(url: String): WeakReference<Bitmap> {
        val bitmap = HttpImageLoader.downloadBitmap(url)
        assertNotNull(bitmap)
        return WeakReference(bitmap)
    }

    private fun assertCollected(refs: List<WeakReference<Bitmap>>) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            System.gc()
            System.runFinalization()
            if (refs.all { it.get() == null }) return
            Thread.sleep(50)
        }
        fail(
            "completed downloads still retained ${refs.count { it.get() != null }} bitmap(s) " +
                "after BitmapCache.clear()"
        )
    }
}
