package com.bitacoraviaje.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities mirror DISENO §5.2. Enums are stored as their names (TEXT) so the
 * schema stays readable in SQLite tools and in the exported trip.json.
 * Additions over the spec (adenda 1): segment.recording_mode, segment.auto_created,
 * segment_pause.kind, track_point.is_mock.
 */

@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    @ColumnInfo(name = "created_utc_ms") val createdUtcMs: Long,
    @ColumnInfo(name = "started_utc_ms") val startedUtcMs: Long?,
    @ColumnInfo(name = "ended_utc_ms") val endedUtcMs: Long?,
    @ColumnInfo(name = "cover_media_id") val coverMediaId: String?,
    val status: String,
)

@Entity(
    tableName = "segment",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["parent_segment_id"]),
    ],
    indices = [Index(value = ["trip_id", "started_utc_ms"], name = "idx_segment_trip"), Index("parent_segment_id")],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "parent_segment_id") val parentSegmentId: String?,
    val type: String,
    val transport: String?,
    val title: String?,
    @ColumnInfo(name = "intent_text") val intentText: String?,
    @ColumnInfo(name = "auto_name", defaultValue = "NULL") val autoName: String? = null,
    @ColumnInfo(name = "started_utc_ms") val startedUtcMs: Long,
    @ColumnInfo(name = "ended_utc_ms") val endedUtcMs: Long?,
    @ColumnInfo(name = "tz_id") val tzId: String,
    @ColumnInfo(name = "sampling_profile") val samplingProfile: String,
    @ColumnInfo(name = "recording_mode") val recordingMode: String,
    @ColumnInfo(name = "auto_created") val autoCreated: Boolean,
    val status: String,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "moving_time_ms") val movingTimeMs: Long?,
    @ColumnInfo(name = "total_time_ms") val totalTimeMs: Long?,
    @ColumnInfo(name = "max_speed_mps") val maxSpeedMps: Double?,
    @ColumnInfo(name = "avg_moving_speed_mps") val avgMovingSpeedMps: Double?,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "elevation_loss_m") val elevationLossM: Double?,
    @ColumnInfo(name = "min_alt_m") val minAltM: Double?,
    @ColumnInfo(name = "max_alt_m") val maxAltM: Double?,
    @ColumnInfo(name = "bbox_min_lat") val bboxMinLat: Double?,
    @ColumnInfo(name = "bbox_min_lon") val bboxMinLon: Double?,
    @ColumnInfo(name = "bbox_max_lat") val bboxMaxLat: Double?,
    @ColumnInfo(name = "bbox_max_lon") val bboxMaxLon: Double?,
)

@Entity(
    tableName = "segment_pause",
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["segment_id", "paused_utc_ms"], name = "idx_pause_segment")],
)
data class SegmentPauseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    val kind: String,
    @ColumnInfo(name = "paused_utc_ms") val pausedUtcMs: Long,
    @ColumnInfo(name = "resumed_utc_ms") val resumedUtcMs: Long?,
)

@Entity(
    tableName = "track_point",
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["segment_id", "time_utc_ms"], name = "idx_tp_segment_time")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "time_utc_ms") val timeUtcMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "alt_m") val altM: Double?,
    @ColumnInfo(name = "alt_msl_m") val altMslM: Double?,
    @ColumnInfo(name = "h_accuracy_m") val hAccuracyM: Float?,
    @ColumnInfo(name = "v_accuracy_m") val vAccuracyM: Float?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float?,
    val provider: String?,
    val activity: String?,
    @ColumnInfo(name = "is_mock") val isMock: Boolean,
    @ColumnInfo(name = "monotonic_ms") val monotonicMs: Long?,
    @ColumnInfo(name = "is_filtered_out") val isFilteredOut: Boolean,
    @ColumnInfo(name = "is_simplified_keep") val isSimplifiedKeep: Boolean,
)

/**
 * Clock offset the user confirmed for one camera ("Make Model") in a trip (DISENO §7.6 step 4).
 * Applied on every rescan so the correction survives the next media sweep.
 */
@Entity(
    tableName = "camera_clock_offset",
    primaryKeys = ["trip_id", "camera"],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"], onDelete = ForeignKey.CASCADE)],
)
data class CameraClockOffsetEntity(
    @ColumnInfo(name = "trip_id") val tripId: String,
    val camera: String,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
)

@Entity(
    tableName = "media_item",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["trip_id", "taken_utc_ms"], name = "idx_media_trip_time"),
        Index(value = ["mediastore_id"], name = "idx_media_ms", unique = true),
        Index("segment_id"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "segment_id") val segmentId: String?,
    val kind: String,
    val source: String,
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    @ColumnInfo(name = "mediastore_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "orientation_deg") val orientationDeg: Int?,
    @ColumnInfo(name = "taken_utc_ms") val takenUtcMs: Long,
    @ColumnInfo(name = "taken_time_source") val takenTimeSource: String,
    @ColumnInfo(name = "exif_lat") val exifLat: Double?,
    @ColumnInfo(name = "exif_lon") val exifLon: Double?,
    @ColumnInfo(name = "camera_make") val cameraMake: String?,
    @ColumnInfo(name = "camera_model") val cameraModel: String?,
    @ColumnInfo(name = "linked_lat") val linkedLat: Double?,
    @ColumnInfo(name = "linked_lon") val linkedLon: Double?,
    @ColumnInfo(name = "link_method") val linkMethod: String,
    @ColumnInfo(name = "link_confidence") val linkConfidence: Double,
    @ColumnInfo(name = "link_time_gap_ms") val linkTimeGapMs: Long?,
    @ColumnInfo(name = "needs_review") val needsReview: Boolean,
    val sha256: String?,
    @ColumnInfo(name = "user_caption") val userCaption: String?,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "is_excluded") val isExcluded: Boolean,
)

@Entity(
    tableName = "voice_note",
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["segment_id", "started_utc_ms"], name = "idx_voice_segment_time"), Index(value = ["media_item_id"], name = "idx_voice_media")],
)
data class VoiceNoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "started_utc_ms") val startedUtcMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val lat: Double?,
    val lon: Double?,
    @ColumnInfo(name = "h_accuracy_m") val hAccuracyM: Float?,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    @ColumnInfo(name = "audio_format") val audioFormat: String,
    val transcript: String?,
    @ColumnInfo(name = "transcript_edited") val transcriptEdited: String?,
    @ColumnInfo(name = "transcript_lang") val transcriptLang: String?,
    @ColumnInfo(name = "transcript_engine") val transcriptEngine: String?,
    @ColumnInfo(name = "transcript_status") val transcriptStatus: String,
    @ColumnInfo(name = "transcript_segments_json") val transcriptSegmentsJson: String?,
    @ColumnInfo(name = "media_item_id") val mediaItemId: String?,
)

@Entity(
    tableName = "text_note",
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["segment_id", "time_utc_ms"], name = "idx_text_segment_time")],
)
data class TextNoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "time_utc_ms") val timeUtcMs: Long,
    val lat: Double?,
    val lon: Double?,
    val kind: String,
    val text: String?,
)

@Entity(
    tableName = "stop",
    foreignKeys = [ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["segment_id", "start_utc_ms"], name = "idx_stop_segment")],
)
data class StopEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
    @ColumnInfo(name = "end_utc_ms") val endUtcMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    // place(id) is a Phase 4 table; kept as a plain column until then.
    @ColumnInfo(name = "place_id") val placeId: String?,
    @ColumnInfo(name = "user_label") val userLabel: String?,
)

// ---- Phase 4: places and enrichment (DISENO §5.2 "Lugares", §11) ----

@Entity(tableName = "place")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String?,
    @ColumnInfo(name = "country_code") val countryCode: String?,
    val admin1: String?,
    val admin2: String?,
    val locality: String?,
    @ColumnInfo(name = "osm_type") val osmType: String?,
    @ColumnInfo(name = "osm_id") val osmId: Long?,
    @ColumnInfo(name = "wikidata_id") val wikidataId: String?,
    @ColumnInfo(name = "wikipedia_title") val wikipediaTitle: String?,
    @ColumnInfo(name = "wikipedia_lang") val wikipediaLang: String?,
    val source: String,
    @ColumnInfo(name = "fetched_utc_ms") val fetchedUtcMs: Long?,
)

@Entity(
    tableName = "enrichment",
    foreignKeys = [ForeignKey(entity = PlaceEntity::class, parentColumns = ["id"], childColumns = ["place_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["place_id"], name = "idx_enrichment_place")],
)
data class EnrichmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "place_id") val placeId: String,
    val lang: String,
    val title: String?,
    val summary: String,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    val license: String?,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
    /** The source text as fetched, kept when the user or the model rewrites [summary] (DISENO §11.4). */
    @ColumnInfo(name = "original_summary") val originalSummary: String?,
    @ColumnInfo(name = "llm_rewritten", defaultValue = "0") val llmRewritten: Boolean,
    @ColumnInfo(name = "user_approved", defaultValue = "0") val userApproved: Boolean,
    @ColumnInfo(name = "fetched_utc_ms") val fetchedUtcMs: Long,
)

@Entity(
    tableName = "segment_place",
    primaryKeys = ["segment_id", "place_id"],
    foreignKeys = [
        ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segment_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlaceEntity::class, parentColumns = ["id"], childColumns = ["place_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["place_id"], name = "idx_segment_place_place")],
)
data class SegmentPlaceEntity(
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "place_id") val placeId: String,
    @ColumnInfo(name = "first_seen_utc_ms") val firstSeenUtcMs: Long,
    @ColumnInfo(name = "dwell_ms") val dwellMs: Long?,
    val relevance: Double?,
    /** "Descartar": the user removed this place from the segment; a re-run must not bring it back (review I5). */
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
)
