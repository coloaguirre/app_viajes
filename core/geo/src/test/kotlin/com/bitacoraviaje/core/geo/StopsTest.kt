package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopsTest {
    private val t0 = 1_700_000_000_000L

    private fun cluster(lat: Double, lon: Double, fromMs: Long, minutes: Int, noiseM: Double = 10.0): List<TrackPoint> {
        val rnd = java.util.Random(1)
        return (0 until minutes * 12).map { i ->
            TrackPoint(segmentId = "s", timeUtcMs = fromMs + i * 5_000L, lat = lat + rnd.nextGaussian() * noiseM / 111_320, lon = lon + rnd.nextGaussian() * noiseM / 111_320)
        }
    }

    @Test
    fun `ten minutes of noise around a point is one stop`() {
        val stops = detectStops(cluster(-34.6, -58.4, t0, 10))
        assertEquals(1, stops.size)
        assertTrue(stops[0].endUtcMs - stops[0].startUtcMs >= 9 * 60_000)
        assertTrue(stops[0].radiusM < 50.0)
    }

    @Test
    fun `continuous walk has no stops`() {
        assertEquals(emptyList(), detectStops(straightWalk(600)))
    }

    @Test
    fun `two cafes 30 m apart are merged`() {
        val a = cluster(-34.6, -58.4, t0, 6)
        val b = cluster(-34.6 + 30.0 / 111_320, -58.4, a.last().timeUtcMs + 60_000, 6)
        assertEquals(1, detectStops(a + b).size)
    }

    @Test
    fun `a gap with the same place before and after is a stop`() {
        val before = straightWalk(12) // 1 min walking in
        val last = before.last()
        val after = TrackPoint(segmentId = "s", timeUtcMs = last.timeUtcMs + 25 * 60_000, lat = last.lat + 8.0 / 111_320, lon = last.lon)
        val out = straightWalk(12, lat0 = after.lat, t0 = after.timeUtcMs + 5_000)
        val stops = detectStops(before + after + out)
        assertEquals(1, stops.size)
        assertTrue(stops[0].endUtcMs - stops[0].startUtcMs >= 25 * 60_000)
    }

    @Test
    fun `stops never overlap and are time ordered`() {
        val a = cluster(-34.6, -58.4, t0, 7)
        val walk = straightWalk(120, lat0 = -34.6 + 0.001, t0 = a.last().timeUtcMs + 5_000)
        val b = cluster(-34.6 + 0.01, -58.4, walk.last().timeUtcMs + 5_000, 7)
        val stops = detectStops(a + walk + b)
        assertEquals(2, stops.size)
        assertTrue(stops[0].endUtcMs < stops[1].startUtcMs)
    }
}
