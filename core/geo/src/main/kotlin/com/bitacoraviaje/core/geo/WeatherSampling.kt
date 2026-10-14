package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint

/**
 * Where to ask the weather for (backlog D3): one position per clock hour the segment covers,
 * taken from the track at the hour's centre (interpolated across gaps up to [maxInterpGapMs], or the
 * nearest fix within [maxNearestMs]); hours the phone saw nothing of are skipped.
 * Coordinates are rounded to [decimals] (2 ≈ 1 km) before leaving the phone, and hours whose
 * rounded position repeats share one lookup ([Plan.locations]).
 */
object WeatherSampling {
    const val HOUR_MS = 3_600_000L

    /** @property hourUtcMs start of the UTC hour; @property lat/lon rounded; @property locationIndex into [Plan.locations]. */
    data class Sample(val hourUtcMs: Long, val lat: Double, val lon: Double, val locationIndex: Int)

    data class Plan(val samples: List<Sample>, val locations: List<Pair<Double, Double>>) {
        val isEmpty: Boolean get() = samples.isEmpty()
    }

    fun plan(
        points: List<TrackPoint>,
        startUtcMs: Long,
        endUtcMs: Long,
        decimals: Int = 2,
        maxNearestMs: Long = 45 * 60_000L,
        maxInterpGapMs: Long = 2 * HOUR_MS,
        maxHours: Int = 24 * 14,
    ): Plan {
        if (points.isEmpty() || endUtcMs <= startUtcMs) return Plan(emptyList(), emptyList())
        val firstHour = startUtcMs / HOUR_MS * HOUR_MS
        val lastHour = (endUtcMs - 1) / HOUR_MS * HOUR_MS
        val locations = ArrayList<Pair<Double, Double>>()
        val index = HashMap<Pair<Double, Double>, Int>()
        val samples = ArrayList<Sample>()
        var hour = firstHour
        while (hour <= lastHour && samples.size < maxHours) {
            val centre = (hour + HOUR_MS / 2).coerceIn(startUtcMs, endUtcMs)
            val pos = when (val p = positionAt(points, centre, LinkConfig(maxInterpGapMs = maxInterpGapMs, maxNearestMs = maxNearestMs))) {
                is PositionAt.Interpolated -> p.lat to p.lon
                is PositionAt.Nearest -> p.lat to p.lon
                PositionAt.None -> null
            }
            if (pos != null) {
                val key = CoordinateRounding.round(pos.first, decimals) to CoordinateRounding.round(pos.second, decimals)
                val i = index.getOrPut(key) { locations += key; locations.lastIndex }
                samples += Sample(hour, key.first, key.second, i)
            }
            hour += HOUR_MS
        }
        return Plan(samples, locations)
    }
}
