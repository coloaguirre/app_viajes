package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.BoundingBox
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport

/** Interval that must not accumulate distance or moving time (pauses, rests). */
data class TimeInterval(val startUtcMs: Long, val endUtcMs: Long)

fun List<SegmentPause>.toIntervals(nowUtcMs: Long): List<TimeInterval> =
    map { TimeInterval(it.pausedUtcMs, it.resumedUtcMs ?: nowUtcMs) }

private fun List<TimeInterval>.covers(t0: Long, t1: Long): Boolean = any { t0 < it.endUtcMs && t1 > it.startUtcMs }

/**
 * Elevation gain/loss with a 5-point moving average and hysteresis: changes smaller
 * than [thresholdM] are noise and do not accumulate (DISENO §6.5).
 */
fun elevationGainLoss(altitudesM: List<Double>, thresholdM: Double = 3.0, window: Int = 5): Pair<Double, Double> {
    val smooth = movingAverage(altitudesM, window)
    var ref = smooth.firstOrNull() ?: return 0.0 to 0.0
    var gain = 0.0
    var loss = 0.0
    for (a in smooth.drop(1)) {
        val d = a - ref
        if (d >= thresholdM) {
            gain += d
            ref = a
        } else if (d <= -thresholdM) {
            loss += -d
            ref = a
        }
    }
    return gain to loss
}

fun boundingBox(points: List<TrackPoint>): BoundingBox? {
    if (points.isEmpty()) return null
    var minLat = points[0].lat
    var maxLat = minLat
    var minLon = points[0].lon
    var maxLon = minLon
    for (p in points) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
    }
    return BoundingBox(minLat, minLon, maxLat, maxLon)
}

/**
 * Segment statistics from the ACCEPTED points in time order. Distance and moving
 * time skip legs that overlap a pause; legs longer than [maxLegMs] are gaps and
 * never count as moving time (DISENO §6.5, skill geo-algorithms).
 *
 * Altitude source: [TrackPoint.altMslM] when present (barometer/MSL), else [TrackPoint.altM].
 */
fun computeSegmentStats(
    points: List<TrackPoint>,
    pauses: List<TimeInterval> = emptyList(),
    transport: Transport? = null,
    totalTimeMs: Long? = null,
    maxLegMs: Long = 120_000,
    stillRadiusM: Double = 15.0,
): SegmentStats {
    if (points.isEmpty()) return SegmentStats(totalTimeMs = totalTimeMs)
    val movingThreshold = if (transport == null || transport == Transport.WALK) 0.5 else 1.5
    var distance = 0.0
    var movingTime = 0L
    val legSpeeds = ArrayList<Double>(points.size)
    var anchor = points[0]
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val dt = b.timeUtcMs - a.timeUtcMs
        if (dt <= 0) continue
        val d = haversineM(a.lat, a.lon, b.lat, b.lon)
        val speed = d / (dt / 1000.0)
        if (pauses.covers(a.timeUtcMs, b.timeUtcMs)) {
            anchor = b
            continue
        }
        // Still-noise rule: movement inside the still radius at still speed adds nothing.
        val still = speed < movingThreshold && haversineM(anchor.lat, anchor.lon, b.lat, b.lon) <= stillRadiusM
        if (!still) {
            distance += d
            anchor = b
        }
        if (dt < maxLegMs) {
            legSpeeds += speed
            if (speed >= movingThreshold) movingTime += dt
        }
    }
    val alts = points.mapNotNull { it.altMslM ?: it.altM }
    val (gain, loss) = elevationGainLoss(alts)
    val maxSpeed = if (legSpeeds.isEmpty()) null else percentile(legSpeeds, 98.0)
    return SegmentStats(
        distanceM = distance,
        movingTimeMs = movingTime,
        totalTimeMs = totalTimeMs ?: (points.last().timeUtcMs - points.first().timeUtcMs),
        maxSpeedMps = maxSpeed,
        avgMovingSpeedMps = if (movingTime > 0) distance / (movingTime / 1000.0) else null,
        elevationGainM = if (alts.isEmpty()) null else gain,
        elevationLossM = if (alts.isEmpty()) null else loss,
        minAltM = alts.minOrNull(),
        maxAltM = alts.maxOrNull(),
        bbox = boundingBox(points),
    )
}
