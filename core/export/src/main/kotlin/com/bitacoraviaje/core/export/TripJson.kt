package com.bitacoraviaje.core.export

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/**
 * `trip.json` (DISENO §12.2): the contract with the desktop renderer. Any shape change
 * bumps [SCHEMA_VERSION] and `docs/trip.schema.json`. Times carry both ISO 8601 with the
 * segment's offset and epoch milliseconds UTC.
 */
object TripJson {
    const val SCHEMA_VERSION = 1

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    @Serializable
    data class Doc(
        val schema_version: Int,
        val app_version: String,
        val exported_utc: String,
        val trip: TripDto,
        val segments: List<SegmentDto>,
        val places: List<PlaceDto> = emptyList(),
        val enrichments: List<EnrichmentDto> = emptyList(),
        val attributions: List<AttributionDto>,
    )

    @Serializable
    data class TripDto(
        val id: String,
        val title: String,
        val description: String?,
        val status: String,
        val started: String?,
        val started_utc_ms: Long?,
        val ended: String?,
        val ended_utc_ms: Long?,
        val cover_media_id: String?,
        val tz_id: String,
    )

    @Serializable
    data class SegmentDto(
        val id: String,
        val parent_segment_id: String?,
        val type: String,
        val transport: String?,
        val title: String?,
        val intent_text: String?,
        val auto_name: String? = null,
        val started: String,
        val started_utc_ms: Long,
        val ended: String?,
        val ended_utc_ms: Long?,
        val tz_id: String,
        val sampling_profile: String,
        val recording_mode: String,
        val auto_created: Boolean,
        val status: String,
        val stats: StatsDto,
        val points_file: String,
        val points: List<PointDto>,
        val pauses: List<PauseDto>,
        val stops: List<StopDto>,
        val media: List<MediaDto>,
        val voice_notes: List<VoiceNoteDto>,
        val text_notes: List<TextNoteDto>,
    )

    @Serializable
    data class StatsDto(
        val distance_m: Double?,
        val moving_time_ms: Long?,
        val total_time_ms: Long?,
        val max_speed_mps: Double?,
        val avg_moving_speed_mps: Double?,
        val elevation_gain_m: Double?,
        val elevation_loss_m: Double?,
        val min_alt_m: Double?,
        val max_alt_m: Double?,
        val bbox: List<Double>?,
    )

    @Serializable
    data class PointDto(
        val t: String,
        val t_utc_ms: Long,
        val lat: Double,
        val lon: Double,
        val alt_m: Double?,
        val h_acc_m: Float?,
        val speed_mps: Float?,
        val bearing_deg: Float?,
        val filtered: Boolean,
    )

    @Serializable
    data class PauseDto(val id: String, val kind: String, val paused: String, val paused_utc_ms: Long, val resumed: String?, val resumed_utc_ms: Long?)

    @Serializable
    data class StopDto(
        val id: String,
        val start: String,
        val start_utc_ms: Long,
        val end: String,
        val end_utc_ms: Long,
        val lat: Double,
        val lon: Double,
        val radius_m: Double,
        val place_id: String?,
        val user_label: String?,
    )

    @Serializable
    data class MediaDto(
        val id: String,
        val kind: String,
        val source: String,
        val file: String?,
        val display_name: String?,
        val mime_type: String?,
        val width: Int?,
        val height: Int?,
        val duration_ms: Long?,
        val taken: String,
        val taken_utc_ms: Long,
        val taken_time_source: String,
        val lat: Double?,
        val lon: Double?,
        val link_method: String,
        val link_confidence: Double,
        val needs_review: Boolean,
        val camera: String?,
        val caption: String?,
        val favorite: Boolean,
        val excluded: Boolean,
    )

    @Serializable
    data class VoiceNoteDto(
        val id: String,
        val started: String,
        val started_utc_ms: Long,
        val duration_ms: Long,
        val lat: Double?,
        val lon: Double?,
        val file: String?,
        val transcript: String?,
        val transcript_status: String,
        val transcript_lang: String?,
        val transcript_engine: String?,
        val media_item_id: String?,
    )

    @Serializable
    data class TextNoteDto(val id: String, val kind: String, val time: String, val time_utc_ms: Long, val lat: Double?, val lon: Double?, val text: String?)

    @Serializable
    data class PlaceDto(
        val id: String, val name: String, val lat: Double, val lon: Double,
        val kind: String? = null, val country_code: String? = null, val admin1: String? = null, val admin2: String? = null, val locality: String? = null,
        val osm_type: String? = null, val osm_id: Long? = null, val wikidata_id: String? = null, val wikipedia_title: String? = null, val wikipedia_lang: String? = null,
        val source: String? = null,
        /** Which segments saw this place and how it ranked there. */
        val visits: List<PlaceVisitDto> = emptyList(),
    )

    @Serializable
    data class PlaceVisitDto(val segment_id: String, val first_seen: String, val first_seen_utc_ms: Long, val dwell_ms: Long?, val relevance: Double?)

    @Serializable
    data class EnrichmentDto(
        val place_id: String, val source: String, val license: String, val text: String,
        val id: String? = null, val lang: String? = null, val title: String? = null, val url: String? = null, val thumbnail_url: String? = null,
        val original_text: String? = null, val llm_rewritten: Boolean = false, val user_approved: Boolean = false,
    )

    @Serializable
    data class AttributionDto(val name: String, val license: String, val url: String)

    fun build(t: TripExport): Doc {
        val tz = t.tzId
        return Doc(
            schema_version = SCHEMA_VERSION,
            app_version = t.appVersion,
            exported_utc = Iso.utc(t.exportedUtcMs),
            trip = TripDto(
                id = t.trip.id, title = t.trip.title, description = t.trip.description, status = t.trip.status.name,
                started = t.trip.startedUtcMs?.let { Iso.local(it, tz) }, started_utc_ms = t.trip.startedUtcMs,
                ended = t.trip.endedUtcMs?.let { Iso.local(it, tz) }, ended_utc_ms = t.trip.endedUtcMs,
                cover_media_id = t.trip.coverMediaId, tz_id = tz,
            ),
            segments = t.segments.mapIndexed { i, se ->
                val s = se.segment
                val z = s.tzId
                SegmentDto(
                    id = s.id, parent_segment_id = s.parentSegmentId, type = s.type.name, transport = s.transport?.name, title = s.title, intent_text = s.intentText, auto_name = s.autoName,
                    started = Iso.local(s.startedUtcMs, z), started_utc_ms = s.startedUtcMs,
                    ended = s.endedUtcMs?.let { Iso.local(it, z) }, ended_utc_ms = s.endedUtcMs,
                    tz_id = z, sampling_profile = s.samplingProfile, recording_mode = s.recordingMode.name, auto_created = s.autoCreated, status = s.status.name,
                    stats = StatsDto(
                        s.stats.distanceM, s.stats.movingTimeMs, s.stats.totalTimeMs, s.stats.maxSpeedMps, s.stats.avgMovingSpeedMps,
                        s.stats.elevationGainM, s.stats.elevationLossM, s.stats.minAltM, s.stats.maxAltM,
                        s.stats.bbox?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
                    ),
                    points_file = "tracks/" + Names.segmentFile(i + 1, s.startedUtcMs, z, s.title, "gpx"),
                    points = se.points.filter { t.includeFilteredPoints || !it.isFilteredOut }.map { p ->
                        PointDto(Iso.local(p.timeUtcMs, z), p.timeUtcMs, p.lat, p.lon, p.altM, p.hAccuracyM, p.speedMps, p.bearingDeg, p.isFilteredOut)
                    },
                    pauses = se.pauses.map { p -> PauseDto(p.id, p.kind.name, Iso.local(p.pausedUtcMs, z), p.pausedUtcMs, p.resumedUtcMs?.let { Iso.local(it, z) }, p.resumedUtcMs) },
                    stops = se.stops.map { st -> StopDto(st.id, Iso.local(st.startUtcMs, z), st.startUtcMs, Iso.local(st.endUtcMs, z), st.endUtcMs, st.lat, st.lon, st.radiusM, st.placeId, st.userLabel) },
                    media = se.media.map { m ->
                        MediaDto(
                            id = m.id, kind = m.kind.name, source = m.source.name, file = t.mediaFiles[m.id], display_name = m.displayName, mime_type = m.mimeType,
                            width = m.width, height = m.height, duration_ms = m.durationMs, taken = Iso.local(m.takenUtcMs, z), taken_utc_ms = m.takenUtcMs,
                            taken_time_source = m.takenTimeSource.name, lat = m.linkedLat, lon = m.linkedLon, link_method = m.linkMethod.name, link_confidence = m.linkConfidence,
                            needs_review = m.needsReview, camera = listOfNotNull(m.cameraMake, m.cameraModel).joinToString(" ").ifBlank { null },
                            caption = m.userCaption, favorite = m.isFavorite, excluded = m.isExcluded,
                        )
                    },
                    voice_notes = se.voiceNotes.map { v ->
                        VoiceNoteDto(
                            id = v.id, started = Iso.local(v.startedUtcMs, z), started_utc_ms = v.startedUtcMs, duration_ms = v.durationMs, lat = v.lat, lon = v.lon,
                            file = t.audioFiles[v.id], transcript = v.displayText, transcript_status = v.transcriptStatus.name, transcript_lang = v.transcriptLang,
                            transcript_engine = v.transcriptEngine, media_item_id = v.mediaItemId,
                        )
                    },
                    text_notes = se.textNotes.map { n -> TextNoteDto(n.id, n.kind.name, Iso.local(n.timeUtcMs, z), n.timeUtcMs, n.lat, n.lon, n.text) },
                )
            },
            places = t.places.map { p ->
                PlaceDto(
                    id = p.id, name = p.name, lat = p.lat, lon = p.lon, kind = p.kind, country_code = p.countryCode, admin1 = p.admin1, admin2 = p.admin2, locality = p.locality,
                    osm_type = p.osmType, osm_id = p.osmId, wikidata_id = p.wikidataId, wikipedia_title = p.wikipediaTitle, wikipedia_lang = p.wikipediaLang, source = p.source.name,
                    visits = t.segmentPlaces.filter { it.placeId == p.id }.map { sp ->
                        val z = t.segments.firstOrNull { it.segment.id == sp.segmentId }?.segment?.tzId ?: tz
                        PlaceVisitDto(sp.segmentId, Iso.local(sp.firstSeenUtcMs, z), sp.firstSeenUtcMs, sp.dwellMs, sp.relevance)
                    },
                )
            },
            enrichments = t.enrichments.map { e ->
                EnrichmentDto(
                    place_id = e.placeId, source = e.sourceName, license = e.license ?: "", text = e.summary, id = e.id, lang = e.lang, title = e.title, url = e.sourceUrl,
                    thumbnail_url = e.thumbnailUrl, original_text = e.originalSummary, llm_rewritten = e.llmRewritten, user_approved = e.userApproved,
                )
            },
            attributions = t.attributions.map { AttributionDto(it.name, it.license, it.url) },
        )
    }
}

class TripJsonWriter : TripWriter {
    override val path = "trip.json"

    @OptIn(ExperimentalSerializationApi::class)
    override fun write(trip: TripExport, out: OutputStream) = TripJson.json.encodeToStream(TripJson.build(trip), out)
}
