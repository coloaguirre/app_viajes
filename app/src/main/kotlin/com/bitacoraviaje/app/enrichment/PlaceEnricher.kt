package com.bitacoraviaje.app.enrichment

import android.util.Log
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.geo.CoordinateRounding
import com.bitacoraviaje.core.geo.PlaceCandidate
import com.bitacoraviaje.core.geo.PlaceClass
import com.bitacoraviaje.core.geo.PlaceRanking
import com.bitacoraviaje.core.geo.haversineM
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.PlacesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * DISENO §11: for a finished segment, find the places worth telling about (stops, localities
 * crossed, peaks near the photos), fetch a short text with its license and keep the best
 * [PlaceRanking.DEFAULT_LIMIT] ranked. Idempotent: a segment that already has places is
 * skipped unless [enrich] is forced; the user's approvals survive a re-run (PlacesRepository).
 *
 * What leaves the phone: the stops and up to [SAMPLES] track samples, rounded to ~10 m
 * (or ~1 km with the privacy option), never the first and last [RouteNamer.TRIM_M] metres.
 * Photos and notes stay on the phone: they only weigh the ranking.
 */
class PlaceEnricher @Inject constructor(
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: PlacesRepository,
    private val nominatim: NominatimClient,
    private val wikipedia: WikipediaSource,
    private val wikidata: WikidataClient,
    private val overpass: OverpassClient,
    private val prefs: UserPreferences,
    private val clock: Clock,
) {
    data class Outcome(val placesFound: Int, val skipped: Boolean, val lookups: Int, val partial: Boolean)

    private data class Anchor(val lat: Double, val lon: Double, val timeUtcMs: Long, val dwellMs: Long, val stop: Stop?)

    private class Resolved(val proposal: Proposal, val facts: WikidataFacts?, val summary: ArticleSummary?, val candidate: PlaceCandidate)

    /** A place proposal before ranking: where it came from and how to fetch its text. */
    private data class Proposal(
        val name: String, val lat: Double, val lon: Double, val anchor: Anchor, val distM: Double,
        val qid: String?, val wikiTitle: String?, val wikiLang: String?, val osm: ReverseGeocode?, val poi: OsmPoi?,
        val placeClass: PlaceClass, val kind: String?,
    )

    suspend fun enrich(segmentId: String, force: Boolean = false): Outcome? {
        val segment = tracking.getSegment(segmentId) ?: return null
        val settings = prefs.settings.first()
        if (!settings.enrichPlaces) return Outcome(0, skipped = true, lookups = 0, partial = false)
        if (!force && places.hasPlaces(segmentId)) return Outcome(places.placesOf(segmentId).size, skipped = true, lookups = 0, partial = false)
        val all = tracking.validPoints(segmentId)
        val points = RouteNamer.trimEnds(all, RouteNamer.TRIM_M)
        if (points.size < RouteNamer.MIN_POINTS) return Outcome(0, skipped = false, lookups = 0, partial = false)
        val decimals = if (settings.roundEnrichmentCoords) PRIVACY_DECIMALS else DEFAULT_DECIMALS
        fun r(v: Double) = CoordinateRounding.round(v, decimals)
        val stops = tracking.stopsOf(segmentId).sortedByDescending { it.endUtcMs - it.startUtcMs }.take(MAX_STOPS)
        val mediaPts = media.allOfTrip(segment.tripId).filter { it.segmentId == segmentId && !it.isExcluded && it.linkedLat != null && it.linkedLon != null }.map { it.linkedLat!! to it.linkedLon!! }
        val notePts = (notes.textNotesOf(segmentId).mapNotNull { n -> n.lat?.let { la -> n.lon?.let { lo -> la to lo } } } + notes.voiceNotesOf(segmentId).mapNotNull { n -> n.lat?.let { la -> n.lon?.let { lo -> la to lo } } })

        val lang = LANG
        var lookups = 0
        var partial = false
        val proposals = ArrayList<Proposal>()
        val seenOsm = HashSet<String>()

        // 1. Stops: the place itself (Nominatim), articles around it (Wikipedia) and mapped points of interest (Overpass).
        for (st in stops) {
            val anchor = Anchor(st.lat, st.lon, st.startUtcMs, st.endUtcMs - st.startUtcMs, st)
            val rg = runCatching { lookups++; nominatim.reverse(settings.nominatimUrl, r(st.lat), r(st.lon), RouteNamer.ZOOM_ROAD) }.onFailure { partial = true; warn("nominatim stop", it) }.getOrNull()
            if (rg != null) {
                val name = rg.name ?: rg.road?.let { road -> rg.locality?.let { "$road, $it" } ?: road } ?: rg.locality
                if (name != null && rg.osmPlaceId != null && seenOsm.add(rg.osmPlaceId!!)) {
                    proposals += Proposal(name, rg.lat ?: st.lat, rg.lon ?: st.lon, anchor, 0.0, rg.wikidata, null, null, rg, null, classOf(rg), kindOf(rg))
                }
            }
            val near: List<Pair<GeoArticle, String>> = runCatching {
                lookups++
                val own = wikipedia.geoSearch(lang, r(st.lat), r(st.lon), STOP_RADIUS_M, GEOSEARCH_LIMIT)
                // No article in Spanish: try English and remember the language (DISENO §11.2).
                if (own.isNotEmpty()) own.map { it to lang } else { lookups++; wikipedia.geoSearch(FALLBACK_LANG, r(st.lat), r(st.lon), STOP_RADIUS_M, GEOSEARCH_LIMIT).map { it to FALLBACK_LANG } }
            }.onFailure { partial = true; warn("wikipedia geosearch", it) }.getOrDefault(emptyList())
            for ((a, l) in near) proposals += Proposal(a.title, a.lat, a.lon, anchor, a.distM, null, a.title, l, null, null, PlaceClass.POI, null)
            val pois = runCatching { lookups++; overpass.poisNear(settings.overpassUrl, r(st.lat), r(st.lon)) }.onFailure { partial = true; warn("overpass pois", it) }.getOrDefault(emptyList())
            for (p in pois) {
                if (!seenOsm.add("osm-${p.osmType.first().uppercaseChar()}${p.osmId}")) continue
                val cls = if (p.tag == "natural") PlaceClass.NATURAL else PlaceClass.POI
                proposals += Proposal(p.name, p.lat, p.lon, anchor, haversineM(st.lat, st.lon, p.lat, p.lon), p.wikidata, null, null, null, p, cls, if (p.tag == "natural") "peak" else p.value)
            }
        }

        // 2. Localities crossed: coarse reverse geocoding of equally spaced samples.
        val samples = RouteNamer.sample(points, SAMPLES)
        for (p in samples) {
            val rg = runCatching { lookups++; nominatim.reverse(settings.nominatimUrl, r(p.lat), r(p.lon), RouteNamer.ZOOM_REGION) }.onFailure { partial = true; warn("nominatim sample", it) }.getOrNull() ?: continue
            val id = rg.osmPlaceId ?: continue
            if (!seenOsm.add(id)) continue
            val name = rg.locality ?: rg.name ?: continue
            val anchor = Anchor(p.lat, p.lon, p.timeUtcMs, 0, null)
            val dist = if (rg.lat != null && rg.lon != null) haversineM(p.lat, p.lon, rg.lat, rg.lon) else 0.0
            proposals += Proposal(name, rg.lat ?: p.lat, rg.lon ?: p.lon, anchor, dist, rg.wikidata, null, null, rg, null, PlaceClass.LOCALITY, kindOf(rg) ?: "locality")
        }

        // 3. Resolve texts and facts (cached per QID/title within the run), then rank.
        val factsByQid = HashMap<String, WikidataFacts?>()
        val summaryByKey = HashMap<String, ArticleSummary?>()
        fun facts(qid: String): WikidataFacts? = factsByQid.getOrPut(qid) { runCatching { lookups++; wikidata.entity(qid, lang) }.onFailure { partial = true; warn("wikidata $qid", it) }.getOrNull() }
        fun summary(l: String, title: String): ArticleSummary? = summaryByKey.getOrPut("$l:$title") { runCatching { lookups++; wikipedia.summary(l, title) }.onFailure { partial = true; warn("wikipedia summary", it) }.getOrNull() }

        val resolved = ArrayList<Resolved>()
        for (pr in proposals) {
            if (lookups >= MAX_LOOKUPS) { partial = true; break }
            var sum: ArticleSummary? = pr.wikiTitle?.let { summary(pr.wikiLang ?: lang, it) }?.takeIf { it.isUsable }
            val qid = pr.qid ?: sum?.wikidataId
            val f = qid?.let(::facts)
            if (sum == null && f?.wikipediaTitle != null) sum = summary(lang, f.wikipediaTitle)?.takeIf { it.isUsable }
            val cls = f?.let { if (it.instanceOf.any { q -> q in WikidataFacts.KIND_BY_QID }) it.placeClass else null } ?: pr.placeClass
            val key = qid ?: pr.osm?.osmPlaceId ?: pr.poi?.let { "osm-${it.osmType.first().uppercaseChar()}${it.osmId}" } ?: sum?.let { "wp-${it.lang}-${it.title}" } ?: "name-${pr.name}"
            val radius = minOf(cls.maxDistM, NEARBY_M)
            val cand = PlaceCandidate(
                key = key, name = sum?.title ?: f?.label ?: pr.name, lat = pr.lat, lon = pr.lon, placeClass = cls, distM = pr.distM, dwellMs = pr.anchor.dwellMs,
                hasPhotos = mediaPts.any { (la, lo) -> haversineM(la, lo, pr.lat, pr.lon) <= radius },
                hasNotes = notePts.any { (la, lo) -> haversineM(la, lo, pr.lat, pr.lon) <= radius },
                sitelinks = f?.sitelinks ?: 0,
            )
            resolved += Resolved(pr, f, sum, cand)
        }
        val ranked = PlaceRanking.rank(resolved.map { it.candidate }, PlaceRanking.DEFAULT_LIMIT)
        val now = clock.nowUtcMs()
        val outPlaces = ArrayList<Place>()
        val outEnrich = ArrayList<Enrichment>()
        val outLinks = ArrayList<SegmentPlace>()
        for (rp in ranked) {
            val rs = resolved.filter { it.candidate.key == rp.candidate.key }
            val best = rs.minByOrNull { it.candidate.distM }!!
            val f = rs.firstNotNullOfOrNull { it.facts }
            val sum = rs.firstNotNullOfOrNull { it.summary }
            val qid = f?.qid ?: sum?.wikidataId ?: best.proposal.qid
            val placeId = placeId(qid, best.proposal, sum)
            val rg = rs.firstNotNullOfOrNull { it.proposal.osm }
            outPlaces += Place(
                id = placeId, name = rp.candidate.name, lat = rp.candidate.lat, lon = rp.candidate.lon, kind = f?.kind ?: best.proposal.kind,
                countryCode = rg?.countryCode, admin1 = rg?.state, admin2 = rg?.county, locality = rg?.locality,
                osmType = rg?.osmType ?: best.proposal.poi?.osmType, osmId = rg?.osmId ?: best.proposal.poi?.osmId,
                wikidataId = qid, wikipediaTitle = sum?.title, wikipediaLang = sum?.lang,
                source = when { sum != null -> PlaceSource.WIKIPEDIA; best.proposal.poi != null -> PlaceSource.OVERPASS; else -> PlaceSource.NOMINATIM },
                fetchedUtcMs = now,
            )
            if (sum != null) {
                outEnrich += Enrichment(
                    id = "$placeId-wikipedia-${sum.lang}", placeId = placeId, lang = sum.lang, title = sum.title, summary = trim(sum.extract),
                    sourceName = Enrichment.SOURCE_WIKIPEDIA, sourceUrl = sum.pageUrl, license = Enrichment.LICENSE_CC_BY_SA, thumbnailUrl = sum.thumbnailUrl, fetchedUtcMs = now,
                )
            }
            val lines = f?.factLines().orEmpty()
            if (f != null && lines.isNotEmpty()) {
                outEnrich += Enrichment(
                    id = "$placeId-wikidata", placeId = placeId, lang = lang, title = f.label, summary = lines.joinToString(" · "),
                    sourceName = Enrichment.SOURCE_WIKIDATA, sourceUrl = "https://www.wikidata.org/wiki/${f.qid}", license = Enrichment.LICENSE_CC0, fetchedUtcMs = now,
                )
            }
            val a = best.proposal.anchor
            outLinks += SegmentPlace(segmentId, placeId, a.timeUtcMs, a.dwellMs.takeIf { it > 0 }, rp.score)
            // The stop that found a Nominatim place gets it as its name (stop.place_id).
            a.stop?.takeIf { best.proposal.osm != null && best.proposal.distM == 0.0 }?.let { tracking.setStopPlace(it.id, placeId) }
        }
        places.replaceSegmentPlaces(segmentId, outPlaces, outEnrich, outLinks)
        Log.i(TAG, "places enriched segment=$segmentId found=${outPlaces.size} proposals=${proposals.size} lookups=$lookups partial=$partial")
        return Outcome(outPlaces.size, skipped = false, lookups = lookups, partial = partial)
    }

    /** `wd-Q…` when Wikidata knows it, else the OSM object, else the article: stable across runs and SafeId-valid. */
    private fun placeId(qid: String?, pr: Proposal, sum: ArticleSummary?): String =
        qid?.let { "wd-$it" } ?: pr.osm?.osmPlaceId ?: pr.poi?.let { "osm-${it.osmType.first().uppercaseChar()}${it.osmId}" } ?: "wp-${sum?.lang ?: "x"}-${slug(sum?.title ?: pr.name)}"

    private fun warn(what: String, e: Throwable) {
        if (e is IOException) Log.w(TAG, "$what failed: ${e.message}") else Log.w(TAG, "$what failed", e)
    }

    companion object {
        private const val TAG = "Bitacora"
        const val LANG = "es"
        const val FALLBACK_LANG = "en"
        const val MAX_STOPS = 6
        const val SAMPLES = 10
        const val STOP_RADIUS_M = 1_000
        const val GEOSEARCH_LIMIT = 5
        /** Photos/notes within this distance of a place count for its ranking. */
        const val NEARBY_M = 1_000.0
        /** Hard cap of HTTP calls per segment: the public instances are shared. */
        const val MAX_LOOKUPS = 45
        const val DEFAULT_DECIMALS = 4
        const val PRIVACY_DECIMALS = 2
        const val SUMMARY_MAX_CHARS = 600

        fun classOf(rg: ReverseGeocode): PlaceClass = when {
            rg.category == "place" && rg.type in setOf("city", "town", "village", "hamlet", "municipality", "suburb", "locality") -> PlaceClass.LOCALITY
            rg.category == "natural" -> PlaceClass.NATURAL
            else -> PlaceClass.POI
        }

        fun kindOf(rg: ReverseGeocode): String? = when (rg.category) {
            "place", "tourism", "historic", "natural", "leisure", "amenity" -> rg.type
            else -> null
        }

        /** Cut at a sentence end before [SUMMARY_MAX_CHARS]; the full article stays one tap away through the URL. */
        fun trim(text: String, max: Int = SUMMARY_MAX_CHARS): String {
            val t = text.replace(Regex("\\s*\\n+\\s*"), " ").trim()
            if (t.length <= max) return t
            val cut = t.lastIndexOf(". ", max).takeIf { it > max / 3 } ?: max
            return t.substring(0, cut + 1).trim()
        }

        fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "x" }
    }
}
