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
        assertEquals(0, TrackLocator.cumulative(emptyList()).size)
    }

    @Test
    fun `cumulative distance grows by the leg length and skips pause legs`() {
        val cum = TrackLocator.cumulative(walk)
        assertEquals(0.0, cum[0])
        assertEquals(5.5, cum[1], 0.05)
        assertEquals(99 * 5.5, cum.last(), 1.0)
        val pause = listOf(TimeInterval(walk[10].timeUtcMs, walk[20].timeUtcMs))
        val paused = TrackLocator.cumulative(walk, pause)
        assertEquals(cum.last() - 10 * 5.5, paused.last(), 1.0)
    }

    @Test
    fun `nearest picks the closest fix and reports its offset and progress`() {
        val target = walk[40]
        val pos = assertNotNull(TrackLocator.nearest(walk, target.lat, target.lon + 0.0001))
        assertEquals(40, pos.index)
        assertEquals(target.timeUtcMs, pos.timeUtcMs)
        assertEquals(40 * 5.5, pos.distanceM, 0.5)
        assertEquals(9.1, pos.offsetM, 0.5, "0.0001° of longitude at 34.6°S ≈ 9.1 m")
        assertEquals(1.1, assertNotNull(pos.speedMps), 0.05)
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
    }
}
