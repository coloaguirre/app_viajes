package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint

/** A detected stay point before it gets an id and a place (core-only shape). */
data class StayPoint(val startUtcMs: Long, val endUtcMs: Long, val lat: Double, val lon: Double, val radiusM: Double, val pointCount: Int)

/**
 * Stay-point detection (DISENO §6.6) robust to GPS gaps: a long time gap whose
 * endpoints are within [radiusM] counts as staying (screen off indoors, no signal).
 *
 * Classic sweep: from i, extend j while every point stays within [radiusM] of p[i];
 * if the dwell reaches [minDurationMs], emit the centroid of p[i..j-1] and continue
 * from j; otherwise i++. Contiguous stays closer than [radiusM] and separated by less
 * than [mergeGapMs] are merged.
 */
fun detectStops(
    points: List<TrackPoint>,
    radiusM: Double = 50.0,
    minDurationMs: Long = 5 * 60_000,
    mergeGapMs: Long = 2 * 60_000,
): List<StayPoint> {
    if (points.size < 2) return emptyList()
    val raw = ArrayList<StayPoint>()
    var i = 0
    while (i < points.size) {
        val pi = points[i]
        var j = i + 1
        while (j < points.size && haversineM(pi.lat, pi.lon, points[j].lat, points[j].lon) <= radiusM) j++
        val last = points[j - 1]
        if (last.timeUtcMs - pi.timeUtcMs >= minDurationMs) {
            raw += centroid(points.subList(i, j))
            i = j
        } else {
            i++
        }
    }
    return merge(raw, radiusM, mergeGapMs)
}

private fun centroid(cluster: List<TrackPoint>): StayPoint {
    val lat = cluster.sumOf { it.lat } / cluster.size
    val lon = cluster.sumOf { it.lon } / cluster.size
    val radius = cluster.maxOf { haversineM(lat, lon, it.lat, it.lon) }
    return StayPoint(cluster.first().timeUtcMs, cluster.last().timeUtcMs, lat, lon, radius, cluster.size)
}

private fun merge(stays: List<StayPoint>, radiusM: Double, gapMs: Long): List<StayPoint> {
    if (stays.size < 2) return stays
    val out = ArrayList<StayPoint>()
    var cur = stays[0]
    for (next in stays.drop(1)) {
        val close = haversineM(cur.lat, cur.lon, next.lat, next.lon) <= radiusM
        if (close && next.startUtcMs - cur.endUtcMs <= gapMs) {
            val n = cur.pointCount + next.pointCount
            val lat = (cur.lat * cur.pointCount + next.lat * next.pointCount) / n
            val lon = (cur.lon * cur.pointCount + next.lon * next.pointCount) / n
            cur = StayPoint(cur.startUtcMs, next.endUtcMs, lat, lon, maxOf(cur.radiusM, next.radiusM, haversineM(lat, lon, next.lat, next.lon)), n)
        } else {
            out += cur
            cur = next
        }
    }
    out += cur
    return out
}
