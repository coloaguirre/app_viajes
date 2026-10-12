package com.bitacoraviaje.core.geo

import kotlin.math.abs

/**
 * Wall-clock resilience (DISENO §16.2): fixes carry a monotonic timestamp (ms
 * since boot) that cannot jump. When the wall clock moves (manual change, NTP
 * correction, time zone sync) the UTC time of a fix is re-derived from the last
 * trusted fix plus the monotonic delta, so points stay ordered and durations stay
 * true. Pure: feed fixes in arrival order.
 */
class ClockGuard(private val toleranceMs: Long = 2_000) {
    private var lastUtcMs: Long? = null
    private var lastMonoMs: Long? = null

    /** Number of fixes whose wall-clock time was corrected; for logging/diagnostics. */
    var corrections: Int = 0
        private set

    /** Returns the UTC time to use for a fix with wall clock [utcMs] and monotonic [monoMs]. */
    fun resolve(utcMs: Long, monoMs: Long?): Long {
        val lu = lastUtcMs
        val lm = lastMonoMs
        var out = utcMs
        if (monoMs != null && lu != null && lm != null && monoMs >= lm) {
            val expected = lu + (monoMs - lm)
            if (abs(utcMs - expected) > toleranceMs) {
                out = expected
                corrections++
            }
        }
        lastUtcMs = out
        if (monoMs != null) lastMonoMs = monoMs
        return out
    }
}
