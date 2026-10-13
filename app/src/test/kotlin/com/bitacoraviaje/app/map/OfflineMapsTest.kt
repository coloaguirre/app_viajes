package com.bitacoraviaje.app.map

import com.bitacoraviaje.core.model.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN 5.4: the pure parts of the offline maps (metadata codec, region bounds and size estimate). */
class OfflineMapsTest {
    @Test
    fun `metadata survives a round trip and garbage decodes to null`() {
        val m = OfflineRegionMeta("Patagonia 2026", 1_760_000_000_000L, "trip-1")
        assertEquals(m, OfflineRegionMeta.decode(OfflineRegionMeta.encode(m)))
        assertEquals(OfflineRegionMeta("x", 1), OfflineRegionMeta.decode("""{"name":"x","created_utc_ms":1,"future":true}""".toByteArray()), "unknown keys from a newer app are ignored")
        assertTrue(String(OfflineRegionMeta.encode(m)).contains("\"trip_id\""), "the stored keys stay snake_case for regions already on phones")
        assertNull(OfflineRegionMeta.decode("not json".toByteArray()))
        assertNull(OfflineRegionMeta.decode(null))
    }

    @Test
    fun `a trip region is the padded track box and the estimate grows with the detail level`() {
        val track = BoundingBox(-50.34, -72.30, -50.32, -72.26)
        val region = OfflineMaps.regionBounds(track)
        assertTrue(region.minLat < track.minLat && region.maxLon > track.maxLon)
        val (t12, b12) = OfflineMaps.estimate(region, 12)
        val (t14, b14) = OfflineMaps.estimate(region, 14)
        assertTrue(t12 in 1..OfflineMaps.TILE_LIMIT && t14 > t12 && b14 > b12, "z12=$t12 z14=$t14")
        assertTrue(t14 <= OfflineMaps.TILE_LIMIT, "a town-sized trip fits the SDK limit at z14: $t14")
    }

    @Test
    fun `a plan merges nearby stages into one region and keeps far-apart stages separate`() {
        val ba1 = BoundingBox(-34.61, -58.42, -34.60, -58.38)
        val ba2 = BoundingBox(-34.60, -58.40, -34.58, -58.37)
        val calafate = BoundingBox(-50.34, -72.30, -50.32, -72.26)
        assertEquals(1, OfflineMaps.plan(listOf(ba1, ba2), 14)!!.size, "two stages in the same town share a region")
        val plan = OfflineMaps.plan(listOf(ba1, calafate, ba2), 14)!!
        assertEquals(2, plan.size, "Buenos Aires and Patagonia cannot share one box: $plan")
        assertTrue(plan.any { it.minLat < -50 } && plan.any { it.maxLat > -35 })
        assertTrue(plan.all { com.bitacoraviaje.core.geo.TileMath.tileCount(it, OfflineMaps.MIN_ZOOM, 14) <= OfflineMaps.TILE_LIMIT })
        val flight = BoundingBox(-50.3, -72.3, -34.6, -58.4)
        assertNull(OfflineMaps.plan(listOf(ba1, flight), 12), "a single stage spanning the country never fits")
        assertEquals(emptyList(), OfflineMaps.plan(emptyList(), 12))
        val (tiles, bytes) = OfflineMaps.estimate(plan, 14)
        assertTrue(tiles > 0 && bytes == 20_000_000L + tiles * 20_000L, "style assets once plus the tiles: $bytes")
        // Three far-apart stages → three groups; a later stage joins the first earlier group whose union fits (order independent of position).
        val cordoba = BoundingBox(-31.43, -64.20, -31.40, -64.17)
        val three = OfflineMaps.plan(listOf(ba1, calafate, cordoba), 14)!!
        assertEquals(3, three.size)
        val joined = OfflineMaps.plan(listOf(ba1, calafate, ba2, cordoba), 14)!!
        assertEquals(3, joined.size, "ba2 joins the Buenos Aires group, not the last one")
        assertEquals("ok", runCatching { OfflineMaps.sanitize("HTTP 403 for key abc") }.map { "ok" }.getOrDefault("fail"))
    }
}
