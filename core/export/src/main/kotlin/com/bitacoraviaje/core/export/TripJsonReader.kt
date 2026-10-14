package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.BoundingBox
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.model.WeatherSample
import java.util.UUID
import kotlinx.serialization.json.Json

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `trip.json` back into domain objects (DISENO §12.5). Lenient on unknown keys so a
 * file from a newer minor revision still loads; [schema_version] must match. With
 * [regenerateIds] every id is replaced (import as a copy next to the original).
 */
object TripJsonReader {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun read(text: String, regenerateIds: Boolean = false): TripExport {
        val doc = try {
            json.decodeFromString(TripJson.Doc.serializer(), text)
        } catch (e: Exception) {
            throw ImportException("trip.json inválido: ${e.message}", e)
        }
        if (doc.schema_version != TripJson.SCHEMA_VERSION) throw ImportException("schema_version ${doc.schema_version} no soportada (esperada ${TripJson.SCHEMA_VERSION})")
        val ids = HashMap<String, String>()
        // Every id is validated whether or not it is regenerated: ids become path components (review B1).
        fun id(old: String): String = if (!regenerateIds) SafeId.require(old) else ids.getOrPut(SafeId.require(old)) { UUID.randomUUID().toString() }
        val tripId = id(doc.trip.id)
        val trip = Trip(
            id = tripId, title = doc.trip.title, description = doc.trip.description, createdUtcMs = doc.trip.started_utc_ms ?: doc.segments.minOfOrNull { it.started_utc_ms } ?: 0L,
            startedUtcMs = doc.trip.started_utc_ms, endedUtcMs = doc.trip.ended_utc_ms, coverMediaId = doc.trip.cover_media_id?.let(::id),
            status = enumOr(doc.trip.status, TripStatus.FINISHED),
        )
        val mediaFiles = HashMap<String, String>()
        val audioFiles = HashMap<String, String>()
        val segments = doc.segments.map { s ->
            val sid = id(s.id)
            val segment = Segment(
                id = sid, tripId = tripId, parentSegmentId = s.parent_segment_id?.let(::id), type = enumOr(s.type, SegmentType.STAGE), transport = s.transport?.let { enumOrNull<Transport>(it) },
                title = s.title, intentText = s.intent_text, autoName = s.auto_name, startedUtcMs = s.started_utc_ms, endedUtcMs = s.ended_utc_ms, tzId = s.tz_id, samplingProfile = s.sampling_profile,
                recordingMode = enumOr(s.recording_mode, RecordingMode.FULL), autoCreated = s.auto_created, status = enumOr(s.status, SegmentStatus.FINISHED),
                stats = SegmentStats(
                    s.stats.distance_m, s.stats.moving_time_ms, s.stats.total_time_ms, s.stats.max_speed_mps, s.stats.avg_moving_speed_mps, s.stats.elevation_gain_m, s.stats.elevation_loss_m,
                    s.stats.min_alt_m, s.stats.max_alt_m, s.stats.bbox?.takeIf { it.size == 4 }?.let { BoundingBox(minLat = it[1], minLon = it[0], maxLat = it[3], maxLon = it[2]) },
                ),
            )
            val points = s.points.map { p -> TrackPoint(segmentId = sid, timeUtcMs = p.t_utc_ms, lat = p.lat, lon = p.lon, altM = p.alt_m, hAccuracyM = p.h_acc_m, speedMps = p.speed_mps, bearingDeg = p.bearing_deg, isFilteredOut = p.filtered) }
            val pauses = s.pauses.map { p -> SegmentPause(id(p.id), sid, enumOr(p.kind, PauseKind.USER), p.paused_utc_ms, p.resumed_utc_ms) }
            val stops = s.stops.map { st -> Stop(id(st.id), sid, st.start_utc_ms, st.end_utc_ms, st.lat, st.lon, st.radius_m, st.place_id, st.user_label) }
            val media = s.media.map { m ->
                val mid = id(m.id)
                m.file?.let { mediaFiles[mid] = it }
                val (make, model) = m.camera?.split(' ', limit = 2).let { (it?.getOrNull(0)) to (it?.getOrNull(1)) }
                MediaItem(
                    id = mid, tripId = tripId, segmentId = sid, kind = enumOr(m.kind, MediaKind.PHOTO), source = MediaSource.IMPORT, contentUri = null, mediaStoreId = null,
                    displayName = m.display_name, mimeType = m.mime_type, width = m.width, height = m.height, durationMs = m.duration_ms,
                    takenUtcMs = m.taken_utc_ms, takenTimeSource = enumOr(m.taken_time_source, TakenTimeSource.MEDIASTORE), cameraMake = make, cameraModel = model,
                    linkedLat = m.lat, linkedLon = m.lon, linkMethod = enumOr(m.link_method, LinkMethod.NONE), linkConfidence = m.link_confidence, needsReview = m.needs_review,
                    userCaption = m.caption, isFavorite = m.favorite, isExcluded = m.excluded,
                )
            }
            val voice = s.voice_notes.map { v ->
                val vid = id(v.id)
                v.file?.let { audioFiles[vid] = it }
                VoiceNote(
                    id = vid, segmentId = sid, startedUtcMs = v.started_utc_ms, durationMs = v.duration_ms, lat = v.lat, lon = v.lon, audioPath = "audio/$tripId/$vid.m4a",
                    transcript = v.transcript, transcriptLang = v.transcript_lang, transcriptEngine = v.transcript_engine, transcriptStatus = enumOr(v.transcript_status, TranscriptStatus.PENDING),
                    mediaItemId = v.media_item_id?.let(::id),
                )
            }
            val text = s.text_notes.map { n -> TextNote(id(n.id), sid, n.time_utc_ms, n.lat, n.lon, enumOr(n.kind, TextNoteKind.NOTE), n.text) }
            val weather = s.weather.map { w -> WeatherSample(id(w.id), sid, w.time_utc_ms, w.lat, w.lon, w.temperature_c, w.apparent_c, w.precipitation_mm, w.wind_kmh, w.cloud_pct, w.weather_code, w.source) }
            SegmentExport(segment, points, pauses, stops, media, voice, text, weather)
        }
        // Places keep their ids on a copy too: `wd-Q…` is the same place in every trip (validated like every id).
        val segIds = segments.map { it.segment.id }.toSet()
        val places = doc.places.map { p ->
            Place(
                id = SafeId.require(p.id), name = p.name, lat = p.lat, lon = p.lon, kind = p.kind, countryCode = p.country_code, admin1 = p.admin1, admin2 = p.admin2, locality = p.locality,
                osmType = p.osm_type, osmId = p.osm_id, wikidataId = p.wikidata_id, wikipediaTitle = p.wikipedia_title, wikipediaLang = p.wikipedia_lang,
                source = p.source?.let { enumOrNull<PlaceSource>(it) } ?: PlaceSource.MANUAL, fetchedUtcMs = null,
            )
        }
        val placeIds = places.map { it.id }.toSet()
        val segmentPlaces = doc.places.flatMap { p -> p.visits.map { v -> SegmentPlace(id(v.segment_id), SafeId.require(p.id), v.first_seen_utc_ms, v.dwell_ms, v.relevance) } }
            .filter { it.segmentId in segIds }
        val enrichments = doc.enrichments.filter { it.place_id in placeIds }.map { e ->
            val pid = SafeId.require(e.place_id)
            Enrichment(
                id = SafeId.require(e.id ?: "$pid-${e.source.lowercase()}"), placeId = pid, lang = e.lang ?: "es", title = e.title, summary = e.text, sourceName = e.source,
                sourceUrl = e.url ?: "", license = e.license.ifBlank { null }, thumbnailUrl = e.thumbnail_url, originalSummary = e.original_text, llmRewritten = e.llm_rewritten, userApproved = e.user_approved, fetchedUtcMs = 0L,
            )
        }
        return TripExport(
            trip = trip, segments = segments, appVersion = doc.app_version, exportedUtcMs = runCatching { java.time.Instant.parse(doc.exported_utc).toEpochMilli() }.getOrDefault(0L),
            mediaFiles = mediaFiles, audioFiles = audioFiles, places = places, enrichments = enrichments, segmentPlaces = segmentPlaces,
            attributions = doc.attributions.map { Attribution(it.name, it.license, it.url) },
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
}
