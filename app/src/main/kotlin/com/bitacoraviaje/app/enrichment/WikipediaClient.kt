package com.bitacoraviaje.app.enrichment

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia over the Action API (GeoSearch, and extracts as fallback) and the REST v1
 * summary endpoint (skill place-enrichment §2-3). Base URL is a template with `{lang}`
 * so tests point it at a MockWebServer and the enricher can switch to `en`.
 */
class WikipediaClient @Inject constructor(private val http: OsmHttp) : WikipediaSource {
    var baseUrlTemplate: String = DEFAULT_BASE

    private fun base(lang: String) = baseUrlTemplate.replace("{lang}", lang).trimEnd('/')

    override fun geoSearch(lang: String, lat: Double, lon: Double, radiusM: Int, limit: Int): List<GeoArticle> {
        val r = radiusM.coerceIn(10, 10_000)
        val url = "${base(lang)}/w/api.php?action=query&list=geosearch&gscoord=${NominatimClient.fmt(lat)}%7C${NominatimClient.fmt(lon)}&gsradius=$r&gslimit=${limit.coerceIn(1, 50)}&format=json&formatversion=2"
        http.client.newCall(OsmHttp.get(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Wikipedia geosearch HTTP ${resp.code}")
            return parseGeoSearch(resp.body.string())
        }
    }

    override fun summary(lang: String, title: String): ArticleSummary? {
        val encoded = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val restUrl = "${base(lang)}/api/rest_v1/page/summary/$encoded"
        val rest = http.client.newCall(OsmHttp.get(restUrl)).execute().use { resp ->
            when {
                resp.code == 404 -> return null
                resp.isSuccessful -> runCatching { parseRestSummary(resp.body.string(), lang) }.getOrNull()
                else -> null // REST v1 unavailable or being retired: fall through to the Action API
            }
        }
        if (rest != null) return rest
        val actionUrl = "${base(lang)}/w/api.php?action=query&prop=extracts%7Cpageprops%7Cpageimages%7Cinfo%7Ccoordinates&exintro=1&explaintext=1&exsentences=4&ppprop=wikibase_item%7Cdisambiguation&piprop=thumbnail&pithumbsize=320&inprop=url&titles=${URLEncoder.encode(title, "UTF-8")}&format=json&formatversion=2"
        http.client.newCall(OsmHttp.get(actionUrl)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Wikipedia extracts HTTP ${resp.code}")
            return parseActionSummary(resp.body.string(), lang)
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://{lang}.wikipedia.org"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        private fun JsonObject.dbl(k: String) = this[k]?.jsonPrimitive?.content?.toDoubleOrNull()

        fun parseGeoSearch(body: String): List<GeoArticle> {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.let { throw IOException("Wikipedia: ${(it as? JsonObject)?.str("info") ?: it}") }
            val arr = root["query"]?.jsonObject?.get("geosearch")?.jsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.jsonObject
                val title = o.str("title") ?: return@mapNotNull null
                GeoArticle(o["pageid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, title, o.dbl("lat") ?: return@mapNotNull null, o.dbl("lon") ?: return@mapNotNull null, o.dbl("dist") ?: 0.0)
            }.sortedBy { it.distM }
        }

        fun parseRestSummary(body: String, lang: String): ArticleSummary {
            val o = json.parseToJsonElement(body).jsonObject
            val coords = o["coordinates"]?.jsonObject
            return ArticleSummary(
                lang = o.str("lang") ?: lang,
                title = o.str("title") ?: "",
                extract = o.str("extract") ?: "",
                pageUrl = o["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.str("page") ?: "https://$lang.wikipedia.org/wiki/${URLEncoder.encode((o.str("title") ?: "").replace(' ', '_'), "UTF-8")}",
                wikidataId = o.str("wikibase_item"),
                thumbnailUrl = o["thumbnail"]?.jsonObject?.str("source"),
                lat = coords?.dbl("lat"), lon = coords?.dbl("lon"),
                type = o.str("type") ?: "standard",
            )
        }

        fun parseActionSummary(body: String, lang: String): ArticleSummary? {
            val root = json.parseToJsonElement(body).jsonObject
            val page = root["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            if (page["missing"] != null) return null
            val props = page["pageprops"]?.jsonObject
            val coords = page["coordinates"]?.jsonArray?.firstOrNull()?.jsonObject
            val title = page.str("title") ?: return null
            return ArticleSummary(
                lang = page.str("pagelanguage") ?: lang,
                title = title,
                extract = page.str("extract") ?: "",
                pageUrl = page.str("fullurl") ?: "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}",
                wikidataId = props?.str("wikibase_item"),
                thumbnailUrl = page["thumbnail"]?.jsonObject?.str("source"),
                lat = coords?.dbl("lat"), lon = coords?.dbl("lon"),
                type = if (props?.containsKey("disambiguation") == true) "disambiguation" else "standard",
            )
        }
    }
}
