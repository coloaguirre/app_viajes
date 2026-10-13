package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint

/**
 * Where on a recorded track a tap or an instant falls (backlog D2, interactive map).
 *
 * Distances are cumulative haversine legs from the first point of the segment, skipping legs that
 * overlap a pause (the same rule as [computeSegmentStats], without the still-noise filter, so the
 * figure can differ from the segment total by a few metres). Pure Kotlin, no Android.
 */
object TrackLocator {
    /**
     * @property index index of the nearest recorded point (the earlier one when interpolating).
     * @property distanceM metres covered since the first point of the segment.
     * @property speedMps average speed over the ±[SPEED_WINDOW_MS] around the point, or the point's own fix speed.
     * @property offsetM distance from the queried position to the track (0 when the query was a time).
     */
    data class Position(
        val index: Int,
        val timeUtcMs: Long,
        val lat: Double,
        val lon: Double,
        val altM: Double?,
        val distanceM: Double,
        val speedMps: Double?,
        val offsetM: Double,
    )

    const val SPEED_WINDOW_MS = 30_000L

    /** Cumulative distance at each point; `[0]` is 0.0. Pause legs add nothing. */
    fun cumulative(points: List<TrackPoint>, pauses: List<TimeInterval> = emptyList()): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val leg = if (pauses.covers(a.timeUtcMs, b.timeUtcMs)) 0.0 else haversineM(a.lat, a.lon, b.lat, b.lon)
            out[i] = out[i - 1] + leg
        }
        return out
    }

    /** The recorded point closest to ([lat], [lon]); null for an empty track. */
    fun nearest(points: List<TrackPoint>, lat: Double, lon: Double, pauses: List<TimeInterval> = emptyList()): Position? {
        if (points.isEmpty()) return null
        var best = 0
        var bestD = Double.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = haversineM(lat, lon, p.lat, p.lon)
            if (d < bestD) { bestD = d; best = i }
        }
        val cum = cumulative(points, pauses)
        val p = points[best]
        return Position(best, p.timeUtcMs, p.lat, p.lon, p.altMslM ?: p.altM, cum[best], speedAround(points, best), bestD)
    }

    /**
     * The track position at [timeUtcMs]: linear interpolation between the surrounding fixes, clamped to
     * the first/last point outside the recording. Points must be in time order.
     */
    fun atTime(points: List<TrackPoint>, timeUtcMs: Long, pauses: List<TimeInterval> = emptyList()): Position? {
        if (points.isEmpty()) return null
        val cum = cumulative(points, pauses)
        if (timeUtcMs <= points.first().timeUtcMs) return points.first().let { Position(0, it.timeUtcMs, it.lat, it.lon, it.altMslM ?: it.altM, 0.0, speedAround(points, 0), 0.0) }
        if (timeUtcMs >= points.last().timeUtcMs) {
            val i = points.lastIndex
            return points[i].let { Position(i, it.timeUtcMs, it.lat, it.lon, it.altMslM ?: it.altM, cum[i], speedAround(points, i), 0.0) }
        }
        // First index with time >= t (binary search), so prev < t <= next.
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timeUtcMs < timeUtcMs) lo = mid + 1 else hi = mid
        }
        val next = points[lo]
        val prev = points[lo - 1]
        val span = (next.timeUtcMs - prev.timeUtcMs).toDouble()
        val f = if (span <= 0) 0.0 else (timeUtcMs - prev.timeUtcMs) / span
        val altA = prev.altMslM ?: prev.altM
        val altB = next.altMslM ?: next.altM
        return Position(
            index = lo - 1,
            timeUtcMs = timeUtcMs,
            lat = prev.lat + (next.lat - prev.lat) * f,
            lon = prev.lon + (next.lon - prev.lon) * f,
            altM = if (altA != null && altB != null) altA + (altB - altA) * f else altA ?: altB,
            distanceM = cum[lo - 1] + (cum[lo] - cum[lo - 1]) * f,
            speedMps = speedAround(points, lo - 1),
            offsetM = 0.0,
        )
    }

    /**
     * Average speed over the fixes within ±[SPEED_WINDOW_MS] of point [i] (distance / elapsed), which
     * smooths GPS jitter; falls back to the fix's own speed when the window has no elapsed time.
     */
    fun speedAround(points: List<TrackPoint>, i: Int): Double? {
        if (i !in points.indices) return null
        val t = points[i].timeUtcMs
        var lo = i
        while (lo > 0 && t - points[lo - 1].timeUtcMs <= SPEED_WINDOW_MS) lo--
        var hi = i
        while (hi < points.lastIndex && points[hi + 1].timeUtcMs - t <= SPEED_WINDOW_MS) hi++
        val dt = points[hi].timeUtcMs - points[lo].timeUtcMs
        if (dt <= 0) return points[i].speedMps?.toDouble()
        var d = 0.0
        for (k in lo until hi) d += haversineM(points[k].lat, points[k].lon, points[k + 1].lat, points[k + 1].lon)
        return d / (dt / 1000.0)
    }
}
