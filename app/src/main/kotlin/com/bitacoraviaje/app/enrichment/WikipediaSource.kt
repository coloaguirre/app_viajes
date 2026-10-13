package com.bitacoraviaje.app.enrichment

/** A Wikipedia article near a coordinate (GeoSearch). */
data class GeoArticle(val pageId: Long, val title: String, val lat: Double, val lon: Double, val distM: Double)

/** The short text of an article and where it came from (DISENO §11.2 point 3). */
data class ArticleSummary(
    val lang: String,
    val title: String,
    val extract: String,
    val pageUrl: String,
    val wikidataId: String?,
    val thumbnailUrl: String?,
    val lat: Double?,
    val lon: Double?,
    /** `standard`, `disambiguation`, `no-extract`... disambiguations are useless as places. */
    val type: String,
) {
    val isUsable: Boolean get() = type != "disambiguation" && extract.isNotBlank()
}

/**
 * Abstraction over Wikipedia's HTTP APIs (PLAN 4.1): Wikimedia is migrating the REST v1
 * endpoints (`/api/rest_v1/page/summary`) towards the MediaWiki core REST API, so the
 * enricher only depends on this interface and the implementation can change behind it.
 */
interface WikipediaSource {
    /** Articles within [radiusM] (≤ 10 000 per the API) of the point, nearest first. */
    fun geoSearch(lang: String, lat: Double, lon: Double, radiusM: Int = 5_000, limit: Int = 10): List<GeoArticle>

    /** Plain-text summary of one article, or null when it does not exist. */
    fun summary(lang: String, title: String): ArticleSummary?
}
