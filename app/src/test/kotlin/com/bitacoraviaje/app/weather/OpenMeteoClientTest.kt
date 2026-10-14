package com.bitacoraviaje.app.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.enrichment.OsmHttp
import java.io.IOException
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog D3: recorded Open-Meteo answers (app/src/test/resources/weather, fetched 2026-10-13 for generic Patagonian coordinates). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenMeteoClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenMeteoClient
    private val now = 1_792_000_000_000L // 2026-10-14

    private fun fixture(name: String) = javaClass.getResourceAsStream("/weather/$name")!!.readBytes().toString(Charsets.UTF_8)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenMeteoClient(OsmHttp(ApplicationProvider.getApplicationContext<Context>())).apply {
            forecastBase = server.url("/forecast").toString()
            archiveBase = server.url("/archive").toString()
        }
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `two locations in one request come back as two hourly lists in UTC`() {
        server.enqueue(MockResponse(body = fixture("open_meteo_forecast_two_locations.json")))
        val rows = client.hourly(listOf(-49.33 to -72.89, -49.27 to -72.95), LocalDate.of(2026, 10, 12), now)
        assertEquals(2, rows.size)
        assertEquals(24, rows[0].size)
        val first = rows[0][0]
        assertEquals(1_791_763_200_000L, first.timeUtcMs, "2026-10-12T00:00Z")
        assertEquals(10.6, first.temperatureC)
        assertEquals(2, first.weatherCode)
        assertEquals(74, first.cloudPct)
        val req = server.takeRequest()
        assertTrue(req.url.encodedPath.startsWith("/forecast"), "two days ago: forecast endpoint")
        assertEquals("-49.33,-49.27", req.url.queryParameter("latitude"), "two decimals, comma separated")
        assertEquals("UTC", req.url.queryParameter("timezone"))
        assertEquals("2026-10-12", req.url.queryParameter("start_date"))
        assertTrue(req.headers["User-Agent"]!!.startsWith("BitacoraViaje/"))
    }

    @Test
    fun `an old day goes to the archive and a single location is still a list`() {
        server.enqueue(MockResponse(body = fixture("open_meteo_archive_one_location.json")))
        val rows = client.hourly(listOf(-49.33 to -72.89), LocalDate.of(2026, 7, 1), now)
        assertEquals(1, rows.size)
        assertEquals(3.5, rows[0][0].temperatureC)
        assertTrue(server.takeRequest().url.encodedPath.startsWith("/archive"))
    }

    @Test
    fun `the service's error object becomes an IOException with its reason and a count mismatch is refused`() {
        server.enqueue(MockResponse(code = 400, body = fixture("open_meteo_error.json")))
        val e = assertFailsWith<PermanentWeatherError> { client.hourly(listOf(0.0 to 0.0), LocalDate.of(2026, 10, 12), now) }
        assertTrue(e.message!!.contains("out of allowed range"), e.message)
        server.enqueue(MockResponse(code = 503, body = "busy"))
        server.enqueue(MockResponse(code = 503, body = "busy"))
        server.enqueue(MockResponse(code = 503, body = "busy"))
        server.enqueue(MockResponse(code = 503, body = "busy"))
        val busy = assertFailsWith<IOException> { client.hourly(listOf(0.0 to 0.0), LocalDate.of(2026, 10, 12), now) }
        assertTrue(busy !is PermanentWeatherError, "a 503 is retried later, not refused for good")
        server.enqueue(MockResponse(body = "<html>captive portal</html>"))
        val junk = assertFailsWith<IOException> { client.hourly(listOf(0.0 to 0.0), LocalDate.of(2026, 10, 12), now) }
        assertTrue(junk.message!!.contains("unreadable"), junk.message)
        assertFailsWith<IOException> { OpenMeteoClient.parse(fixture("open_meteo_forecast_two_locations.json"), expected = 1) }
        assertTrue(OpenMeteoClient.parse(fixture("open_meteo_error.json").let { "{\"hourly\":{\"time\":[]}}" }, 1).single().isEmpty())
    }
}
