package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.TrackPoint

/** Splits a track at every pause (user, rest, nested excursion or privacy cut): one piece per `<trkseg>` / LineString. */
object TrackSplitter {
    fun split(points: List<TrackPoint>, pauses: List<SegmentPause>): List<List<TrackPoint>> {
        if (points.isEmpty()) return emptyList()
        val cuts = pauses.map { it.pausedUtcMs }.sorted()
        val out = ArrayList<List<TrackPoint>>()
        var current = ArrayList<TrackPoint>()
        var cutIdx = 0
        for (p in points) {
            var cutHere = false
            while (cutIdx < cuts.size && cuts[cutIdx] <= p.timeUtcMs) { cutHere = true; cutIdx++ }
            if (cutHere && current.isNotEmpty()) { out += current; current = ArrayList() }
            current += p
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}
