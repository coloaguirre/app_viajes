package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteNamingTest {
    private fun line(n: Int, lat0: Double = -49.30, lon0: Double = -72.90, stepLat: Double = 0.0002, offsetLon: Double = 0.0) =
        (0 until n).map { i -> (lat0 + i * stepLat) to (lon0 + offsetLon) }

    private fun track(n: Int, offsetLon: Double = 0.0) = line(n, offsetLon = offsetLon).mapIndexed { i, (la, lo) -> TrackPoint(segmentId = "s", timeUtcMs = i * 10_000L, lat = la, lon = lo) }

    @Test
    fun `coverage counts points within 30 m of the trail`() {
        val trail = line(50)
        assertEquals(1.0, TrailMatch.coverage(track(50), trail))
        // 0.001° of longitude at 49° S ≈ 73 m: outside the 30 m band.
        assertEquals(0.0, TrailMatch.coverage(track(50, offsetLon = 0.001), trail))
        // 0.0003° ≈ 22 m: inside.
        assertEquals(1.0, TrailMatch.coverage(track(50, offsetLon = 0.0003), trail))
        assertEquals(0.0, TrailMatch.coverage(emptyList(), trail))
    }

    @Test
    fun `best trail needs 60 percent coverage and merges split ways of the same name`() {
        val tr = track(100)
        val halfA = TrailCandidate("Sendero a Laguna de los Tres", line(50))
        val halfB = TrailCandidate("Sendero a Laguna de los Tres", line(50, lat0 = -49.30 + 50 * 0.0002))
        val other = TrailCandidate("Sendero Capri", line(30, offsetLon = 0.0001))
        val best = assertNotNull(TrailMatch.bestTrail(tr, listOf(halfA, halfB, other)))
        assertEquals("Sendero a Laguna de los Tres", best.first.name)
        assertTrue(best.second >= 0.99)
        assertNull(TrailMatch.bestTrail(tr, listOf(other)), "30 % coverage is not enough")
    }

    @Test
    fun `route name composes road and localities`() {
        val samples = listOf(PlaceSample("RN 40", "El Calafate"), PlaceSample("RN 40", null), PlaceSample("RN 40", "El Chaltén"), PlaceSample("RP 23", "El Chaltén"))
        assertEquals("RN 40, de El Calafate a El Chaltén", RouteNaming.routeName(samples))
        assertEquals("Por El Chaltén", RouteNaming.routeName(listOf(PlaceSample(null, "El Chaltén"), PlaceSample(null, "El Chaltén"))))
        assertEquals("Avenida Corrientes", RouteNaming.routeName(listOf(PlaceSample("Avenida Corrientes", null))))
        assertNull(RouteNaming.routeName(listOf(PlaceSample(null, null))))
    }
}
