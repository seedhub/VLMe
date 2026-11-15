package org.videolan.vlc.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSaveThrottleTest {
    @Test
    fun periodicSaveWaitsTenSecondsThenAllowsOne() {
        var now = 0L
        val throttle = PositionSaveThrottle { now }

        assertFalse(throttle.shouldSavePeriodic())
        now = 9_999L
        assertFalse(throttle.shouldSavePeriodic())
        now = 10_000L
        assertTrue(throttle.shouldSavePeriodic())
        throttle.markSaved()

        now = 19_999L
        assertFalse(throttle.shouldSavePeriodic())
        now = 20_000L
        assertTrue(throttle.shouldSavePeriodic())
    }

    @Test
    fun pauseOrSeekSaveStartsANewTenSecondWindow() {
        var now = 0L
        val throttle = PositionSaveThrottle { now }

        now = 10_000L
        assertTrue(throttle.shouldSavePeriodic())
        throttle.markSaved()

        now = 16_000L
        throttle.markSaved()

        now = 25_999L
        assertFalse(throttle.shouldSavePeriodic())
        now = 26_000L
        assertTrue(throttle.shouldSavePeriodic())
    }
}
