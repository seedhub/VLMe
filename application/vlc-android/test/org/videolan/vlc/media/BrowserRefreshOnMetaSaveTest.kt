package org.videolan.vlc.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRefreshOnMetaSaveTest {
    @Test
    fun periodicTimeChangeDoesNotRebrowse() {
        assertFalse(shouldRefreshBrowserOnMetaSave(audioPlayerShown = false, fromPeriodicUpdate = true))
        assertFalse(shouldRefreshBrowserOnMetaSave(audioPlayerShown = true, fromPeriodicUpdate = true))
    }

    @Test
    fun stopOrEndRebrowsersWhenTheAudioPlayerIsNotShown() {
        assertTrue(shouldRefreshBrowserOnMetaSave(audioPlayerShown = false, fromPeriodicUpdate = false))
        assertFalse(shouldRefreshBrowserOnMetaSave(audioPlayerShown = true, fromPeriodicUpdate = false))
    }
}
