package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.BoundingBox
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator tile arithmetic (PLAN 5.4, offline maps): how many tiles a region needs per zoom,
 * so the app can tell the user the size before downloading and stay under the SDK's tile limit.
 * Pure Kotlin, mirrored nowhere else.
 */
object TileMath {
    const val MAX_LAT = 85.0511

    /** Tile column of [lon] at [zoom]. */
    fun tileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** Tile row of [lat] at [zoom] (clamped to the Mercator limit). */
    fun tileY(lat: Double, zoom: Int): Int {
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return floor((1.0 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)
    }

    /** North-west corner of tile (x, y) at [zoom]: (lat, lon). */
    fun tileOrigin(x: Int, y: Int, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val lon = x / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        return lat to lon
    }

    /** Tiles covering [bounds] at one zoom level (inclusive on both edges). A box across the antimeridian is not supported. */
    fun tileCount(bounds: BoundingBox, zoom: Int): Long {
        require(bounds.minLon <= bounds.maxLon && bounds.minLat <= bounds.maxLat) { "bounds must be ordered (no antimeridian crossing): $bounds" }
        val x0 = tileX(bounds.minLon, zoom)
        val x1 = tileX(bounds.maxLon, zoom)
        val y0 = tileY(bounds.maxLat, zoom)
        val y1 = tileY(bounds.minLat, zoom)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
    }

    /** Tiles covering [bounds] from [minZoom] to [maxZoom] inclusive. */
    fun tileCount(bounds: BoundingBox, minZoom: Int, maxZoom: Int): Long = (minZoom..maxZoom).sumOf { tileCount(bounds, it) }

    /** Grows [bounds] by [paddingKm] on every side (clamped to the Mercator limits); a point-like box becomes a square. */
    fun pad(bounds: BoundingBox, paddingKm: Double): BoundingBox {
        val dLat = paddingKm / 111.32
        val midLat = (bounds.minLat + bounds.maxLat) / 2
        val dLon = paddingKm / (111.32 * max(0.01, cos(Math.toRadians(midLat))))
        return BoundingBox(
            minLat = max(-MAX_LAT, bounds.minLat - dLat),
            minLon = max(-180.0, bounds.minLon - dLon),
            maxLat = min(MAX_LAT, bounds.maxLat + dLat),
            maxLon = min(180.0, bounds.maxLon + dLon),
        )
    }

    /** Highest zoom (from [maxZoom] down) at which the region stays within [tileLimit] tiles; null if even [minZoom] alone does not fit. */
    fun maxZoomWithin(bounds: BoundingBox, minZoom: Int, maxZoom: Int, tileLimit: Long): Int? =
        (maxZoom downTo minZoom).firstOrNull { tileCount(bounds, minZoom, it) <= tileLimit }

    /**
     * Rough download size: a fixed share for the style, sprites and glyphs every region fetches
     * (measured ~20 MB on MapTiler outdoor) plus ~20 KB per vector tile (measured on z8–12 regions).
     */
    fun estimateBytes(tiles: Long, bytesPerTile: Long = 20_000L, fixedBytes: Long = 20_000_000L): Long = fixedBytes + tiles * bytesPerTile

    /** Zoom 0 has one tile: 2^zoom tiles per side. */
    fun tilesPerSide(zoom: Int): Int = 2.0.pow(zoom).toInt()
}
