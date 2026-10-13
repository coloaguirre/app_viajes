package com.bitacoraviaje.core.model

enum class MediaKind { PHOTO, VIDEO }

/** Where a media item came from (DISENO §7.2). */
enum class MediaSource { MEDIASTORE, APP_CAMERA, GOOGLE_PHOTOS_PICKER, IMPORT }

/** How the capture time was determined, best first (DISENO §7.5). */
enum class TakenTimeSource { EXIF_WITH_OFFSET, EXIF_LOCAL, MEDIASTORE, FILE_MTIME }

/** How the position was determined (DISENO §7.6). */
enum class LinkMethod { EXIF, TRACK_INTERPOLATED, TRACK_NEAREST, MANUAL, NONE }

/**
 * A photo or video linked (or not yet) to the trip. Originals are never copied
 * during the trip: only the content URI and a fingerprint are kept (DISENO §5.1).
 */
data class MediaItem(
    val id: String,
    val tripId: String,
    val segmentId: String? = null,
    val kind: MediaKind,
    val source: MediaSource,
    val contentUri: String? = null,
    val mediaStoreId: Long? = null,
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val orientationDeg: Int? = null,
    val takenUtcMs: Long,
    val takenTimeSource: TakenTimeSource,
    val exifLat: Double? = null,
    val exifLon: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val linkedLat: Double? = null,
    val linkedLon: Double? = null,
    val linkMethod: LinkMethod = LinkMethod.NONE,
    val linkConfidence: Double = 0.0,
    val linkTimeGapMs: Long? = null,
    /** Flagged when EXIF and track disagree by a lot: likely another camera with a wrong clock (§7.6). */
    val needsReview: Boolean = false,
    val sha256: String? = null,
    val userCaption: String? = null,
    val isFavorite: Boolean = false,
    val isExcluded: Boolean = false,
)
