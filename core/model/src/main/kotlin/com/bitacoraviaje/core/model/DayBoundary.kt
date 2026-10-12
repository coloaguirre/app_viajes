package com.bitacoraviaje.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-day arithmetic for the continuous trip (adenda 1): daily segments are cut
 * at local midnight of the segment's time zone. UTC in, UTC out.
 */
object DayBoundary {
    fun localDate(utcMs: Long, tzId: String): LocalDate = Instant.ofEpochMilli(utcMs).atZone(ZoneId.of(tzId)).toLocalDate()

    /** First instant (UTC ms) of the local day following [nowUtcMs]. */
    fun nextLocalMidnightUtcMs(nowUtcMs: Long, tzId: String): Long {
        val zone = ZoneId.of(tzId)
        val next = Instant.ofEpochMilli(nowUtcMs).atZone(zone).toLocalDate().plusDays(1)
        return next.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun isSameLocalDay(aUtcMs: Long, bUtcMs: Long, tzId: String): Boolean = localDate(aUtcMs, tzId) == localDate(bUtcMs, tzId)
}
