package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backlog D2: the interactive map answers "where/when/how far" for a tap or an instant. */
class TrackLocatorTest {
    // 5.5 m every 5 s: 1.1 m/s, 100 points = 544.5 m.
    private val walk = straightWalk(100)

    @Test
    fun `empty track answers null everywhere`() {
        assertNull(TrackLocator.nearest(emptyList(), 0.0, 0.0))
        assertNull(TrackLocator.atTime(emptyList(), 0L))
        assertEquals(0, cumulativeDistanceM(emptyList()).size)
    }

    @Test
    fun `cumulative distance grows by the leg length, skips pause legs and matches the segment stats`() {
        val cum = cumulativeDistanceM(walk)
        assertEquals(0.0, cum[0])
        assertEquals(5.5, cum[1], 0.05)
        assertEquals(99 * 5.5, cum.last(), 1.0)
        val pause = listOf(TimeInterval(walk[10].timeUtcMs, walk[20].timeUtcMs))
        val paused = cumulativeDistanceM(walk, pause)
        assertEquals(cum.last() - 10 * 5.5, paused.last(), 1.0)
        assertEquals(assertNotNull(computeSegmentStats(walk, pause).distanceM), paused.last(), "one rule for the card and the stage header")
        // A still episode: 72 jittery fixes within 1 m (0.2 m/s) add nothing, as in the stats.
        val still = (0 until 72).map { i -> walk.last().copy(timeUtcMs = walk.last().timeUtcMs + (i + 1) * 5_000L, lat = walk.last().lat + (if (i % 2 == 0) 1 else -1) * 0.5 / 111_320.0) }
        val withStill = cumulativeDistanceM(walk + still)
        assertEquals(cum.last(), withStill.last(), 1.0)
        assertEquals(assertNotNull(computeSegmentStats(walk + still).distanceM), withStill.last(), 1e-9)
    }

    @Test
    fun `duplicate timestamps add nothing and an instant inside a pause keeps the distance flat`() {
        val dup = walk.take(5) + walk[4].copy(lat = walk[4].lat + 0.01) + walk.drop(5)
        val cum = cumulativeDistanceM(dup)
        assertEquals(cum[4], cum[5], "a fix with the same time as the previous one does not count")
        val pause = listOf(TimeInterval(walk[10].timeUtcMs, walk[20].timeUtcMs))
        val at = assertNotNull(TrackLocator.atTime(walk, walk[15].timeUtcMs, pause))
        assertEquals(10 * 5.5, at.distanceM, 0.1)
        assertEquals(assertNotNull(TrackLocator.atTime(walk, walk[20].timeUtcMs, pause)).distanceM, at.distanceM, 0.1)
    }

    @Test
    fun `a precomputed cumulative array gives the same answers`() {
        val cum = cumulativeDistanceM(walk)
        assertEquals(TrackLocator.nearest(walk, walk[40].lat, walk[40].lon), TrackLocator.nearest(walk, walk[40].lat, walk[40].lon, cum = cum))
        assertEquals(TrackLocator.atTime(walk, walk[10].timeUtcMs + 2_500), TrackLocator.atTime(walk, walk[10].timeUtcMs + 2_500, cum = cum))
    }

    @Test
    fun `nearest projects onto the line and reports offset and progress`() {
        val target = walk[40]
        val pos = assertNotNull(TrackLocator.nearest(walk, target.lat, target.lon + 0.0001))
        assertEquals(target.timeUtcMs, pos.timeUtcMs)
        assertEquals(40 * 5.5, pos.distanceM, 0.5)
        assertEquals(9.1, pos.offsetM, 0.5, "0.0001° of longitude at 34.6°S ≈ 9.1 m")
        assertEquals(1.1, assertNotNull(pos.speedMps), 0.05)
        // Halfway along leg 10→11, 20 m to the side: the foot of the perpendicular, not vertex 10 or 11.
        val mid = assertNotNull(TrackLocator.nearest(walk, (walk[10].lat + walk[11].lat) / 2, walk[10].lon + 20 / (111_320.0 * Math.cos(Math.toRadians(-34.6)))))
        assertEquals(10, mid.index)
        assertEquals(walk[10].timeUtcMs + 2_500, mid.timeUtcMs)
        assertEquals(10.5 * 5.5, mid.distanceM, 0.1)
        assertEquals(20.0, mid.offsetM, 0.5)
        // Beyond the end of the track the answer is clamped to the last vertex, with the real offset.
        val past = assertNotNull(TrackLocator.nearest(walk, walk.last().lat + 100 / 111_320.0, walk.last().lon))
        assertEquals(walk.last().timeUtcMs, past.timeUtcMs)
        assertEquals(100.0, past.offsetM, 0.5)
        val lone = assertNotNull(TrackLocator.nearest(walk.take(1), walk[0].lat, walk[0].lon))
        assertEquals(0.0, lone.offsetM, 1e-6)
    }

    @Test
    fun `atTime interpolates between fixes and clamps outside the recording`() {
        val t = walk[10].timeUtcMs + 2_500 // halfway to point 11
        val pos = assertNotNull(TrackLocator.atTime(walk, t))
        assertEquals(10, pos.index)
        assertEquals(10.5 * 5.5, pos.distanceM, 0.1)
        assertEquals((walk[10].lat + walk[11].lat) / 2, pos.lat, 1e-9)
        assertEquals(0.0, pos.offsetM)
        assertEquals(0, assertNotNull(TrackLocator.atTime(walk, walk.first().timeUtcMs - 60_000)).index)
        val last = assertNotNull(TrackLocator.atTime(walk, walk.last().timeUtcMs + 60_000))
        assertEquals(walk.lastIndex, last.index)
        assertEquals(99 * 5.5, last.distanceM, 1.0)
    }

    @Test
    fun `altitude interpolates when both neighbours have one`() {
        val pts = walk.take(3).mapIndexed { i, p -> p.copy(altM = 100.0 + i * 10) }
        val pos = assertNotNull(TrackLocator.atTime(pts, pts[0].timeUtcMs + 2_500))
        assertEquals(105.0, assertNotNull(pos.altM), 1e-9)
        val holes = pts.map { it.copy(altM = null) }
        assertNull(TrackLocator.atTime(holes, pts[0].timeUtcMs + 2_500)?.altM)
    }

    @Test
    fun `speedAround averages the window and falls back to the fix speed for a lone point`() {
        assertEquals(1.1, assertNotNull(TrackLocator.speedAround(walk, 50)), 0.05)
        val lone = listOf(TrackPoint(segmentId = "s", timeUtcMs = 1L, lat = 0.0, lon = 0.0, speedMps = 3f))
        assertEquals(3.0, TrackLocator.speedAround(lone, 0))
        assertNull(TrackLocator.speedAround(lone.map { it.copy(speedMps = null) }, 0))
        assertNull(TrackLocator.speedAround(walk, 500))
        assertTrue(assertNotNull(TrackLocator.speedAround(walk, 0)) > 0)
        val pause = listOf(TimeInterval(walk[48].timeUtcMs, walk[52].timeUtcMs))
        assertEquals(1.1, assertNotNull(TrackLocator.speedAround(walk, 50, pause)), 0.05, "pause legs are left out of the window")
    }
}
