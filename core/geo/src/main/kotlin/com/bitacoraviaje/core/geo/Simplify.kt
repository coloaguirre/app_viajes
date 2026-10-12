package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Iterative Douglas-Peucker (no recursion: segments can have 100k points). Returns
 * the indices to keep, ascending; first and last are always kept. Perpendicular
 * distance uses a local equirectangular projection, enough for short legs.
 * Tolerances per DISENO §6.7: 5 m detail, 30 m overview/video.
 */
fun simplifyIndices(points: List<TrackPoint>, toleranceM: Double): List<Int> {
    if (points.size <= 2) return points.indices.toList()
    val lat0 = Math.toRadians(points[points.size / 2].lat)
    val kx = EARTH_RADIUS_M * cos(lat0)
    val ky = EARTH_RADIUS_M
    val xs = DoubleArray(points.size) { Math.toRadians(points[it].lon) * kx }
    val ys = DoubleArray(points.size) { Math.toRadians(points[it].lat) * ky }
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, points.lastIndex))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        var maxD = -1.0
        var maxI = -1
        val ax = xs[a]
        val ay = ys[a]
        val dx = xs[b] - ax
        val dy = ys[b] - ay
        val len = hypot(dx, dy)
        for (i in a + 1 until b) {
            val d = if (len == 0.0) hypot(xs[i] - ax, ys[i] - ay) else abs(dy * xs[i] - dx * ys[i] + xs[b] * ay - ys[b] * ax) / len
            if (d > maxD) {
                maxD = d
                maxI = i
            }
        }
        if (maxD > toleranceM) {
            keep[maxI] = true
            stack.addLast(intArrayOf(a, maxI))
            stack.addLast(intArrayOf(maxI, b))
        }
    }
    return keep.indices.filter { keep[it] }
}
