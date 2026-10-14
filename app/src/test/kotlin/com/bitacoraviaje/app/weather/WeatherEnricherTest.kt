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

    private val source = object : WeatherSource {
        override fun hourly(locations: List<Pair<Double, Double>>, dayUtc: LocalDate, nowUtcMs: Long): List<List<HourlyWeather>> {
            calls += locations to dayUtc
            if (fail) throw IOException("offline")
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
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun enricher() = WeatherEnricher(tracking, weather, source, prefs, Clock { now })

    /** A 3 h walk from 10:13 UTC: hours 10, 11, 12, 13 are touched. */
    private suspend fun closedSegment(): String {
        now = t0
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Clima")
        tracking.insertPoints((0 until 2160).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 5_000L, lat = -34.6 + i * 5.5 / 111_320, lon = -58.4) })
        now = t0 + 3 * 3_600_000L
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
        assertEquals(4, calls.single().first.size, "12 km north in 4 h: each hour's centre falls in its own ~1 km cell")
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
}
