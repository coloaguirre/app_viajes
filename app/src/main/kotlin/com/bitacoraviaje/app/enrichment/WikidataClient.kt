package com.bitacoraviaje.app.enrichment

import com.bitacoraviaje.core.geo.PlaceClass
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Structured facts about an entity (skill place-enrichment §4). Only literal values; nothing inferred. */
data class WikidataFacts(
    val qid: String,
    val label: String?,
    val description: String?,
    /** P31 instance-of QIDs, e.g. Q8502 (mountain). */
    val instanceOf: List<String>,
    val population: Long?,
    val populationYear: Int?,
    val elevationM: Double?,
    val inceptionYear: Int?,
    val unescoHeritage: Boolean,
    val countryQids: List<String>,
    val sitelinks: Int,
    /** Article title in the requested language's Wikipedia (`eswiki`), when any. */
    val wikipediaTitle: String?,
) {
    /** A broad class for the ranking radius and a readable kind for the place row. */
    val placeClass: PlaceClass get() = instanceOf.firstNotNullOfOrNull { KIND_BY_QID[it] }?.second ?: PlaceClass.POI
    val kind: String? get() = instanceOf.firstNotNullOfOrNull { KIND_BY_QID[it] }?.first

    /** Card lines ("Altitud: 3.406 m") in Spanish, from literal data only. */
    fun factLines(): List<String> {
        val out = ArrayList<String>()
        elevationM?.let { out += "Altitud: ${num(it)} m" }
        population?.let { out += "Población: ${num(it.toDouble())}" + (populationYear?.let { y -> " ($y)" } ?: "") }
        inceptionYear?.let { out += if (placeClass == PlaceClass.LOCALITY) "Fundación: $it" else "Creación: $it" }
        if (unescoHeritage) out += "Patrimonio de la Humanidad (UNESCO)"
        return out
    }

    companion object {
        /** P31 → (kind, class). Small on purpose: anything else is a point of interest. */
        val KIND_BY_QID: Map<String, Pair<String, PlaceClass>> = mapOf(
            "Q515" to ("city" to PlaceClass.LOCALITY), "Q1549591" to ("city" to PlaceClass.LOCALITY), "Q3957" to ("town" to PlaceClass.LOCALITY),
            "Q532" to ("village" to PlaceClass.LOCALITY), "Q486972" to ("settlement" to PlaceClass.LOCALITY), "Q15284" to ("municipality" to PlaceClass.LOCALITY),
            "Q3327873" to ("municipality" to PlaceClass.LOCALITY), "Q5084" to ("hamlet" to PlaceClass.LOCALITY), "Q2039348" to ("municipality" to PlaceClass.LOCALITY),
            "Q8502" to ("peak" to PlaceClass.NATURAL), "Q23397" to ("lake" to PlaceClass.NATURAL), "Q35666" to ("glacier" to PlaceClass.NATURAL),
            "Q46169" to ("national_park" to PlaceClass.NATURAL), "Q179049" to ("nature_reserve" to PlaceClass.NATURAL), "Q4022" to ("river" to PlaceClass.NATURAL),
            "Q39816" to ("valley" to PlaceClass.NATURAL), "Q34763" to ("peninsula" to PlaceClass.NATURAL), "Q23442" to ("island" to PlaceClass.NATURAL),
            "Q54050" to ("hill" to PlaceClass.NATURAL), "Q1200957" to ("lagoon" to PlaceClass.NATURAL), "Q12280" to ("bridge" to PlaceClass.POI),
            "Q33506" to ("museum" to PlaceClass.POI), "Q570116" to ("attraction" to PlaceClass.POI), "Q839954" to ("archaeological_site" to PlaceClass.POI),
            "Q4989906" to ("monument" to PlaceClass.POI), "Q16970" to ("church" to PlaceClass.POI), "Q22698" to ("park" to PlaceClass.POI),
            "Q1248784" to ("airport" to PlaceClass.POI), "Q55488" to ("railway_station" to PlaceClass.POI), "Q2319498" to ("landmark" to PlaceClass.POI),
        )
        const val UNESCO_WORLD_HERITAGE = "Q9259"

        fun num(v: Double): String = String.format(Locale.forLanguageTag("es-AR"), "%,.0f", v)
    }
}

/** `Special:EntityData/{QID}.json` (CC0). */
class WikidataClient @Inject constructor(private val http: OsmHttp) {
    var baseUrl: String = DEFAULT_BASE

    fun entity(qid: String, lang: String = "es"): WikidataFacts? {
        require(QID.matches(qid)) { "not a QID: $qid" }
        http.client.newCall(OsmHttp.get("${baseUrl.trimEnd('/')}/wiki/Special:EntityData/$qid.json")).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("Wikidata HTTP ${resp.code}")
            return parse(resp.body.string(), qid, lang)
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://www.wikidata.org"
        val QID = Regex("^Q[0-9]{1,12}$")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun claims(e: JsonObject, p: String): List<JsonObject> = e["claims"]?.jsonObject?.get(p)?.jsonArray?.mapNotNull { it.jsonObject["mainsnak"]?.jsonObject?.get("datavalue")?.jsonObject } ?: emptyList()
        private fun rank(e: JsonObject, p: String): List<Pair<JsonObject, JsonObject>> = e["claims"]?.jsonObject?.get(p)?.jsonArray?.mapNotNull { c -> c.jsonObject["mainsnak"]?.jsonObject?.get("datavalue")?.jsonObject?.let { it to c.jsonObject } } ?: emptyList()
        private fun itemId(dv: JsonObject) = dv["value"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        private fun amount(dv: JsonObject) = dv["value"]?.jsonObject?.get("amount")?.jsonPrimitive?.content?.toDoubleOrNull()
        private fun year(dv: JsonObject): Int? = dv["value"]?.jsonObject?.get("time")?.jsonPrimitive?.content?.let { Regex("^([+-]\\d{1,16})-").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        fun parse(body: String, qid: String, lang: String): WikidataFacts? {
            val e = json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject?.get(qid)?.jsonObject ?: return null
            // Population: the claim with the latest "point in time" (P585) qualifier wins.
            val pop = rank(e, "P1082").map { (dv, c) ->
                val t = c["qualifiers"]?.jsonObject?.get("P585")?.jsonArray?.firstOrNull()?.jsonObject?.get("datavalue")?.jsonObject?.let { year(it) }
                amount(dv) to t
            }.filter { it.first != null }.maxWithOrNull(compareBy { it.second ?: Int.MIN_VALUE })
            val elev = claims(e, "P2044").firstNotNullOfOrNull { dv -> amount(dv)?.takeIf { dv["value"]?.jsonObject?.get("unit")?.jsonPrimitive?.content?.endsWith("Q11573") == true } }
            fun label(field: String) = e[field]?.jsonObject?.let { it[lang]?.jsonObject ?: it["en"]?.jsonObject }?.get("value")?.jsonPrimitive?.content
            return WikidataFacts(
                qid = qid,
                label = label("labels"),
                description = label("descriptions"),
                instanceOf = claims(e, "P31").mapNotNull(::itemId),
                population = pop?.first?.toLong(),
                populationYear = pop?.second,
                elevationM = elev,
                inceptionYear = claims(e, "P571").firstNotNullOfOrNull(::year),
                unescoHeritage = claims(e, "P1435").mapNotNull(::itemId).contains(UNESCO_WORLD_HERITAGE),
                countryQids = claims(e, "P17").mapNotNull(::itemId),
                sitelinks = e["sitelinks"]?.jsonObject?.size ?: 0,
                wikipediaTitle = e["sitelinks"]?.jsonObject?.get("${lang}wiki")?.jsonObject?.get("title")?.jsonPrimitive?.content,
            )
        }

        private const val UNESCO_WORLD_HERITAGE = WikidataFacts.UNESCO_WORLD_HERITAGE
    }
}
