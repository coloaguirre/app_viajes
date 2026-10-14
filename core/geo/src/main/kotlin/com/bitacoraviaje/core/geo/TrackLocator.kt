package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport

/**
 * Where on a recorded track a tap or an instant falls (backlog D2, interactive map).
 *
 * Progress along the stage comes from [cumulativeDistanceM], the same rule the segment statistics use
 * (pause legs, non-increasing time and still noise add nothing), so the card and the stage header agree.
 * The caller precomputes that array once per segment and passes it as [cum]; without it, it is computed
 * on the spot. Pure Kotlin, no Android.
 */
object TrackLocator {
    /**
     * @property index index of the recorded point at or before the position (the leg's start when interpolating).
     * @property distanceM metres covered since the first point of the segment.
     * @property speedMps average speed over the fixes within ±[SPEED_WINDOW_MS], skipping pause legs; the fix's own speed when the window is empty.
     * @property offsetM distance from the queried position to the track line (0 when the query was a time).
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

    /**
     * The point of the track line closest to ([lat], [lon]): the foot of the perpendicular on the nearest
     * leg (local equirectangular frame, fine for legs of a few hundred metres), with time, altitude and
     * progress interpolated along that leg; [Position.offsetM] is the distance from the query to that point.
     * Null for an empty track; a single point is its own answer.
     */
    fun nearest(
        points: List<TrackPoint>,
        lat: Double,
        lon: Double,
        pauses: List<TimeInterval> = emptyList(),
        transport: Transport? = null,
        cum: DoubleArray? = null,
    ): Position? {
        if (points.isEmpty()) return null
        val c = cum ?: cumulativeDistanceM(points, pauses, transport)
        if (points.size == 1) return points[0].let { Position(0, it.timeUtcMs, it.lat, it.lon, it.altMslM ?: it.altM, 0.0, speedAround(points, 0, pauses), haversineM(lat, lon, it.lat, it.lon)) }
        val kx = Math.toRadians(1.0) * EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))
        val ky = Math.toRadians(1.0) * EARTH_RADIUS_M
        var bestLeg = 0
        var bestF = 0.0
        var bestD2 = Double.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - lon) * kx
            val ay = (a.lat - lat) * ky
            val bx = (b.lon - lon) * kx
            val by = (b.lat - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val f = if (len2 <= 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * f
            val py = ay + dy * f
            val d2 = px * px + py * py
            if (d2 < bestD2) { bestD2 = d2; bestLeg = i; bestF = f }
        }
        val a = points[bestLeg]
        val b = points[bestLeg + 1]
        val altA = a.altMslM ?: a.altM
        val altB = b.altMslM ?: b.altM
        val f = bestF
        return Position(
            index = bestLeg,
            timeUtcMs = a.timeUtcMs + ((b.timeUtcMs - a.timeUtcMs) * f).toLong(),
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            altM = if (altA != null && altB != null) altA + (altB - altA) * f else altA ?: altB,
            distanceM = c[bestLeg] + (c[bestLeg + 1] - c[bestLeg]) * f,
            speedMps = speedAround(points, bestLeg, pauses),
            offsetM = Math.sqrt(bestD2),
        )
    }

    /**
     * The track position at [timeUtcMs]: linear interpolation between the surrounding fixes (O(log n)),
     * clamped to the first/last point outside the recording. Points must be in time order.
     */
    fun atTime(
        points: List<TrackPoint>,
        timeUtcMs: Long,
        pauses: List<TimeInterval> = emptyList(),
        transport: Transport? = null,
        cum: DoubleArray? = null,
    ): Position? {
        if (points.isEmpty()) return null
        val c = cum ?: cumulativeDistanceM(points, pauses, transport)
        if (timeUtcMs <= points.first().timeUtcMs) return points.first().let { Position(0, it.timeUtcMs, it.lat, it.lon, it.altMslM ?: it.altM, 0.0, speedAround(points, 0, pauses), 0.0) }
        if (timeUtcMs >= points.last().timeUtcMs) {
            val i = points.lastIndex
            return points[i].let { Position(i, it.timeUtcMs, it.lat, it.lon, it.altMslM ?: it.altM, c[i], speedAround(points, i, pauses), 0.0) }
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
            distanceM = c[lo - 1] + (c[lo] - c[lo - 1]) * f,
            speedMps = speedAround(points, lo - 1, pauses),
            offsetM = 0.0,
        )
    }

    /**
     * Average speed over the legs within ±[SPEED_WINDOW_MS] of point [i] (distance / elapsed), skipping
     * legs that overlap a pause; smooths GPS jitter. Falls back to the fix's own speed when no leg counts.
     */
    fun speedAround(points: List<TrackPoint>, i: Int, pauses: List<TimeInterval> = emptyList()): Double? {
        if (i !in points.indices) return null
        val t = points[i].timeUtcMs
        var lo = i
        while (lo > 0 && t - points[lo - 1].timeUtcMs <= SPEED_WINDOW_MS) lo--
        var hi = i
        while (hi < points.lastIndex && points[hi + 1].timeUtcMs - t <= SPEED_WINDOW_MS) hi++
        var d = 0.0
        var dt = 0L
        for (k in lo until hi) {
            val a = points[k]
            val b = points[k + 1]
            val leg = b.timeUtcMs - a.timeUtcMs
            if (leg <= 0 || pauses.covers(a.timeUtcMs, b.timeUtcMs)) continue
            d += haversineM(a.lat, a.lon, b.lat, b.lon)
            dt += leg
        }
        if (dt <= 0) return points[i].speedMps?.toDouble()
        return d / (dt / 1000.0)
    }
}
