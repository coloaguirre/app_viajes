package com.bitacoraviaje.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceRankingTest {
    private fun c(key: String, cls: PlaceClass, dist: Double, dwell: Long = 0, photos: Boolean = false, notes: Boolean = false, links: Int = 0) =
        PlaceCandidate(key, key, -49.0, -72.0, cls, dist, dwell, photos, notes, links)

    @Test
    fun `radius depends on the class of place`() {
        val r = PlaceRanking.rank(listOf(c("poi-far", PlaceClass.POI, 400.0), c("town-far", PlaceClass.LOCALITY, 4_900.0), c("peak-far", PlaceClass.NATURAL, 9_999.0), c("peak-too-far", PlaceClass.NATURAL, 10_001.0)))
        assertEquals(listOf("town-far", "peak-far").toSet(), r.map { it.candidate.key }.toSet())
    }

    @Test
    fun `same article found by two anchors is one place with the closest distance and merged flags`() {
        val r = PlaceRanking.rank(listOf(c("Q1", PlaceClass.LOCALITY, 3_000.0, photos = true), c("Q1", PlaceClass.LOCALITY, 500.0, dwell = 600_000, links = 20)))
        assertEquals(1, r.size)
        val p = r.single().candidate
        assertEquals(500.0, p.distM)
        assertTrue(p.hasPhotos && p.dwellMs == 600_000L && p.sitelinks == 20)
    }

    @Test
    fun `a stop with photos and notes outranks a famous drive-by and the list is capped at five`() {
        val cands = listOf(
            c("famous", PlaceClass.LOCALITY, 4_000.0, links = 200),
            c("stop", PlaceClass.POI, 20.0, dwell = 1_800_000, photos = true, notes = true, links = 3),
        ) + (1..10).map { c("filler$it", PlaceClass.LOCALITY, 100.0 * it, links = it) }
        val r = PlaceRanking.rank(cands)
        assertEquals(5, r.size)
        assertEquals("stop", r.first().candidate.key)
        assertTrue(r.zipWithNext().all { (a, b) -> a.score >= b.score }, "sorted by score")
    }

    @Test
    fun `empty input and zero limit`() {
        assertEquals(emptyList(), PlaceRanking.rank(emptyList()))
        assertEquals(emptyList(), PlaceRanking.rank(listOf(c("x", PlaceClass.POI, 1.0)), limit = 0))
    }

    @Test
    fun `coordinate rounding to about one kilometre and ten metres`() {
        assertEquals(-49.33, CoordinateRounding.round(-49.331408, 2))
        assertEquals(-72.8861, CoordinateRounding.round(-72.886129, 4))
        assertEquals(0.0, CoordinateRounding.round(0.004, 2))
    }
}
