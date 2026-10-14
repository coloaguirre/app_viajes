package com.bitacoraviaje.core.timeline

import com.bitacoraviaje.core.model.WeatherCodes
import com.bitacoraviaje.core.model.WeatherSample
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the hourly samples of a segment into what the diary shows (backlog D3): the blocks of the
 * local day (morning 6–12, afternoon 12–20, night 20–6) the segment touched, each with its range of
 * temperatures, rain, strongest wind and the weather that best describes it; and the sample nearest
 * to an instant, for photos, stops and the map card. Pure Kotlin.
 */
object WeatherDigest {
    enum class Block(val startHour: Int, val endHour: Int) {
        MORNING(6, 12), AFTERNOON(12, 20), NIGHT(20, 6);

        val label: String get() = when (this) { MORNING -> "Mañana"; AFTERNOON -> "Tarde"; NIGHT -> "Noche" }
    }

    data class BlockSummary(
        val block: Block,
        /** Local date (ISO) the block belongs to; a night that spans midnight carries the date it started. */
        val localDate: String,
        val startUtcMs: Long,
        val endUtcMs: Long,
        val hours: Int,
        val minTempC: Double?,
        val maxTempC: Double?,
        val precipitationMm: Double,
        val maxWindKmh: Double?,
        val weatherCode: Int?,
    ) {
        /** "Despejado, 8–17 °C" plus rain and wind when worth saying. */
        val text: String get() = buildString {
            append(WeatherCodes.label(weatherCode))
            if (minTempC != null && maxTempC != null) {
                append(", ")
                val lo = Math.round(minTempC)
                val hi = Math.round(maxTempC)
                append(if (lo == hi) "$lo °C" else "$lo–$hi °C")
            }
            if (precipitationMm >= 0.5) append(", ${formatMm(precipitationMm)} mm de lluvia")
            if (maxWindKmh != null && maxWindKmh >= 30) append(", viento ${Math.round(maxWindKmh)} km/h")
        }
    }

    /** Max distance in time between an instant and the sample that describes it. */
    const val MAX_GAP_MS = 90 * 60_000L

    /** The sample closest to [timeUtcMs], within [MAX_GAP_MS]; null otherwise. */
    fun at(samples: List<WeatherSample>, timeUtcMs: Long): WeatherSample? =
        samples.minByOrNull { Math.abs(it.timeUtcMs - timeUtcMs) }?.takeIf { Math.abs(it.timeUtcMs - timeUtcMs) <= MAX_GAP_MS }

    /** "☀️ 23 °C" for a list row; null without a temperature. */
    fun brief(sample: WeatherSample?): String? {
        val t = sample?.temperatureC ?: return null
        return "${WeatherCodes.icon(sample.weatherCode)} ${Math.round(t)} °C"
    }

    /**
     * One line for a whole segment (video chapter card): the dominant weather and the temperature range,
     * e.g. "Parcialmente nublado, 8–17 °C". Mirrored in desktop-renderer/planner.py: keep both in step.
     */
    fun segmentText(samples: List<WeatherSample>): String? {
        if (samples.isEmpty()) return null
        val code = WeatherCodes.dominant(samples.mapNotNull { it.weatherCode })
        val temps = samples.mapNotNull { it.temperatureC }
        if (code == null && temps.isEmpty()) return null
        return buildString {
            if (code != null) append(WeatherCodes.label(code))
            if (temps.isNotEmpty()) {
                val lo = Math.round(temps.min())
                val hi = Math.round(temps.max())
                if (isNotEmpty()) append(", ")
                append(if (lo == hi) "$lo °C" else "$lo–$hi °C")
            }
        }
    }

    /** Blocks in time order, only those with at least one sample. */
    fun blocks(samples: List<WeatherSample>, tzId: String): List<BlockSummary> {
        if (samples.isEmpty()) return emptyList()
        val zone = runCatching { ZoneId.of(tzId) }.getOrDefault(ZoneId.of("UTC"))
        data class Key(val date: String, val block: Block)
        val groups = LinkedHashMap<Key, MutableList<WeatherSample>>()
        for (s in samples.sortedBy { it.timeUtcMs }) {
            val local = Instant.ofEpochMilli(s.timeUtcMs).atZone(zone)
            val hour = local.hour
            val block = when {
                hour < 6 -> Block.NIGHT
                hour < 12 -> Block.MORNING
                hour < 20 -> Block.AFTERNOON
                else -> Block.NIGHT
            }
            // Hours before 6 belong to the night that started the evening before.
            val date = (if (block == Block.NIGHT && hour < 6) local.toLocalDate().minusDays(1) else local.toLocalDate()).toString()
            groups.getOrPut(Key(date, block)) { ArrayList() } += s
        }
        return groups.map { (k, list) ->
            val temps = list.mapNotNull { it.temperatureC }
            BlockSummary(
                block = k.block,
                localDate = k.date,
                startUtcMs = list.first().timeUtcMs,
                endUtcMs = list.last().timeUtcMs,
                hours = list.size,
                minTempC = temps.minOrNull(),
                maxTempC = temps.maxOrNull(),
                precipitationMm = list.sumOf { it.precipitationMm ?: 0.0 },
                maxWindKmh = list.mapNotNull { it.windKmh }.maxOrNull(),
                weatherCode = WeatherCodes.dominant(list.mapNotNull { it.weatherCode }),
            )
        }
    }

    private fun formatMm(mm: Double): String = if (mm >= 10) Math.round(mm).toString() else String.format(java.util.Locale.ROOT, "%.1f", mm)
}
