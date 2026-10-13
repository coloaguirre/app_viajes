package com.bitacoraviaje.app.enrichment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.geo.PlaceClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 4.1: recorded Wikipedia / Wikidata / Overpass answers (app/src/test/resources/enrichment, fetched 2026-10-13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiClientsTest {
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
    fun `geosearch parses nearest first and falls back to english when spanish is empty`() {
        val near = WikipediaClient.parseGeoSearch(fixture("wikipedia_geosearch_fitzroy.json"))
        assertEquals("Monte Fitz Roy", near.first().title)
        assertTrue(near.zipWithNext().all { (a, b) -> a.distM <= b.distM })
        assertEquals(emptyList(), WikipediaClient.parseGeoSearch(fixture("wikipedia_geosearch_empty.json")))
        val c = WikipediaClient(http).apply { baseUrlTemplate = server.url("/").toString().trimEnd('/') + "/{lang}" }
        server.enqueue(MockResponse(body = fixture("wikipedia_geosearch_chalten.json")))
        val r = c.geoSearch("es", -49.331408, -72.886129)
        assertEquals("El Chaltén", r.single().title)
        val req = server.takeRequest()
        assertTrue(req.url.encodedPath.startsWith("/es/w/api.php"))
        assertEquals("-49.3314|-72.8861", req.url.queryParameter("gscoord"), "4 decimals, never more")
        assertTrue(req.headers["User-Agent"]!!.startsWith("BitacoraViaje/"))
    }

    @Test
    fun `summary comes from REST v1 and from the Action API when REST is gone`() {
        val c = WikipediaClient(http).apply { baseUrlTemplate = server.url("/").toString().trimEnd('/') + "/{lang}" }
        server.enqueue(MockResponse(body = fixture("wikipedia_summary_chalten.json")))
        val rest = assertNotNull(c.summary("es", "El Chaltén"))
        assertEquals("Q1323978", rest.wikidataId)
        assertEquals("https://es.wikipedia.org/wiki/El_Chalt%C3%A9n", rest.pageUrl)
        assertTrue(rest.extract.startsWith("El Chaltén es un municipio") && rest.isUsable && rest.thumbnailUrl!!.startsWith("https://"))
        assertEquals("/es/api/rest_v1/page/summary/El_Chalt%C3%A9n", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse(code = 410)) // REST v1 retired
        server.enqueue(MockResponse(body = fixture("wikipedia_action_extract_chalten.json")))
        val action = assertNotNull(c.summary("es", "El Chaltén"))
        assertEquals(rest.wikidataId, action.wikidataId)
        assertTrue(action.extract.startsWith(rest.extract.substringBefore('\n')), "same article, the Action API may return one more sentence")
        assertEquals("https://es.wikipedia.org/wiki/El_Chalt%C3%A9n", action.pageUrl)
        server.takeRequest()
        assertTrue(server.takeRequest().url.queryParameter("prop")!!.contains("extracts"))

        server.enqueue(MockResponse(code = 404))
        assertNull(c.summary("es", "No existe"))
        val disamb = WikipediaClient.parseRestSummary("""{"type":"disambiguation","title":"Chaltén","extract":"Chaltén puede referirse a:","lang":"es"}""", "es")
        assertFalse(disamb.isUsable, "disambiguation pages are not places")
    }

    @Test
    fun `wikidata facts are literal values with their class and card lines`() {
        val town = assertNotNull(WikidataClient.parse(fixture("wikidata_Q1323978_chalten.json"), "Q1323978", "es"))
        assertEquals("El Chaltén", town.label)
        assertEquals(PlaceClass.LOCALITY, town.placeClass)
        assertEquals("municipality", town.kind)
        assertEquals(1865L, town.population)
        assertEquals(2022, town.populationYear, "the latest census wins")
        assertEquals(397.0, town.elevationM)
        assertEquals(28, town.sitelinks)
        assertEquals("El Chaltén", town.wikipediaTitle)
        assertEquals(listOf("Altitud: 397 m", "Población: 1.865 (2022)"), town.factLines())

        val peak = assertNotNull(WikidataClient.parse(fixture("wikidata_Q215138_fitzroy.json"), "Q215138", "es"))
        assertEquals(PlaceClass.NATURAL, peak.placeClass)
        assertEquals("peak", peak.kind)
        assertEquals(listOf("Altitud: 3.406 m"), peak.factLines())
        assertTrue(peak.sitelinks > 30 && peak.countryQids.containsAll(listOf("Q298", "Q414")))

        val c = WikidataClient(http).apply { baseUrl = server.url("/").toString() }
        server.enqueue(MockResponse(code = 404))
        assertNull(c.entity("Q999999999"))
        assertEquals("/wiki/Special:EntityData/Q999999999.json", server.takeRequest().url.encodedPath)
        assertTrue(runCatching { c.entity("drop table") }.isFailure, "only QIDs reach the URL")
    }

    @Test
    fun `overpass points of interest keep named nodes with their tag`() {
        val pois = OverpassClient.parsePois(fixture("overpass_chalten_pois.json"))
        assertEquals(2, pois.size, "the unnamed memorial is dropped")
        val peak = pois.single { it.tag == "natural" }
        assertEquals("Cerro Paredón", peak.name)
        assertTrue(peak.lat < -49 && peak.lon < -72)
        val q = OverpassClient.poiQuery(-49.331408, -72.886129, 300, 1_000)
        assertTrue(q.contains("around:300,-49.3314,-72.8861") && q.contains("natural=peak") && q.contains("around:1000"))
    }
}
