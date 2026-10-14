package com.bitacoraviaje.core.model

/**
 * One hourly weather observation along a segment (backlog D3): what the weather was where the
 * traveller was at that hour. Fetched after the segment closes from Open-Meteo (CC BY 4.0), with
 * the coordinates rounded to ~1 km; [timeUtcMs] is the hour's centre in UTC.
 */
data class WeatherSample(
    val id: String,
    val segmentId: String,
    val timeUtcMs: Long,
    val lat: Double,
    val lon: Double,
    val temperatureC: Double?,
    val apparentC: Double?,
    val precipitationMm: Double?,
    val windKmh: Double?,
    val cloudPct: Int?,
    /** WMO weather interpretation code (0 clear … 99 thunderstorm with hail); see [WeatherCodes]. */
    val weatherCode: Int?,
    val source: String = SOURCE_OPEN_METEO,
    val fetchedUtcMs: Long? = null,
) {
    companion object {
        const val SOURCE_OPEN_METEO = "Open-Meteo"
        const val LICENSE_CC_BY = "CC BY 4.0"
        const val SOURCE_URL = "https://open-meteo.com/"
    }
}

/** WMO 4677 weather codes as Open-Meteo reports them, in Spanish, plus a coarse family for icons. */
object WeatherCodes {
    enum class Family { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM, UNKNOWN }

    fun family(code: Int?): Family = when (code) {
        null -> Family.UNKNOWN
        0, 1 -> Family.CLEAR
        2, 3 -> Family.CLOUDY
        45, 48 -> Family.FOG
        in 51..57, in 61..67, in 80..82 -> Family.RAIN
        in 71..77, 85, 86 -> Family.SNOW
        95, 96, 99 -> Family.STORM
        else -> Family.UNKNOWN
    }

    fun label(code: Int?): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45 -> "Niebla"
        48 -> "Niebla con escarcha"
        51, 53, 55 -> "Llovizna"
        56, 57 -> "Llovizna helada"
        61 -> "Lluvia ligera"
        63 -> "Lluvia"
        65 -> "Lluvia intensa"
        66, 67 -> "Lluvia helada"
        71 -> "Nevada ligera"
        73 -> "Nevada"
        75 -> "Nevada intensa"
        77 -> "Granos de nieve"
        80 -> "Chaparrones ligeros"
        81 -> "Chaparrones"
        82 -> "Chaparrones fuertes"
        85, 86 -> "Chaparrones de nieve"
        95 -> "Tormenta"
        96, 99 -> "Tormenta con granizo"
        else -> "Sin dato"
    }

    /** Emoji for lists and cards; text labels stay the source of truth for exports. */
    fun icon(code: Int?): String = when (family(code)) {
        Family.CLEAR -> "☀️"
        Family.CLOUDY -> "⛅"
        Family.FOG -> "🌫️"
        Family.RAIN -> "🌧️"
        Family.SNOW -> "🌨️"
        Family.STORM -> "⛈️"
        Family.UNKNOWN -> "🌡️"
    }

    /**
     * The code that best represents a set of hours: the worst family present if it covered at least
     * [minShare] of the hours (rain for a third of the afternoon is "the" afternoon), else the most frequent.
     */
    fun dominant(codes: List<Int>, minShare: Double = 0.3): Int? {
        if (codes.isEmpty()) return null
        val severity = listOf(Family.STORM, Family.SNOW, Family.RAIN, Family.FOG, Family.CLOUDY, Family.CLEAR)
        for (fam in severity) {
            val hits = codes.filter { family(it) == fam }
            if (hits.size >= codes.size * minShare && hits.isNotEmpty()) return hits.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        }
        return codes.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }
}
