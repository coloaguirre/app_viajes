package com.bitacoraviaje.app.weather

import android.util.Log
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.geo.WeatherSampling
import com.bitacoraviaje.core.geo.toIntervals
import com.bitacoraviaje.core.model.WeatherSample
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.TrackingRepository
import com.bitacoraviaje.data.repository.WeatherRepository
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Backlog D3: for a finished segment, plan one lookup per hour where the traveller was
 * ([WeatherSampling]), ask [WeatherSource] once per UTC day, and store the hours as
 * [WeatherSample]s. Idempotent: a segment already looked up completely (even with no usable hour) is
 * skipped unless forced; a partial run (a day offline, an hour still in the future) is retried by the
 * worker. What leaves the phone: coordinates rounded to ~1 km and the dates; never the track.
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
        if (!force && weather.fetchedAt(segmentId) != null) return Outcome(weather.samplesOf(segmentId).size, skipped = true, requests = 0, partial = false)
        val end = segment.endedUtcMs ?: return Outcome(0, skipped = true, requests = 0, partial = false)
        val now = clock.nowUtcMs()
        val points = tracking.validPoints(segmentId)
        val pauses = tracking.pausesOf(segmentId).toIntervals(now)
        val plan = WeatherSampling.plan(points, segment.startedUtcMs, end, pauses = pauses)
        if (plan.isEmpty) {
            weather.markFetched(segmentId, now, 0)
            return Outcome(0, skipped = false, requests = 0, partial = false)
        }
        val out = ArrayList<WeatherSample>()
        var requests = 0
        var partial = false
        // An hour whose centre has not passed yet would only get a forecast: leave it for the retry (review I2).
        val (ready, future) = plan.samples.partition { it.hourStartUtcMs + WeatherSampling.HOUR_MS / 2 <= now }
        if (future.isNotEmpty()) partial = true
        // One request per UTC day with every location that day touches; each hour reads its own location's row.
        for ((day, samples) in ready.groupBy { Instant.ofEpochMilli(it.hourStartUtcMs).atOffset(ZoneOffset.UTC).toLocalDate() }) {
            val locIdx = samples.map { it.locationIndex }.distinct()
            val locations = locIdx.map { plan.locations[it] }
            val rows = try {
                requests++
                source.hourly(locations, day, now)
            } catch (e: PermanentWeatherError) {
                Log.w(TAG, "weather day=$day refused for good: ${e.message}")
                continue // done for that day, with no data
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "weather day=$day failed: ${e.javaClass.simpleName} ${e.message}")
                partial = true
                continue
            }
            for (s in samples) {
                val hours = rows.getOrNull(locIdx.indexOf(s.locationIndex)) ?: continue
                val h = hours.firstOrNull { it.timeUtcMs == s.hourStartUtcMs } ?: continue
                if (h.temperatureC == null && h.weatherCode == null) continue // the model had nothing for that hour
                out += WeatherSample(
                    id = "wx-$segmentId-${s.hourStartUtcMs / 3_600_000}", segmentId = segmentId, timeUtcMs = s.hourStartUtcMs + WeatherSampling.HOUR_MS / 2,
                    lat = s.lat, lon = s.lon, temperatureC = h.temperatureC, apparentC = h.apparentC, precipitationMm = h.precipitationMm,
                    windKmh = h.windKmh, cloudPct = h.cloudPct, weatherCode = h.weatherCode, fetchedUtcMs = now,
                )
            }
        }
        // A partial answer never replaces a fuller one already stored (review M7), and an empty answer never wipes
        // stored hours (review N2): a forced refresh that gets nothing keeps what the segment had.
        val existing = weather.samplesOf(segmentId).size
        if (out.isNotEmpty() && (!partial || out.size >= existing)) weather.replace(segmentId, out)
        if (!partial) weather.markFetched(segmentId, now, out.size)
        Log.i(TAG, "weather segment=$segmentId hours=${out.size}/${plan.samples.size} requests=$requests partial=$partial")
        return Outcome(out.size, skipped = false, requests = requests, partial = partial)
    }

    companion object {
        private const val TAG = "Bitacora"
    }
}
