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
 * roads and localities ("RN 40, de El Calafate a El Chaltén"). Coordinates sent out are
 * the sampled points only, rounded to ~10 m.
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
        val points = tracking.validPoints(segmentId)
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

        /** Equally spaced samples including both ends. */
        fun sample(points: List<TrackPoint>, n: Int): List<TrackPoint> {
            if (points.size <= n) return points
            return (0 until n).map { i -> points[(i * (points.size - 1)) / (n - 1)] }
        }
    }
}
