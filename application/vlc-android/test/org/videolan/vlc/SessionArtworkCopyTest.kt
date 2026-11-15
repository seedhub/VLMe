package org.videolan.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SessionArtworkCopyTest {
    @Test
    fun sameArtAcrossPublishesCopiesOnce() {
        var copies = 0
        val art = SessionArtworkCopy<Any> { copies += 1; Any() }
        val source = Any()

        val first = art.publishedArt("track", source)
        val second = art.publishedArt("track", source)
        val third = art.publishedArt("track", source)

        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, copies)
    }

    @Test
    fun newArtOrMediaCopiesAgain() {
        var copies = 0
        val art = SessionArtworkCopy<Any> { copies += 1; Any() }
        val source = Any()
        val first = art.publishedArt("track-a", source)

        val otherSource = Any()
        val second = art.publishedArt("track-a", otherSource)
        val third = art.publishedArt("track-b", otherSource)

        assertNotSame(first, second)
        assertNotSame(second, third)
        assertEquals(3, copies)

        art.drop()
        val fourth = art.publishedArt("track-b", otherSource)
        assertNotSame(third, fourth)
        assertEquals(4, copies)
    }
}
