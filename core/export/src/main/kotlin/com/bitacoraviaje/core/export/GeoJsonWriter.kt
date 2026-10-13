package com.bitacoraviaje.core.export

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.put

/** RFC 7946 FeatureCollection: a LineString per segment, a Point per event; coordinates [lon, lat, alt]. */
class GeoJsonWriter(override val path: String = "tracks/viaje.geojson") : TripWriter {
    @OptIn(ExperimentalSerializationApi::class)
    override fun write(trip: TripExport, out: OutputStream) {
        val features = ArrayList<JsonElement>()
        trip.segments.forEach { se ->
            val s = se.segment
            val z = s.tzId
            val pts = se.points.filter { trip.includeFilteredPoints || !it.isFilteredOut }
            if (pts.size >= 2) {
                features += feature(
                    geometry("LineString", buildJsonArray { pts.forEach { p -> add(pos(p.lon, p.lat, p.altM)) } }),
                    mapOf(
                        "kind" to JsonPrimitive("track"), "segment_id" to JsonPrimitive(s.id), "type" to JsonPrimitive(s.type.name),
                        "transport" to (s.transport?.let { JsonPrimitive(it.name) } ?: JsonNull), "name" to (s.title?.let { JsonPrimitive(it) } ?: JsonNull),
                        "started" to JsonPrimitive(Iso.local(s.startedUtcMs, z)), "ended" to (s.endedUtcMs?.let { JsonPrimitive(Iso.local(it, z)) } ?: JsonNull),
                        "distance_m" to (s.stats.distanceM?.let { JsonPrimitive(it) } ?: JsonNull), "moving_time_ms" to (s.stats.movingTimeMs?.let { JsonPrimitive(it) } ?: JsonNull),
                        "elevation_gain_m" to (s.stats.elevationGainM?.let { JsonPrimitive(it) } ?: JsonNull),
                    ),
                )
            }
            se.media.forEach { m ->
                val la = m.linkedLat ?: return@forEach
                val lo = m.linkedLon ?: return@forEach
                features += feature(geometry("Point", pos(lo, la, null)), mapOf("kind" to JsonPrimitive(if (m.kind.name == "VIDEO") "video" else "photo"), "id" to JsonPrimitive(m.id), "time" to JsonPrimitive(Iso.local(m.takenUtcMs, z)), "file" to (trip.mediaFiles[m.id]?.let { JsonPrimitive(it) } ?: JsonNull), "caption" to (m.userCaption?.let { JsonPrimitive(it) } ?: JsonNull)))
            }
            se.voiceNotes.forEach { v ->
                val la = v.lat ?: return@forEach
                val lo = v.lon ?: return@forEach
                features += feature(geometry("Point", pos(lo, la, null)), mapOf("kind" to JsonPrimitive("voice"), "id" to JsonPrimitive(v.id), "time" to JsonPrimitive(Iso.local(v.startedUtcMs, z)), "text" to (v.displayText?.let { JsonPrimitive(it) } ?: JsonNull), "file" to (trip.audioFiles[v.id]?.let { JsonPrimitive(it) } ?: JsonNull)))
            }
            se.textNotes.forEach { n ->
                val la = n.lat ?: return@forEach
                val lo = n.lon ?: return@forEach
                features += feature(geometry("Point", pos(lo, la, null)), mapOf("kind" to JsonPrimitive(n.kind.name.lowercase()), "id" to JsonPrimitive(n.id), "time" to JsonPrimitive(Iso.local(n.timeUtcMs, z)), "text" to (n.text?.let { JsonPrimitive(it) } ?: JsonNull)))
            }
            se.stops.forEach { st ->
                features += feature(geometry("Point", pos(st.lon, st.lat, null)), mapOf("kind" to JsonPrimitive("stop"), "id" to JsonPrimitive(st.id), "start" to JsonPrimitive(Iso.local(st.startUtcMs, z)), "end" to JsonPrimitive(Iso.local(st.endUtcMs, z)), "radius_m" to JsonPrimitive(st.radiusM), "label" to (st.userLabel?.let { JsonPrimitive(it) } ?: JsonNull)))
            }
        }
        val doc = buildJsonObject {
            put("type", "FeatureCollection")
            put("name", trip.trip.title)
            put("features", JsonArray(features))
        }
        TripJson.json.encodeToStream(doc, out)
    }

    private fun pos(lon: Double, lat: Double, alt: Double?): JsonArray = buildJsonArray {
        add(JsonPrimitive(lon))
        add(JsonPrimitive(lat))
        if (alt != null) add(JsonPrimitive(alt))
    }

    private fun geometry(type: String, coords: JsonElement) = buildJsonObject {
        put("type", type)
        put("coordinates", coords)
    }

    private fun feature(geometry: JsonElement, props: Map<String, JsonElement>) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
    }
}
