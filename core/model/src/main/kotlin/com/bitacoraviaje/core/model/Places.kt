package com.bitacoraviaje.core.model

/** Where a [Place] row came from (DISENO §5.2 `place.source`). */
enum class PlaceSource { NOMINATIM, ANDROID_GEOCODER, WIKIPEDIA, OVERPASS, MANUAL }

/**
 * A named spot the trip touched: a locality crossed, a stop, a peak near a photo (DISENO §11).
 * Ids are deterministic per source (`wd-Q1323978`, `osm-N123`) so enrichment is idempotent
 * and stays valid as a path component on export (SafeId).
 */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** city | town | village | poi | peak | lake | park | museum | ... free text, see PlaceKind. */
    val kind: String? = null,
    val countryCode: String? = null,
    val admin1: String? = null,
    val admin2: String? = null,
    val locality: String? = null,
    val osmType: String? = null,
    val osmId: Long? = null,
    val wikidataId: String? = null,
    val wikipediaTitle: String? = null,
    val wikipediaLang: String? = null,
    val source: PlaceSource,
    val fetchedUtcMs: Long? = null,
)

/** One text about a [Place] from one source, with its license (DISENO §11.5). */
data class Enrichment(
    val id: String,
    val placeId: String,
    val lang: String,
    val title: String? = null,
    /** Short text for the diary and the video cards. */
    val summary: String,
    /** "Wikipedia", "Wikidata", ... */
    val sourceName: String,
    val sourceUrl: String,
    val license: String? = null,
    val thumbnailUrl: String? = null,
    /** Text as fetched from the source, when [summary] was edited or rewritten. */
    val originalSummary: String? = null,
    val llmRewritten: Boolean = false,
    /** The user accepted it for the video (DISENO §11.4). */
    val userApproved: Boolean = false,
    val fetchedUtcMs: Long,
) {
    companion object {
        const val SOURCE_WIKIPEDIA = "Wikipedia"
        const val SOURCE_WIKIDATA = "Wikidata"
        const val LICENSE_CC_BY_SA = "CC BY-SA 4.0"
        const val LICENSE_CC0 = "CC0"
    }
}

/** Which places a segment visited, with the ranking score that selected them (DISENO §11.3). */
data class SegmentPlace(
    val segmentId: String,
    val placeId: String,
    val firstSeenUtcMs: Long,
    val dwellMs: Long? = null,
    val relevance: Double? = null,
    /** The user discarded this place for the segment; kept as a tombstone so a refresh does not bring it back. */
    val dismissed: Boolean = false,
)
