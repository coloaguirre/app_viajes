package com.bitacoraviaje.app.weather

import com.bitacoraviaje.app.enrichment.OsmHttp
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One hour of one location as the service reports it; nulls where the model had no value. */
data class HourlyWeather(
    val timeUtcMs: Long,
    val temperatureC: Double?,
    val apparentC: Double?,
    val precipitationMm: Double?,
    val windKmh: Double?,
    val cloudPct: Int?,
    val weatherCode: Int?,
)

/** Open-Meteo itself refused the request (its `{"error":true,"reason":…}` body on a 4xx): retrying will not help, the segment is done without data. */
class PermanentWeatherError(message: String) : IOException(message)

/** Hourly weather for a set of locations on one UTC day; the result list follows the input order. */
interface WeatherSource {
    fun hourly(locations: List<Pair<Double, Double>>, dayUtc: LocalDate, nowUtcMs: Long): List<List<HourlyWeather>>
}

/**
 * Open-Meteo (backlog D3): free, no key, CC BY 4.0. The forecast endpoint also serves the recent
 * past (about three months); older days come from the ERA5 archive, which lags a few days. One
 * request per day covers every location (comma-separated coordinates, rounded to ~1 km by the
 * caller). Goes through [OsmHttp] for the User-Agent, timeouts, cache and backoff.
 */
class OpenMeteoClient @Inject constructor(private val http: OsmHttp) : WeatherSource {
    var forecastBase: String = FORECAST_BASE
    var archiveBase: String = ARCHIVE_BASE

    override fun hourly(locations: List<Pair<Double, Double>>, dayUtc: LocalDate, nowUtcMs: Long): List<List<HourlyWeather>> {
        if (locations.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(nowUtcMs).atOffset(ZoneOffset.UTC).toLocalDate()
        val base = if (dayUtc.isBefore(today.minusDays(ARCHIVE_AFTER_DAYS))) archiveBase else forecastBase
        val url = base + "?latitude=" + locations.joinToString(",") { fmt(it.first) } + "&longitude=" + locations.joinToString(",") { fmt(it.second) } +
            "&hourly=$HOURLY&start_date=$dayUtc&end_date=$dayUtc&timezone=UTC"
        http.client.newCall(OsmHttp.get(url)).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                val reason = errorReason(body)
                val msg = "Open-Meteo HTTP ${resp.code}: ${reason ?: ""}".trim()
                // Only the service's own error object is final; a 401/403 from a captive portal or proxy is retried later (review N1).
                if (resp.code in 400..499 && resp.code != 429 && reason != null) throw PermanentWeatherError(msg) else throw IOException(msg)
            }
            // Anything the parser cannot read is an IOException too: the caller keeps the other days (review I3).
            return try { parse(body, locations.size) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Open-Meteo: unreadable answer (${e.javaClass.simpleName})", e) }
        }
    }

    companion object {
        const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
        const val ARCHIVE_BASE = "https://archive-api.open-meteo.com/v1/archive"
        /** The forecast API keeps ~92 days of past; the archive lags ~5 days. Switch well inside both windows. */
        const val ARCHIVE_AFTER_DAYS = 60L
        const val HOURLY = "temperature_2m,apparent_temperature,precipitation,wind_speed_10m,cloud_cover,weather_code"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)

        private fun errorReason(body: String): String? = runCatching { json.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.content }.getOrNull()

        /** The service answers an object for one location and an array for several; both become one list per location. */
        fun parse(body: String, expected: Int): List<List<HourlyWeather>> {
            val root = json.parseToJsonElement(body)
            val objects: List<JsonObject> = when (root) {
                is JsonArray -> root.map { it.jsonObject }
                is JsonObject -> {
                    root["error"]?.let { throw IOException("Open-Meteo: ${root["reason"]?.jsonPrimitive?.content ?: "error"}") }
                    listOf(root)
                }
                else -> throw IOException("Open-Meteo: unexpected answer")
            }
            if (objects.size != expected) throw IOException("Open-Meteo: ${objects.size} locations for $expected asked")
            return objects.map { o -> parseHourly(o["hourly"]?.jsonObject ?: throw IOException("Open-Meteo: no hourly block")) }
        }

        private fun parseHourly(h: JsonObject): List<HourlyWeather> {
            val times = h["time"]?.jsonArray ?: return emptyList()
            fun col(k: String) = h[k]?.jsonArray
            fun num(a: JsonArray?, i: Int): Double? = a?.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull()
            val t2 = col("temperature_2m"); val ap = col("apparent_temperature"); val pr = col("precipitation"); val ws = col("wind_speed_10m"); val cc = col("cloud_cover"); val wc = col("weather_code")
            return times.indices.map { i ->
                val iso = times[i].jsonPrimitive.content // "2026-10-12T00:00" in UTC
                val ms = java.time.LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
                HourlyWeather(ms, num(t2, i), num(ap, i), num(pr, i), num(ws, i), num(cc, i)?.toInt(), num(wc, i)?.toInt())
            }
        }
    }
}
