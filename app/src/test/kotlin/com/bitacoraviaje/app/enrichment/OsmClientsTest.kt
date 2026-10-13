package com.bitacoraviaje.app.enrichment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.geo.RouteNaming
import com.bitacoraviaje.core.geo.TrailMatch
import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 3.6 acceptance: recorded Nominatim/Overpass answers (app/src/test/resources/enrichment) drive the naming. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OsmClientsTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OsmHttp

    private fun fixture(name: String) = javaClass.getResourceAsStream("/enrichment/$name")!!.readBytes().toString(Charsets.UTF_8)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OsmHttp(ApplicationProvider.getApplicationContext<Context>())
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `nominatim reverse answers are parsed into road and locality and the user agent is sent`() {
        server.enqueue(MockResponse(body = fixture("nominatim_reverse_z16_road.json")))
        server.enqueue(MockResponse(body = fixture("nominatim_reverse_z14.json")))
        server.enqueue(MockResponse(body = fixture("nominatim_reverse_z10.json")))
        val c = NominatimClient(http)
        val road = c.reverse(server.url("/").toString(), -50.3391, -72.2674, 16)
        assertEquals("1 de Mayo", road.road)
        assertEquals("El Calafate", road.locality)
        val town = c.reverse(server.url("/").toString(), -49.3315, -72.8861, 14)
        assertEquals("El Chaltén", town.locality)
        val region = c.reverse(server.url("/").toString(), -49.62, -72.70, 10)
        assertEquals("Lago Argentino", region.name)
        assertEquals("Santa Cruz", region.state)
        assertEquals("Lago Argentino", region.locality, "a department counts as the place when there is no settlement")
        assertEquals("Por Lago Argentino", RouteNaming.routeName(listOf(region.toSample(), region.toSample())))
        val req = server.takeRequest()
        assertTrue(req.headers["User-Agent"]!!.startsWith("BitacoraViaje/"), "identifiable User-Agent per Nominatim policy")
        assertTrue(req.url.encodedPath.endsWith("/reverse") && req.url.queryParameter("zoom") == "16")
        assertEquals("1 de Mayo, por El Calafate", RouteNaming.routeName(listOf(road.toSample(), road.toSample())))
    }

    @Test
    fun `the sample trek over a real trail gets the trail name from the recorded overpass answer`() {
        val body = fixture("overpass_chalten_trails.json")
        val trails = OverpassClient.parse(body)
        assertTrue(trails.isNotEmpty(), "the recorded answer holds named trails")
        val longest = trails.maxByOrNull { it.geometry.size }!!
        // tools/sample-trips/trekking-sendero: a walk along the real trail with ±8 m GPS jitter (PLAN 3.6 acceptance).
        val gpx = javaClass.getResourceAsStream("/trekking-sendero/track.gpx") ?: error("sample trek missing: run tools/sample-trips/make_trail_trek.py")
        val trek = com.bitacoraviaje.core.export.GpxReader.read(gpx, "track.gpx", "America/Argentina/Buenos_Aires").trip.segments.single()
        val track = trek.points
        assertTrue(track.size > 100 && trek.segment.transport == com.bitacoraviaje.core.model.Transport.WALK)
        server.enqueue(MockResponse(body = body))
        val fetched = OverpassClient(http).trailsNear(server.url("/api/interpreter").toString(), track)
        assertEquals(trails.size, fetched.size)
        val best = assertNotNull(TrailMatch.bestTrail(track, fetched))
        assertEquals(longest.name, best.first.name)
        assertTrue(best.second >= 0.6)
        val q = server.takeRequest().body!!.utf8()
        assertTrue(q.contains("data=") && q.contains("around") && q.contains("route"), "overpass query posted as form data")
    }

    @Test
    fun `busy instances are retried with backoff and a non json answer is an error`() {
        server.enqueue(MockResponse(code = 503))
        server.enqueue(MockResponse(code = 429, headers = okhttp3.Headers.headersOf("Retry-After", "0")))
        server.enqueue(MockResponse(body = fixture("nominatim_reverse_z14.json")))
        val r = NominatimClient(http).reverse(server.url("/").toString(), -49.3315, -72.8861, 14)
        assertEquals("El Chaltén", r.locality)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse(body = "<html>busy</html>"))
        val failed = runCatching { OverpassClient(http).trailsNear(server.url("/api/interpreter").toString(), listOf(TrackPoint(segmentId = "s", timeUtcMs = 0, lat = -49.3, lon = -72.9), TrackPoint(segmentId = "s", timeUtcMs = 1, lat = -49.31, lon = -72.9))) }
        assertTrue(failed.isFailure)
    }
}
