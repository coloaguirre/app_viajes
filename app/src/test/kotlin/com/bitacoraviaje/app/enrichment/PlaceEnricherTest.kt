package com.bitacoraviaje.app.enrichment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.export.GpxReader
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.mapper.toEntity
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.PlacesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PLAN 4.1 acceptance: the sample trek (tools/sample-trips/trekking-sendero) with one stop
 * and a photo produces ≤ 5 ranked places, each text with its source and license, from
 * recorded answers only. Also: idempotence, privacy rounding and partial failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceEnricherTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var server: MockWebServer
    private lateinit var tracking: TrackingRepository
    private lateinit var places: PlacesRepository
    private lateinit var prefs: UserPreferences
    private lateinit var enricher: PlaceEnricher
    private lateinit var segmentId: String
    private val requests = ArrayList<RecordedRequest>()
    private var failOverpass = false
    private var failAll = false

    private fun fixture(name: String) = javaClass.getResourceAsStream("/enrichment/$name")!!.readBytes().toString(Charsets.UTF_8)

    @BeforeTest
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { NOW })
        places = PlacesRepository(db)
        prefs = UserPreferences(context)
        // The preferences DataStore delegate is a process singleton: reset what other cases may have left.
        prefs.setEnrichPlaces(true)
        prefs.setRoundEnrichmentCoords(false)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                if (failAll) return MockResponse(code = 500) // not a retry code: fails fast
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/reverse") -> MockResponse(body = fixture(if (request.url.queryParameter("zoom") == "16") "nominatim_reverse_z16_road.json" else "nominatim_reverse_z14.json"))
                    path.endsWith("/w/api.php") && request.url.queryParameter("list") == "geosearch" -> MockResponse(body = fixture(if (path.startsWith("/es/")) "wikipedia_geosearch_chalten.json" else "wikipedia_geosearch_empty_en.json"))
                    path.contains("/api/rest_v1/page/summary/") -> MockResponse(body = fixture(if (path.endsWith("Monte_Fitz_Roy")) "wikipedia_summary_fitzroy.json" else "wikipedia_summary_chalten.json"))
                    path.contains("/Special:EntityData/Q1323978") -> MockResponse(body = fixture("wikidata_Q1323978_chalten.json"))
                    path.contains("/Special:EntityData/Q215138") -> MockResponse(body = fixture("wikidata_Q215138_fitzroy.json"))
                    path.endsWith("/api/interpreter") -> if (failOverpass) MockResponse(code = 504) else MockResponse(body = fixture("overpass_chalten_pois.json"))
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
        val http = OsmHttp(context)
        val base = server.url("/").toString().trimEnd('/')
        // Settings accept http only for loopback hosts: exactly what a MockWebServer is.
        prefs.setNominatimUrl(base)
        prefs.setOverpassUrl("$base/api/interpreter")
        val nominatim = NominatimClient(http)
        val wikipedia = WikipediaClient(http).apply { baseUrlTemplate = "$base/{lang}" }
        val wikidata = WikidataClient(http).apply { baseUrl = base }
        val overpass = OverpassClient(http)
        enricher = PlaceEnricher(tracking, MediaRepository(db), NotesRepository(db), places, nominatim, wikipedia, wikidata, overpass, prefs, Clock { NOW })

        val gpx = javaClass.getResourceAsStream("/trekking-sendero/track.gpx")!!
        val t = GpxReader.read(gpx, "track.gpx", "America/Argentina/Buenos_Aires").trip
        tracking.importTrip(t.trip, t.segments.map { it.segment }, t.segments.associate { it.segment.id to it.points }, emptyList(), emptyList())
        segmentId = t.segments.single().segment.id
        val tripId = t.trip.id
        val p0 = t.segments.single().points.first()
        // A 40-minute stop in the village and a photo taken there.
        db.stopDao().insertAll(listOf(Stop("stop-1", segmentId, p0.timeUtcMs, p0.timeUtcMs + 40 * 60_000L, -49.3315, -72.8861, 30.0).toEntity()))
        MediaRepository(db).upsertAll(
            listOf(
                MediaItem(
                    id = "m1", tripId = tripId, segmentId = segmentId, kind = MediaKind.PHOTO, source = MediaSource.MEDIASTORE, contentUri = "content://x/1", mediaStoreId = 1,
                    takenUtcMs = p0.timeUtcMs + 60_000, takenTimeSource = TakenTimeSource.EXIF_WITH_OFFSET, linkedLat = -49.3316, linkedLon = -72.8860, linkMethod = LinkMethod.EXIF, linkConfidence = 1.0,
                ),
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `the sample trek yields at most five ranked places with sourced texts and is idempotent`() = runTest {
        val o = assertNotNull(enricher.enrich(segmentId))
        assertTrue(!o.skipped && o.placesFound in 1..5, "found=${o.placesFound} outcome=$o requests=${synchronized(requests) { requests.map { it.url.toString() } }} settings=${prefs.settings.first().nominatimUrl}")
        val found = places.placesOf(segmentId)
        assertEquals(o.placesFound, found.size)
        val top = found.first()
        assertEquals("El Chaltén", top.name, "the stop with the photo and the famous article ranks first")
        assertEquals("wd-Q1323978", top.id)
        assertEquals(PlaceSource.WIKIPEDIA, top.source)
        val texts = places.enrichmentsOf(top.id)
        val wiki = texts.single { it.sourceName == Enrichment.SOURCE_WIKIPEDIA }
        assertEquals(Enrichment.LICENSE_CC_BY_SA, wiki.license)
        assertTrue(wiki.sourceUrl.startsWith("https://es.wikipedia.org/wiki/"), wiki.sourceUrl)
        assertTrue(wiki.summary.length <= PlaceEnricher.SUMMARY_MAX_CHARS && wiki.summary.endsWith("."), wiki.summary)
        val facts = texts.single { it.sourceName == Enrichment.SOURCE_WIKIDATA }
        assertEquals(Enrichment.LICENSE_CC0, facts.license)
        assertTrue(facts.summary.contains("Altitud: 397 m"))
        val links = places.segmentPlacesOfTrip(tracking.getSegment(segmentId)!!.tripId)
        assertTrue(links.zipWithNext().all { (a, b) -> (a.relevance ?: 0.0) >= (b.relevance ?: 0.0) } || links.size <= 1)
        assertEquals(40 * 60_000L, links.first { it.placeId == top.id }.dwellMs)
        assertTrue(found.any { it.source == PlaceSource.OVERPASS && it.kind == "peak" }, "Overpass peak kept as a place without text: ${found.map { it.name to it.source }}")
        // Nothing more precise than 4 decimals left the phone.
        synchronized(requests) {
            assertTrue(requests.isNotEmpty())
            val coords = requests.flatMap { r -> Regex("-?\\d+\\.\\d+").findAll(r.url.toString() + (r.body?.utf8() ?: "")).map { it.value }.toList() }
            assertTrue(coords.all { it.substringAfter('.').length <= 4 }, "coords: $coords")
        }
        val before = synchronized(requests) { requests.size }
        val again = assertNotNull(enricher.enrich(segmentId))
        assertTrue(again.skipped)
        assertEquals(before, synchronized(requests) { requests.size }, "a segment with places is not fetched again")
        val forced = assertNotNull(enricher.enrich(segmentId, force = true))
        assertTrue(!forced.skipped && forced.placesFound == o.placesFound)
    }

    @Test
    fun `privacy rounding sends two decimals and a busy overpass gives a partial result instead of nothing`() = runTest {
        prefs.setRoundEnrichmentCoords(true)
        failOverpass = true
        val o = assertNotNull(enricher.enrich(segmentId))
        assertTrue(o.partial && o.placesFound >= 1)
        synchronized(requests) {
            val coords = requests.filter { !it.url.encodedPath.endsWith("/api/interpreter") }.flatMap { r -> Regex("-?\\d+\\.\\d+").findAll(r.url.toString()).map { it.value }.toList() }
            assertTrue(coords.isNotEmpty() && coords.all { it.substringAfter('.').length <= 2 }, "coords: $coords")
        }
        assertTrue(places.placesOf(segmentId).none { it.source == PlaceSource.OVERPASS })
    }

    @Test
    fun `when no source answers nothing is written and the error propagates for a retry`() = runTest {
        failAll = true
        val e = runCatching { enricher.enrich(segmentId) }.exceptionOrNull()
        assertTrue(e is java.io.IOException, "got $e")
        assertTrue(!places.hasPlaces(segmentId))
    }

    @Test
    fun `disabled in settings does nothing`() = runTest {
        prefs.setEnrichPlaces(false)
        val o = assertNotNull(enricher.enrich(segmentId))
        assertTrue(o.skipped && o.placesFound == 0)
        assertEquals(0, synchronized(requests) { requests.size })
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
    }
}
