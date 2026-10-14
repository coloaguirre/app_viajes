package com.bitacoraviaje.app.weather

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.TrackingRepository
import com.bitacoraviaje.data.repository.WeatherRepository
import java.io.IOException
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog D3: one request per day, one sample per hour where the traveller was, idempotent, offline-safe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherEnricherTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var weather: WeatherRepository
    private lateinit var prefs: UserPreferences
    private val t0 = 1_791_800_000_000L // 2026-10-12 10:13:20 UTC
    private var now = t0 + 86_400_000L
    private val calls = ArrayList<Pair<List<Pair<Double, Double>>, LocalDate>>()
    private var fail = false
    private var failDay: LocalDate? = null
    private var permanent = false

    private val source = object : WeatherSource {
        override fun hourly(locations: List<Pair<Double, Double>>, dayUtc: LocalDate, nowUtcMs: Long): List<List<HourlyWeather>> {
            calls += locations to dayUtc
            if (permanent) throw PermanentWeatherError("Open-Meteo HTTP 400: out of range")
            if (fail || dayUtc == failDay) throw IOException("offline")
            val day0 = dayUtc.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            return locations.map { (lat, _) -> (0 until 24).map { h -> HourlyWeather(day0 + h * 3_600_000L, 10.0 + h + lat, 9.0, 0.0, 5.0, 20, if (h < 12) 1 else 61) } }
        }
    }

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { now })
        weather = WeatherRepository(db)
        prefs = UserPreferences(context)
        // The DataStore file outlives a test in the same JVM: start every test with the default.
        kotlinx.coroutines.runBlocking { prefs.setFetchWeather(true) }
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun enricher() = WeatherEnricher(tracking, weather, source, prefs, Clock { now })

    /** A walk of [hours] h from 10:13 UTC on 2026-10-12 (3 h → hours 10, 11, 12, 13 are touched). */
    private suspend fun closedSegment(hours: Int = 3): String {
        now = t0
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Clima")
        tracking.insertPoints((0 until hours * 720).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 5_000L, lat = -34.6 + (i % 720) * 5.5 / 111_320, lon = -58.4) })
        now = t0 + hours * 3_600_000L
        tracking.finishTrip(s.tripId)
        now = t0 + 86_400_000L
        return s.id
    }

    @Test
    fun `one request for the day, one sample per hour at the hour's centre, and a second run is skipped`() = runTest {
        val id = closedSegment()
        val o = assertNotNull(enricher().enrich(id))
        assertEquals(4, o.samples)
        assertEquals(1, o.requests)
        assertEquals(LocalDate.of(2026, 10, 12), calls.single().second)
        assertTrue(calls.single().first.size in 1..2, "the walk restarts every hour at the same place: one or two ~1 km cells")
        val rows = weather.samplesOf(id)
        assertEquals(listOf(10, 11, 12, 13), rows.map { ((it.timeUtcMs / 3_600_000) % 24).toInt() })
        assertTrue(rows.all { it.timeUtcMs % 3_600_000 == 1_800_000L }, "stored at the half hour")
        assertEquals(listOf(1, 1, 61, 61), rows.map { it.weatherCode })
        assertEquals("Open-Meteo", rows.first().source)
        val again = assertNotNull(enricher().enrich(id))
        assertTrue(again.skipped)
        assertEquals(1, calls.size, "no second request")
        assertEquals(4, assertNotNull(enricher().enrich(id, force = true)).samples)
        assertEquals(2, calls.size)
    }

    @Test
    fun `offline keeps the segment without weather and reports partial for a retry`() = runTest {
        val id = closedSegment()
        fail = true
        val o = assertNotNull(enricher().enrich(id))
        assertTrue(o.partial)
        assertEquals(0, o.samples)
        assertTrue(weather.samplesOf(id).isEmpty())
        fail = false
        assertEquals(4, assertNotNull(enricher().enrich(id)).samples, "the retry fills it: nothing marked it as done")
    }

    @Test
    fun `disabled in settings means no request at all`() = runTest {
        val id = closedSegment()
        prefs.setFetchWeather(false)
        val o = assertNotNull(enricher().enrich(id))
        assertTrue(o.skipped)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a segment across two UTC days makes two requests, one day offline keeps the other and stays pending`() = runTest {
        val id = closedSegment(hours = 16) // 10:13 → 02:13 next day
        failDay = LocalDate.of(2026, 10, 13)
        val o = assertNotNull(enricher().enrich(id))
        assertEquals(2, o.requests)
        assertTrue(o.partial)
        assertEquals(14, o.samples, "hours 10–23 of the first day")
        assertEquals(14, weather.samplesOf(id).size, "the day that answered is kept")
        assertEquals(null, weather.fetchedAt(id), "still pending: the worker retries")
        failDay = null
        val done = assertNotNull(enricher().enrich(id))
        assertTrue(!done.partial && done.samples == 17, "all 17 hours after the retry: ${done.samples}")
        assertNotNull(weather.fetchedAt(id))
    }

    @Test
    fun `an hour still in the future is left for the retry, never stored as a forecast`() = runTest {
        val id = closedSegment()
        now = t0 + 2 * 3_600_000L + 50 * 60_000L // 13:03 UTC: the hour 13 (centre 13:30) has not passed
        val o = assertNotNull(enricher().enrich(id))
        assertTrue(o.partial, "outcome=$o samples=${weather.samplesOf(id).map { it.timeUtcMs }} segmentEnd=${tracking.getSegment(id)?.endedUtcMs} now=$now")
        assertEquals(3, o.samples)
        assertEquals(listOf(10, 11, 12), weather.samplesOf(id).map { ((it.timeUtcMs / 3_600_000) % 24).toInt() })
        assertEquals(null, weather.fetchedAt(id))
        now = t0 + 86_400_000L
        assertEquals(4, assertNotNull(enricher().enrich(id)).samples)
        assertNotNull(weather.fetchedAt(id))
    }

    @Test
    fun `a request refused for good marks the segment as looked up with no data, so it is not asked again`() = runTest {
        val id = closedSegment()
        permanent = true
        val o = assertNotNull(enricher().enrich(id))
        assertTrue(!o.partial && o.samples == 0)
        assertNotNull(weather.fetchedAt(id))
        permanent = false
        assertTrue(assertNotNull(enricher().enrich(id)).skipped, "no retry on its own")
        assertEquals(1, calls.size)
        assertEquals(4, assertNotNull(enricher().enrich(id, force = true)).samples, "force asks again")
    }

    @Test
    fun `a segment without points is marked as looked up without any request`() = runTest {
        now = t0
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "Vacío")
        now = t0 + 60_000
        tracking.finishTrip(s.tripId)
        val o = assertNotNull(enricher().enrich(s.id))
        assertEquals(0, o.requests)
        assertNotNull(weather.fetchedAt(s.id))
    }
}
