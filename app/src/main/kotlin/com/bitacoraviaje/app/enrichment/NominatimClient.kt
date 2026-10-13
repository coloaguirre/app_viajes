package com.bitacoraviaje.app.enrichment

import com.bitacoraviaje.core.geo.PlaceSample
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of `/reverse` reduced to what the trip needs. */
data class ReverseGeocode(
    val name: String?, val displayName: String?, val road: String?, val locality: String?, val state: String?, val countryCode: String?,
    val osmType: String?, val osmId: Long?, val wikidata: String?,
    /** Centre of the matched object (not the query point). */
    val lat: Double? = null, val lon: Double? = null,
    /** OSM `category`/`type` of the match, e.g. place/town, highway/residential, tourism/viewpoint. */
    val category: String? = null, val type: String? = null, val county: String? = null,
) {
    fun toSample() = PlaceSample(road, locality)

    /** Deterministic place id from the OSM object (`osm-N123`), null when Nominatim gave none. */
    val osmPlaceId: String? get() = osmId?.let { "osm-${osmType?.firstOrNull()?.uppercaseChar() ?: 'X'}$it" }
}

/** Nominatim reverse geocoding (skill place-enrichment §1). Coordinates rounded to 4 decimals (~10 m) in the URL. */
class NominatimClient @Inject constructor(private val http: OsmHttp) {
    fun reverse(baseUrl: String, lat: Double, lon: Double, zoom: Int): ReverseGeocode {
        val url = "${baseUrl.trimEnd('/')}/reverse?format=jsonv2&lat=${fmt(lat)}&lon=${fmt(lon)}&zoom=$zoom&accept-language=es&extratags=1"
        http.client.newCall(OsmHttp.get(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Nominatim HTTP ${r.code}")
            return parse(r.body.string())
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** At most 4 decimals (~10 m); trailing zeros dropped so a privacy-rounded value stays visibly coarse. */
        fun fmt(v: Double): String = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        fun parse(body: String): ReverseGeocode {
            val o = json.parseToJsonElement(body).jsonObject
            o["error"]?.let { throw IOException("Nominatim: ${it.jsonPrimitive.content}") }
            val address = o["address"]?.jsonObject
            fun a(k: String) = address?.get(k)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            // Settlement first; in open country (Patagonian steppe, for instance) the department or region is the only name.
            val locality = a("city") ?: a("town") ?: a("village") ?: a("hamlet") ?: a("municipality") ?: a("county") ?: a("state_district") ?: a("region")
            return ReverseGeocode(
                name = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                displayName = o["display_name"]?.jsonPrimitive?.content,
                road = a("road"), locality = locality, state = a("state"), countryCode = a("country_code"),
                osmType = o["osm_type"]?.jsonPrimitive?.content, osmId = o["osm_id"]?.jsonPrimitive?.content?.toLongOrNull(),
                wikidata = (o["extratags"] as? JsonObject)?.get("wikidata")?.jsonPrimitive?.content?.takeIf { it.matches(Regex("^Q[0-9]+$")) },
                lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull(), lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                category = o["category"]?.jsonPrimitive?.content, type = o["type"]?.jsonPrimitive?.content, county = a("county"),
            )
        }
    }
}
