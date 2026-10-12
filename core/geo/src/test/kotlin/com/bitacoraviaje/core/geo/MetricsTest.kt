package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsTest {
    @Test
    fun `elevation hysteresis ignores noise below the threshold`() {
        val noisy = List(50) { 100.0 + (if (it % 2 == 0) 1.0 else -1.0) }
        assertEquals(0.0 to 0.0, elevationGainLoss(noisy))
        val climb = (0..100).map { 100.0 + it * 2.0 } // +200 m smooth climb
        val (gain, loss) = elevationGainLoss(climb)
        assertEquals(200.0, gain, 6.0)
        assertEquals(0.0, loss)
    }

    @Test
    fun `stats on a straight walk`() {
        val pts = straightWalk(121) // 10 minutes, 660 m
        val s = computeSegmentStats(pts, transport = Transport.WALK)
        assertEquals(660.0, s.distanceM!!, 2.0)
        assertEquals(600_000L, s.movingTimeMs)
        assertEquals(1.1, s.avgMovingSpeedMps!!, 0.01)
        assertEquals(1.1, s.maxSpeedMps!!, 0.01)
        assertNull(s.elevationGainM, "no altitudes in fixture")
        assertNotNull(s.bbox)
    }

    @Test
    fun `pauses and long gaps do not count`() {
        val a = straightWalk(13) // 1 minute
        val gapStart = a.last().timeUtcMs
        val b = straightWalk(13, lat0 = -34.6 + 0.01, t0 = gapStart + 20 * 60_000) // 1 km away after 20 min
        val s = computeSegmentStats(a + b, pauses = listOf(TimeInterval(gapStart, gapStart + 20 * 60_000)))
        assertEquals(2 * 12 * 5.5, s.distanceM!!, 2.0)
        assertEquals(120_000L, s.movingTimeMs)
        // Same without a declared pause: the 20 min leg is a gap (not moving time) but its distance counts.
        val s2 = computeSegmentStats(a + b)
        assertEquals(120_000L, s2.movingTimeMs)
        assertTrue(s2.distanceM!! > 1_000.0)
    }

    @Test
    fun `empty input yields empty stats`() {
        val s = computeSegmentStats(emptyList(), totalTimeMs = 5)
        assertNull(s.distanceM)
        assertEquals(5L, s.totalTimeMs)
    }
}
