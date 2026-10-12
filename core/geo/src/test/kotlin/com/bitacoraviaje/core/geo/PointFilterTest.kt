package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointFilterTest {
    private val walk = FilterConfig.WALK

    @Test
    fun `accepts a clean walk and sums its distance`() {
        val (pts, dist) = acceptedBy(walk, straightWalk(100))
        assertEquals(100, pts.size)
        assertEquals(99 * 5.5, dist, 1.0)
    }

    @Test
    fun `rejects low accuracy, duplicate time and backwards time`() {
        val f = PointFilter(walk)
        val a = straightWalk(3)
        assertTrue(f.offer(a[0]).accepted)
        assertEquals(RejectReason.LOW_ACCURACY, f.offer(a[1].copy(hAccuracyM = 40f)).reason)
        assertEquals(RejectReason.DUPLICATE_TIME, f.offer(a[0]).reason)
        assertEquals(RejectReason.DUPLICATE_TIME, f.offer(a[1].copy(timeUtcMs = a[0].timeUtcMs - 1)).reason)
        assertTrue(f.offer(a[1]).accepted)
    }

    @Test
    fun `an isolated impossible jump is dropped`() {
        val base = straightWalk(10)
        val spike = base[5].copy(lat = base[5].lat + 0.01) // ~1.1 km in 5 s
        val raw = base.take(5) + spike + base.drop(6)
        val (pts, _) = acceptedBy(walk, raw)
        assertEquals(9, pts.size)
        assertFalse(pts.any { it.lat == spike.lat })
    }

    @Test
    fun `a jump confirmed by the next fixes is a relocation and gets reinstated`() {
        // 5 fixes here, then a 30 s gap and 6 fixes 2 km away (bus ride with GPS off).
        val here = straightWalk(5)
        val thereT0 = here.last().timeUtcMs + 30_000
        val there = straightWalk(6, lat0 = -34.6 + 0.02, t0 = thereT0)
        val (pts, dist) = acceptedBy(walk, here + there)
        assertEquals(11, pts.size, "all fixes of the new area are kept once confirmed")
        assertTrue(dist < 100.0, "the teleport leg is not counted, got $dist")
    }

    @Test
    fun `standing still adds no distance despite GPS wobble`() {
        val t0 = 1_700_000_000_000L
        val raw = (0 until 60).map { i ->
            val wobble = if (i % 2 == 0) 0.0 else 4.0 / 111_320.0
            TrackPoint(segmentId = "s", timeUtcMs = t0 + i * 5_000L, lat = -34.6 + wobble, lon = -58.4, hAccuracyM = 5f, speedMps = 0.1f)
        }
        val (pts, dist) = acceptedBy(walk, raw)
        assertEquals(60, pts.size, "still points are kept, they just add nothing")
        assertEquals(0.0, dist, 1e-6)
    }

    @Test
    fun `profiles map transports to thresholds`() {
        assertEquals(70.0, FilterConfig.forTransport(com.bitacoraviaje.core.model.Transport.BUS).maxSpeedMps)
        assertEquals(4.0, FilterConfig.forTransport(null).maxSpeedMps)
    }
}
