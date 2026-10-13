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

/** Overpass trails near a track (skill place-enrichment §5): named paths/footways/tracks and hiking relations. */
class OverpassClient @Inject constructor(private val http: OsmHttp) {
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
