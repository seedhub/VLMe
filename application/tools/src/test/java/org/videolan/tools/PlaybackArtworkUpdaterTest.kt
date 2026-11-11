package org.videolan.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Artwork is a string token here so the scheduling rules can run without Android bitmaps.
 * PlaybackService supplies the real bitmap loader.
 */
class PlaybackArtworkUpdaterTest {

    @Test(timeout = 10_000)
    fun stalledArtworkDoesNotBlockPlayerEventsOrNotificationState() = runBlocking {
        val gate = CompletableDeferred<String?>()
        val loads = AtomicInteger(0)
        val seen = CopyOnWriteArrayList<String>()
        val updater = PlaybackArtworkUpdater(
            scope = this,
            cached = { null },
            load = {
                loads.incrementAndGet()
                gate.await()
            },
            isCurrent = { true },
            onArtwork = { _, _ -> seen.add("artwork") },
        )
        val commands = Channel<String>(Channel.UNLIMITED)
        val processor = launch {
            for (command in commands) {
                if (command.startsWith("meta:")) {
                    updater.request(command.removePrefix("meta:"), "http://art.example/$command")
                } else {
                    seen.add(command)
                }
            }
        }
        commands.trySend("meta:track-1")
        commands.trySend("player:playing")
        commands.trySend("player:paused")
        commands.trySend("callback:add")
        commands.trySend("callback:remove")
        commands.trySend("notification:show")
        delay(150)
        assertEquals(
            listOf("player:playing", "player:paused", "callback:add", "callback:remove", "notification:show"),
            seen.toList(),
        )
        assertEquals(1, loads.get())
        gate.complete("art-1")
        delay(150)
        assertTrue(seen.contains("artwork"))
        commands.close()
        processor.join()
    }

    @Test(timeout = 10_000)
    fun rapidMediaChangeDropsStaleArtworkAndKeepsTheLatest() = runBlocking {
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>()
        var current = "A"
        val delivered = CopyOnWriteArrayList<Pair<String, String?>>()
        val updater = PlaybackArtworkUpdater(
            scope = this,
            cached = { null },
            load = { url -> if (url.endsWith("/a")) first.await() else second.await() },
            isCurrent = { it == current },
            onArtwork = { key, artwork -> delivered.add(key to artwork) },
        )
        assertNull(updater.request("A", "http://art.example/a"))
        current = "B"
        assertNull(updater.request("B", "http://art.example/b"))
        first.complete("art-A")
        second.complete("art-B")
        delay(200)
        assertEquals(listOf("B" to "art-B"), delivered.toList())
    }

    @Test(timeout = 10_000)
    fun cancelPreventsDeliveryAndALaterRequestCanRetry() = runBlocking {
        val gate = CompletableDeferred<String?>()
        var attempts = 0
        val delivered = CopyOnWriteArrayList<String?>()
        val updater = PlaybackArtworkUpdater(
            scope = this,
            cached = { null },
            load = {
                attempts++
                if (attempts == 1) gate.await() else "art-retry"
            },
            isCurrent = { true },
            onArtwork = { _, artwork -> delivered.add(artwork) },
        )
        updater.request("A", "http://art.example/a")
        delay(50)
        updater.cancel()
        gate.complete("art-abandoned")
        delay(100)
        assertTrue(delivered.isEmpty())
        updater.request("A", "http://art.example/a")
        delay(150)
        assertEquals(listOf("art-retry"), delivered.toList())
    }

    @Test(timeout = 10_000)
    fun failurePublishesNullAndARetryCanSucceed() = runBlocking {
        var fail = true
        val delivered = CopyOnWriteArrayList<String?>()
        val updater = PlaybackArtworkUpdater(
            scope = this,
            cached = { null },
            load = { if (fail) null else "art-ok" },
            isCurrent = { true },
            onArtwork = { _, artwork -> delivered.add(artwork) },
        )
        updater.request("A", "http://art.example/a")
        delay(100)
        assertEquals(listOf<String?>(null), delivered.toList())
        fail = false
        updater.request("A", "http://art.example/a")
        delay(100)
        assertEquals(listOf(null, "art-ok"), delivered.toList())
    }

    @Test(timeout = 10_000)
    fun cacheHitDoesNotStartADownload() = runBlocking {
        var loads = 0
        val updater = PlaybackArtworkUpdater(
            scope = this,
            cached = { url -> if (url == "http://art.example/a") "cached" else null },
            load = { loads++; "network" },
            isCurrent = { true },
            onArtwork = { _, _ -> },
        )
        assertEquals("cached", updater.request("A", "http://art.example/a"))
        delay(50)
        assertEquals(0, loads)
    }

    @Test(timeout = 10_000)
    fun sameMediaDoesNotStartASecondDownload() = runBlocking {
        val gate = CompletableDeferred<String?>()
        val loads = AtomicInteger(0)
        val updater = PlaybackArtworkUpdater(
            scope = CoroutineScope(Dispatchers.Default),
            cached = { null },
            load = {
                loads.incrementAndGet()
                gate.await()
            },
            isCurrent = { true },
            onArtwork = { _, _ -> },
        )
        updater.request("A", "http://art.example/a")
        delay(50)
        updater.request("A", "http://art.example/a")
        delay(50)
        assertEquals(1, loads.get())
        gate.complete(null)
        Unit
    }
}
