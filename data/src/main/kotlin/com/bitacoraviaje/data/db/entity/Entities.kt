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
