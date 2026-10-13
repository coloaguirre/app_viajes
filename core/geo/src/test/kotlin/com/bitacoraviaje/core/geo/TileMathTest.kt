package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN 5.4: tile counts drive the size estimate and the zoom cap of an offline region. */
class TileMathTest {
    private val world = BoundingBox(-85.0511, -180.0, 85.0511, 180.0)
    private val calafate = BoundingBox(-50.35, -72.45, -50.25, -72.20) // ~11 km × 18 km

    @Test
    fun `zoom zero is one tile and the world doubles per side at each zoom`() {
        assertEquals(1, TileMath.tileCount(world, 0))
        assertEquals(4, TileMath.tileCount(world, 1))
        assertEquals(1L shl 20, TileMath.tileCount(world, 10))
        assertEquals(1 + 4 + 16, TileMath.tileCount(world, 0, 2))
        assertEquals(1024, TileMath.tilesPerSide(10))
    }

    @Test
    fun `well known tiles land where OSM puts them`() {
        // Greenwich/equator sits exactly on the tile boundary: columns 2^(z-1) at every zoom.
        assertEquals(512, TileMath.tileX(0.0, 10))
        assertEquals(512, TileMath.tileY(0.0, 10))
        // Buenos Aires (-34.6, -58.4) at zoom 12 → x=1383, y=2468 (OSM slippy-map formula, checked by hand).
        assertEquals(1383, TileMath.tileX(-58.4, 12))
        assertEquals(2468, TileMath.tileY(-34.6, 12))
        val (lat, lon) = TileMath.tileOrigin(1383, 2468, 12)
        assertTrue(lon <= -58.4 && lon > -58.5 && lat >= -34.6 && lat < -34.5, "origin north-west of the point: $lat,$lon")
        // Poles are clamped, never an index out of range.
        assertEquals(0, TileMath.tileY(89.9, 5))
        assertEquals(31, TileMath.tileY(-89.9, 5))
    }

    @Test
    fun `a small region grows with zoom and the cap picks the deepest zoom that fits`() {
        val counts = (8..14).map { TileMath.tileCount(calafate, it) }
        assertTrue(counts.zipWithNext().all { (a, b) -> b >= a }, "monotonic: $counts")
        assertEquals(1, TileMath.tileCount(calafate, 8))
        val total = TileMath.tileCount(calafate, 8, 14)
        assertTrue(total in 50..400, "a town at z8–14 is a few hundred tiles: $total")
        assertEquals(14, TileMath.maxZoomWithin(calafate, 8, 14, 6_000))
        assertEquals(12, TileMath.maxZoomWithin(calafate, 8, 14, TileMath.tileCount(calafate, 8, 12)))
        assertNull(TileMath.maxZoomWithin(world, 8, 14, 6_000), "the world never fits")
        assertTrue(runCatching { TileMath.tileCount(BoundingBox(10.0, 179.0, 11.0, -179.0), 5) }.isFailure, "a box across the antimeridian is refused, never a negative count")
        assertEquals(20_000_000L + 20_000L * 100, TileMath.estimateBytes(100))
        assertEquals(1_000L + 7L * 3, TileMath.estimateBytes(7, bytesPerTile = 3, fixedBytes = 1_000))
    }

    @Test
    fun `padding grows the box on every side and clamps at the edges`() {
        val p = TileMath.pad(calafate, 5.0)
        assertTrue(p.minLat < calafate.minLat && p.maxLat > calafate.maxLat && p.minLon < calafate.minLon && p.maxLon > calafate.maxLon)
        assertEquals(10.0 / 111.32, p.maxLat - p.minLat - (calafate.maxLat - calafate.minLat), 1e-9)
        val edge = TileMath.pad(BoundingBox(84.9, 179.9, 85.0, 180.0), 50.0)
        assertEquals(TileMath.MAX_LAT, edge.maxLat)
        assertEquals(180.0, edge.maxLon)
        val point = TileMath.pad(BoundingBox(-34.6, -58.4, -34.6, -58.4), 2.0)
        assertTrue(point.maxLat > point.minLat && point.maxLon > point.minLon, "a single point becomes a box")
    }
}
