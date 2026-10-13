package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A named line from the map (an OSM way or hiking relation) with its geometry. */
data class TrailCandidate(val name: String, val geometry: List<Pair<Double, Double>>)

/** One reverse-geocode sample along a track (DISENO §6.8): road and locality, if any. */
data class PlaceSample(val road: String?, val locality: String?)

/**
 * DISENO §6.8: a trek gets the name of the trail that covers most of it. Coverage is the
 * share of track points lying within [maxDistM] of the candidate's geometry (point to
 * polyline distance on a local equirectangular projection, enough at trail scale).
 */
object TrailMatch {
    const val EARTH_M_PER_DEG_LAT = 111_320.0

    fun coverage(track: List<TrackPoint>, trail: List<Pair<Double, Double>>, maxDistM: Double = 30.0): Double {
        if (track.isEmpty() || trail.size < 2) return 0.0
        val lat0 = track[0].lat
        val kx = EARTH_M_PER_DEG_LAT * cos(Math.toRadians(lat0))
        val ky = EARTH_M_PER_DEG_LAT
        val segs = trail.zipWithNext()
        var hits = 0
        for (p in track) {
            val px = p.lon * kx
            val py = p.lat * ky
            var best = Double.MAX_VALUE
            for ((a, b) in segs) {
                val ax = a.second * kx
                val ay = a.first * ky
                val bx = b.second * kx
                val by = b.first * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
                val cx = ax + t * dx
                val cy = ay + t * dy
                val d = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
                if (d < best) best = d
                if (best <= maxDistM) break
            }
            if (best <= maxDistM) hits++
        }
        return hits.toDouble() / track.size
    }

    /** The candidate covering at least [minCoverage] of the track, best first; null when none qualifies. */
    fun bestTrail(track: List<TrackPoint>, candidates: List<TrailCandidate>, maxDistM: Double = 30.0, minCoverage: Double = 0.6): Pair<TrailCandidate, Double>? {
        val byName = candidates.groupBy { it.name }
        // Ways of the same named trail are usually split: merge their coverage by name.
        val scored = byName.map { (name, parts) ->
            val merged = parts.flatMap { it.geometry + listOf(Double.NaN to Double.NaN) } // NaN breaks the polyline between parts
            TrailCandidate(name, merged) to coverageMulti(track, parts.map { it.geometry }, maxDistM)
        }
        return scored.filter { it.second >= minCoverage }.maxByOrNull { it.second }
    }

    private fun coverageMulti(track: List<TrackPoint>, parts: List<List<Pair<Double, Double>>>, maxDistM: Double): Double {
        if (track.isEmpty()) return 0.0
        var hits = 0
        for (p in track) {
            if (parts.any { coverage(listOf(p), it, maxDistM) > 0.0 }) hits++
        }
        return hits.toDouble() / track.size
    }
}

/** DISENO §6.8: "Ruta 40, de El Calafate a El Chaltén" from reverse-geocoded samples along the track. */
object RouteNaming {
    fun routeName(samples: List<PlaceSample>): String? {
        val roads = samples.mapNotNull { it.road?.trim()?.takeIf { r -> r.isNotEmpty() } }
        val localities = samples.mapNotNull { it.locality?.trim()?.takeIf { l -> l.isNotEmpty() } }
        val mainRoad = roads.groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 || roads.size == 1 }?.key
        val distinct = localities.fold(ArrayList<String>()) { acc, l -> if (acc.lastOrNull() != l) acc.add(l); acc }
        val from = distinct.firstOrNull()
        val to = distinct.lastOrNull()?.takeIf { it != from }
        val where = when {
            from != null && to != null -> "de $from a $to"
            from != null -> "por $from"
            else -> null
        }
        return when {
            mainRoad != null && where != null -> "$mainRoad, $where"
            mainRoad != null -> mainRoad
            where != null -> where.replaceFirstChar { it.uppercase() }
            else -> null
        }
    }
}
