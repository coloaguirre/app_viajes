package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimplifyTest {
    @Test
    fun `straight line collapses to its endpoints`() {
        val pts = straightWalk(1000)
        assertEquals(listOf(0, 999), simplifyIndices(pts, 5.0))
    }

    @Test
    fun `a corner is kept and first-last always survive`() {
        val leg1 = straightWalk(50)
        val corner = leg1.last()
        val leg2 = (1..50).map { i -> TrackPoint(segmentId = "s", timeUtcMs = corner.timeUtcMs + i * 5_000L, lat = corner.lat, lon = corner.lon + i * 5.5 / (111_320 * Math.cos(Math.toRadians(corner.lat)))) }
        val idx = simplifyIndices(leg1 + leg2, 5.0)
        assertEquals(listOf(0, 49, 99), idx)
    }

    @Test
    fun `tiny inputs are returned as is and large inputs do not overflow`() {
        assertEquals(listOf(0), simplifyIndices(straightWalk(1), 5.0))
        assertEquals(listOf(0, 1), simplifyIndices(straightWalk(2), 5.0))
        val big = (0 until 100_000).map { i -> TrackPoint(segmentId = "s", timeUtcMs = i.toLong(), lat = -34.6 + i * 1e-5, lon = -58.4 + Math.sin(i / 50.0) * 1e-4) }
        // ~9 m sine wobble: invisible at 30 m tolerance, kept at 5 m.
        assertEquals(listOf(0, 99_999), simplifyIndices(big, 30.0))
        val idx = simplifyIndices(big, 5.0)
        assertTrue(idx.size in 3 until 100_000)
        assertEquals(0, idx.first())
        assertEquals(99_999, idx.last())
    }
}
