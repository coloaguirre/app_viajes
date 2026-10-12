package com.bitacoraviaje.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Earth mean radius in metres (sphere approximation of WGS84). */
const val EARTH_RADIUS_M: Double = 6_371_008.8

/** Great-circle distance in metres between two WGS84 coordinates. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/** Simple moving average with a centred window (edges shrink the window). */
internal fun movingAverage(values: List<Double>, window: Int): List<Double> {
    if (values.isEmpty() || window <= 1) return values
    val half = window / 2
    return values.indices.map { i ->
        val from = maxOf(0, i - half)
        val to = minOf(values.lastIndex, i + half)
        var sum = 0.0
        for (j in from..to) sum += values[j]
        sum / (to - from + 1)
    }
}

/** Nearest-rank percentile of a non-empty list; [p] in 0..100. */
internal fun percentile(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty()) { "percentile of empty list" }
    val sorted = values.sorted()
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
