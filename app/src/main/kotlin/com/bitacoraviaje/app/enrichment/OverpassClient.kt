package com.bitacoraviaje.app.enrichment

import com.bitacoraviaje.core.geo.TrailCandidate
import com.bitacoraviaje.core.model.TrackPoint
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request

/** A named point of interest from OSM (tourism, historic, natural=peak) near a stop. */
data class OsmPoi(val osmType: String, val osmId: Long, val name: String, val tag: String, val value: String, val lat: Double, val lon: Double, val wikidata: String?)

/** Overpass trails near a track (skill place-enrichment §5): named paths/footways/tracks and hiking relations. */
class OverpassClient @Inject constructor(private val http: OsmHttp) {
    /** Named viewpoints, attractions, museums and monuments within [aroundM]; peaks within [peakAroundM]. */
    fun poisNear(baseUrl: String, lat: Double, lon: Double, aroundM: Int = 300, peakAroundM: Int = 1_000): List<OsmPoi> {
        val req = Request.Builder().url(baseUrl).post(FormBody.Builder().add("data", poiQuery(lat, lon, aroundM, peakAroundM)).build()).build()
        http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Overpass HTTP ${r.code}")
            val body = r.body.string()
            if (!body.trimStart().startsWith("{")) throw IOException("Overpass busy: non-JSON answer")
            return parsePois(body)
        }
    }

    fun trailsNear(baseUrl: String, simplified: List<TrackPoint>, aroundM: Int = 30): List<TrailCandidate> {
        val q = query(simplified, aroundM)
        val req = Request.Builder().url(baseUrl).post(FormBody.Builder().add("data", q).build()).build()
        http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Overpass HTTP ${r.code}")
            val body = r.body.string()
            if (!body.trimStart().startsWith("{")) throw IOException("Overpass busy: non-JSON answer")
            return parse(body)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `around` along the simplified polyline; long tracks are split so the query stays small. */
        fun query(simplified: List<TrackPoint>, aroundM: Int, maxVertices: Int = 150): String {
            val pts = if (simplified.size <= maxVertices) simplified else simplified.filterIndexed { i, _ -> i % ((simplified.size + maxVertices - 1) / maxVertices) == 0 } + simplified.last()
            // 4 decimals (~10 m): enough for around:30 and no more precision than needed (review I6).
            val line = pts.joinToString(",") { "${String.format(Locale.ROOT, "%.4f", it.lat)},${String.format(Locale.ROOT, "%.4f", it.lon)}" }
            return "[out:json][timeout:25];(way(around:$aroundM,$line)[highway~\"^(path|footway|track)$\"][name];relation(around:$aroundM,$line)[route=hiking][name];);out geom 40;"
        }

        /** Tolerant: a member without coordinates is skipped, not a crash (review M10). */
        private fun geometry(o: kotlinx.serialization.json.JsonObject): List<Pair<Double, Double>>? =
            o["geometry"]?.jsonArray?.mapNotNull { g ->
                val la = g.jsonObject["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val lo = g.jsonObject["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (la != null && lo != null) la to lo else null
            }?.takeIf { it.size >= 2 }

        fun poiQuery(lat: Double, lon: Double, aroundM: Int, peakAroundM: Int): String {
            val c = "${NominatimClient.fmt(lat)},${NominatimClient.fmt(lon)}"
            return "[out:json][timeout:25];(nwr(around:$aroundM,$c)[tourism~\"^(viewpoint|attraction|museum|artwork)$\"][name];nwr(around:$aroundM,$c)[historic][name];nwr(around:$peakAroundM,$c)[natural=peak][name];);out tags center 20;"
        }

        fun parsePois(body: String): List<OsmPoi> {
            val els = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
            return els.mapNotNull { e ->
                val o = e.jsonObject
                val tags = o["tags"]?.jsonObject ?: return@mapNotNull null
                val name = tags["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val center = o["center"]?.jsonObject ?: o
                val la = center["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lo = center["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val (tag, value) = listOf("tourism", "historic", "natural").firstNotNullOfOrNull { k -> tags[k]?.jsonPrimitive?.content?.let { k to it } } ?: return@mapNotNull null
                OsmPoi(o["type"]?.jsonPrimitive?.content ?: "node", o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null, name, tag, value, la, lo, tags["wikidata"]?.jsonPrimitive?.content)
            }
        }

        fun parse(body: String): List<TrailCandidate> {
            val els = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
            val out = ArrayList<TrailCandidate>()
            for (e in els) {
                val o = e.jsonObject
                val name = o["tags"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
                when (o["type"]?.jsonPrimitive?.content) {
                    "way" -> geometry(o)?.let { out += TrailCandidate(name, it) }
                    "relation" -> o["members"]?.jsonArray?.forEach { m -> geometry(m.jsonObject)?.let { out += TrailCandidate(name, it) } }
                }
            }
            return out
        }
    }
}
