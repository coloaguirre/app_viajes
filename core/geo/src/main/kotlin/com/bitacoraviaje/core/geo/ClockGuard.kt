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

    /**
     * Seed with the last persisted fix of a resumed segment so a wall clock that moved
     * while the service was dead is corrected from the first new fix. A monotonic value
     * smaller than the seed means the phone rebooted: the baseline is dropped and the
     * wall clock is trusted again.
     */
    fun seed(utcMs: Long, monoMs: Long?) {
        lastUtcMs = utcMs
        lastMonoMs = monoMs
    }

    /** Returns the UTC time to use for a fix with wall clock [utcMs] and monotonic [monoMs]. */
    fun resolve(utcMs: Long, monoMs: Long?): Long {
        val lu = lastUtcMs
        val lm = lastMonoMs
        var out = utcMs
        if (monoMs != null && lm != null && monoMs < lm) {
            // Reboot: monotonic clock restarted. Trust the wall clock from here on.
            lastUtcMs = utcMs
            lastMonoMs = monoMs
            return utcMs
        }
        if (monoMs != null && lu != null && lm != null) {
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
