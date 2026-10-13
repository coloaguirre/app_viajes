package com.bitacoraviaje.app.enrichment

import android.util.Log
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.geo.RouteNaming
import com.bitacoraviaje.core.geo.TrailMatch
import com.bitacoraviaje.core.geo.simplifyIndices
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.data.repository.TrackingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * DISENO §6.8: name a finished segment from the map. Treks ask Overpass for the trail
 * covering ≥ 60 % of the track; everything else samples the track and reverse-geocodes
 * roads and localities ("RN 40, de El Calafate a El Chaltén").
 *
 * What leaves the phone (review I6): for treks, the simplified track rounded to ~10 m
 * (Overpass needs the shape); otherwise 8 samples rounded to ~10 m. The first and last
 * [TRIM_M] metres are never sent: that is where home usually is. Rounding to ~1 km and
 * privacy zones are Fase 6 (DISENO §15).
 */
class RouteNamer @Inject constructor(
    private val tracking: TrackingRepository,
    private val nominatim: NominatimClient,
    private val overpass: OverpassClient,
    private val prefs: UserPreferences,
) {
    data class Outcome(val name: String?, val source: String)

    suspend fun name(segmentId: String): Outcome? {
        val segment = tracking.getSegment(segmentId) ?: return null
        if (segment.title != null) return Outcome(segment.title, "título del usuario") // nothing to ask (review M12)
        val all = tracking.validPoints(segmentId)
        val points = trimEnds(all, TRIM_M)
        if (points.size < MIN_POINTS) return Outcome(null, "sin puntos")
        val settings = prefs.settings.first()
        if (segment.type == SegmentType.TREKKING) {
            val simplified = simplifyIndices(points, 30.0).map { points[it] }
            val candidates = overpass.trailsNear(settings.overpassUrl, simplified)
            val best = TrailMatch.bestTrail(points, candidates)
            if (best != null) {
                Log.i(TAG, "trail matched segment=$segmentId name=${best.first.name} coverage=${"%.0f".format(best.second * 100)}%")
                tracking.setAutoName(segmentId, best.first.name)
                return Outcome(best.first.name, "overpass")
            }
            Log.i(TAG, "no trail covers 60% of segment=$segmentId (candidates=${candidates.size}); falling back to places")
        }
        // zoom 16 is where Nominatim names the road; localities come in the same address block.
        val samples = sample(points, SAMPLES).map { p -> nominatim.reverse(settings.nominatimUrl, p.lat, p.lon, zoom = ZOOM_ROAD).toSample() }
        var name = RouteNaming.routeName(samples)
        if (name == null) {
            // Nothing at street/locality level: try the region (zoom 10) for the first and last point.
            val coarse = listOf(points.first(), points.last()).map { p -> nominatim.reverse(settings.nominatimUrl, p.lat, p.lon, zoom = ZOOM_REGION).toSample() }
            name = RouteNaming.routeName(coarse)
        }
        tracking.setAutoName(segmentId, name)
        Log.i(TAG, "route named segment=$segmentId name=$name")
        return Outcome(name, "nominatim")
    }

    companion object {
        private const val TAG = "Bitacora"
        const val MIN_POINTS = 5
        const val SAMPLES = 8
        const val ZOOM_ROAD = 16
        const val ZOOM_REGION = 10
        const val TRIM_M = 200.0

        /** Drops the first and last [metres] of the track (home, hotel) before anything is sent out. */
        fun trimEnds(points: List<TrackPoint>, metres: Double): List<TrackPoint> {
            if (points.size < 3) return points
            var start = 0
            var acc = 0.0
            while (start < points.size - 1 && acc < metres) {
                acc += com.bitacoraviaje.core.geo.haversineM(points[start].lat, points[start].lon, points[start + 1].lat, points[start + 1].lon)
                start++
            }
            var end = points.size - 1
            acc = 0.0
            while (end > start && acc < metres) {
                acc += com.bitacoraviaje.core.geo.haversineM(points[end].lat, points[end].lon, points[end - 1].lat, points[end - 1].lon)
                end--
            }
            val trimmed = points.subList(start, end + 1)
            return if (trimmed.size >= MIN_POINTS) trimmed else points
        }

        /** Equally spaced samples including both ends. */
        fun sample(points: List<TrackPoint>, n: Int): List<TrackPoint> {
            if (points.size <= n) return points
            return (0 until n).map { i -> points[(i * (points.size - 1)) / (n - 1)] }
        }
    }
}
