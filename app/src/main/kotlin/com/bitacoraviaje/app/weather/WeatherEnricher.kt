package com.bitacoraviaje.app.weather

import android.util.Log
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.geo.WeatherSampling
import com.bitacoraviaje.core.model.WeatherSample
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.TrackingRepository
import com.bitacoraviaje.data.repository.WeatherRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Backlog D3: for a finished segment, plan one lookup per hour where the traveller was
 * ([WeatherSampling]), ask [WeatherSource] once per UTC day, and store the hours as
 * [WeatherSample]s. Idempotent: a segment with weather is skipped unless forced. What leaves the
 * phone: coordinates rounded to ~1 km and the dates; never the track.
 */
class WeatherEnricher @Inject constructor(
    private val tracking: TrackingRepository,
    private val weather: WeatherRepository,
    private val source: WeatherSource,
    private val prefs: UserPreferences,
    private val clock: Clock,
) {
    data class Outcome(val samples: Int, val skipped: Boolean, val requests: Int, val partial: Boolean)

    suspend fun enrich(segmentId: String, force: Boolean = false): Outcome? {
        val segment = tracking.getSegment(segmentId) ?: return null
        if (!prefs.settings.first().fetchWeather) return Outcome(0, skipped = true, requests = 0, partial = false)
        if (!force && weather.hasWeather(segmentId)) return Outcome(weather.samplesOf(segmentId).size, skipped = true, requests = 0, partial = false)
        val end = segment.endedUtcMs ?: return Outcome(0, skipped = true, requests = 0, partial = false)
        val points = tracking.validPoints(segmentId)
        val plan = WeatherSampling.plan(points, segment.startedUtcMs, end)
        if (plan.isEmpty) return Outcome(0, skipped = false, requests = 0, partial = false)
        val now = clock.nowUtcMs()
        val out = ArrayList<WeatherSample>()
        var requests = 0
        var partial = false
        // One request per UTC day with every location that day touches; each hour reads its own location's row.
        for ((day, samples) in plan.samples.groupBy { Instant.ofEpochMilli(it.hourUtcMs).atOffset(ZoneOffset.UTC).toLocalDate() }) {
            val locIdx = samples.map { it.locationIndex }.distinct()
            val locations = locIdx.map { plan.locations[it] }
            val rows = try {
                requests++
                source.hourly(locations, day, now)
            } catch (e: IOException) {
                Log.w(TAG, "weather day=$day failed: ${e.javaClass.simpleName} ${e.message}")
                partial = true
                continue
            }
            for (s in samples) {
                val hours = rows.getOrNull(locIdx.indexOf(s.locationIndex)) ?: continue
                val h = hours.firstOrNull { it.timeUtcMs == s.hourUtcMs } ?: continue
                if (h.temperatureC == null && h.weatherCode == null) continue // the model had nothing for that hour
                out += WeatherSample(
                    id = "wx-$segmentId-${s.hourUtcMs / 3_600_000}", segmentId = segmentId, timeUtcMs = s.hourUtcMs + WeatherSampling.HOUR_MS / 2,
                    lat = s.lat, lon = s.lon, temperatureC = h.temperatureC, apparentC = h.apparentC, precipitationMm = h.precipitationMm,
                    windKmh = h.windKmh, cloudPct = h.cloudPct, weatherCode = h.weatherCode, fetchedUtcMs = now,
                )
            }
        }
        if (out.isNotEmpty() || !partial) weather.replace(segmentId, out)
        Log.i(TAG, "weather segment=$segmentId hours=${out.size}/${plan.samples.size} requests=$requests partial=$partial")
        return Outcome(out.size, skipped = false, requests = requests, partial = partial)
    }

    companion object {
        private const val TAG = "Bitacora"
    }
}
