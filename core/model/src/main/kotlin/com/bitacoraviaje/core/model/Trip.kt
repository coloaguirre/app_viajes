package com.bitacoraviaje.core.model

/** A whole journey ("Patagonia 2026"). Times are epoch milliseconds UTC. */
data class Trip(
    val id: String,
    val title: String,
    val description: String? = null,
    val createdUtcMs: Long,
    val startedUtcMs: Long? = null,
    val endedUtcMs: Long? = null,
    val coverMediaId: String? = null,
    val status: TripStatus,
)

/** Statistics computed when a segment closes (DISENO §6.5). All optional until then. */
data class SegmentStats(
    val distanceM: Double? = null,
    val movingTimeMs: Long? = null,
    val totalTimeMs: Long? = null,
    val maxSpeedMps: Double? = null,
    val avgMovingSpeedMps: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val minAltM: Double? = null,
    val maxAltM: Double? = null,
    val bbox: BoundingBox? = null,
)

data class BoundingBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/**
 * A continuous recording session inside a trip: a day, a leg, an excursion or a
 * trek. Daily segments are cut automatically at local midnight (adenda 1), so
 * [autoCreated] tells them apart from user-declared ones.
 */
data class Segment(
    val id: String,
    val tripId: String,
    val parentSegmentId: String? = null,
    val type: SegmentType,
    val transport: Transport? = null,
    val title: String? = null,
    val intentText: String? = null,
    val startedUtcMs: Long,
    val endedUtcMs: Long? = null,
    val tzId: String,
    val samplingProfile: String,
    val recordingMode: RecordingMode,
    val autoCreated: Boolean = false,
    val status: SegmentStatus,
    val stats: SegmentStats = SegmentStats(),
    /** Name derived from the map (road, localities or trail), PLAN 3.6; the user's [title] wins when set. */
    val autoName: String? = null,
) {
    /** What to show: the user's title, else the derived name. */
    val displayName: String? get() = title ?: autoName
}

data class SegmentPause(
    val id: String,
    val segmentId: String,
    val kind: PauseKind,
    val pausedUtcMs: Long,
    val resumedUtcMs: Long? = null,
)

/** One GPS fix. Raw values are kept; [isFilteredOut] marks what the filter rejected. */
data class TrackPoint(
    val id: Long = 0,
    val segmentId: String,
    val timeUtcMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double? = null,
    val altMslM: Double? = null,
    val hAccuracyM: Float? = null,
    val vAccuracyM: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val activity: String? = null,
    val isMock: Boolean = false,
    /** Monotonic clock (ms since boot) of the fix; immune to wall-clock changes. Schema v2. */
    val monotonicMs: Long? = null,
    val isFilteredOut: Boolean = false,
    val isSimplifiedKeep: Boolean = true,
)

/** A detected stay point (DISENO §6.6). */
data class Stop(
    val id: String,
    val segmentId: String,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val placeId: String? = null,
    val userLabel: String? = null,
)
