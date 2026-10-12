package com.bitacoraviaje.app

import com.bitacoraviaje.app.tracking.LiveTrackingState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingFormatTest {
    @Test
    fun `elapsed and distance formatting`() {
        assertEquals("0 min", TrackingNotificationFactory.formatElapsed(59_000))
        assertEquals("14 min", TrackingNotificationFactory.formatElapsed(14 * 60_000L))
        assertEquals("2 h 05 min", TrackingNotificationFactory.formatElapsed((2 * 3600 + 5 * 60) * 1000L))
        assertEquals("850 m", TrackingNotificationFactory.formatKm(850.0))
        assertEquals("8.3 km", TrackingNotificationFactory.formatKm(8_320.0))
    }

    @Test
    fun `elapsed excludes accumulated and current pauses`() {
        val s = LiveTrackingState(startedUtcMs = 1_000_000, pausedAccumMs = 60_000, pausedSinceUtcMs = 1_300_000)
        // now = start + 400 s; paused 60 s earlier and 100 s currently -> 240 s elapsed
        assertEquals(240_000L, s.elapsedMs(1_400_000))
        assertEquals(0L, LiveTrackingState().elapsedMs(5))
    }
}
