package com.bitacoraviaje.app.media

import android.util.Log
import com.bitacoraviaje.core.geo.ExifTime
import com.bitacoraviaje.core.geo.LinkConfig
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.geo.linkMedia
import com.bitacoraviaje.core.geo.segmentFor
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case: find the trip's photos and videos in MediaStore and link them to the
 * track (DISENO §7.3, §7.5, §7.6). Idempotent: rescans upsert by mediastore_id and
 * never touch user edits (caption, favourite, excluded, manual placement).
 */
@Singleton
class MediaLinker @Inject constructor(
    private val scanner: MediaCatalog,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
) {
    data class Result(val scanned: Int, val linked: Int, val review: Int, val skipped: Int)

    /**
     * Reconciliation sweep over every segment of [tripId] (±10 min), or only [segmentIds].
     * MediaStore's DATE_TAKEN carries the camera's own clock, so the window is widened by
     * the largest confirmed camera offset of the trip (review I5).
     */
    suspend fun linkTrip(tripId: String, segmentIds: Collection<String>? = null, nowUtcMs: Long = System.currentTimeMillis()): Result {
        val segments = tracking.segmentsOf(tripId).filter { segmentIds == null || it.id in segmentIds }
        if (segments.isEmpty()) return Result(0, 0, 0, 0)
        val margin = LinkConfig().segmentMarginMs + (media.cameraOffsets(tripId).values.maxOfOrNull { kotlin.math.abs(it) } ?: 0L)
        val from = segments.minOf { it.startedUtcMs } - margin
        val to = segments.maxOf { it.endedUtcMs ?: nowUtcMs } + margin
        val candidates = scanner.queryByTime(from, to)
        return link(tripId, candidates, segments, nowUtcMs)
    }

    /**
     * DISENO §7.6 step 4: shift the capture time of every automatically linked item of
     * [camera] ("Make Model") by [offsetMs] and re-link them from the track. Manual
     * placements and exclusions are kept.
     */
    suspend fun applyCameraOffset(tripId: String, camera: String, offsetMs: Long, nowUtcMs: Long = System.currentTimeMillis()) {
        val segments = tracking.segmentsOf(tripId)
        val pointsCache = HashMap<String, List<TrackPoint>>()
        // Items already carry the previously confirmed offset: store the accumulated total for future scans.
        media.setCameraOffset(tripId, camera, (media.cameraOffsets(tripId)[camera] ?: 0L) + offsetMs)
        val updated = media.allOfTrip(tripId).filter { m ->
            listOfNotNull(m.cameraMake, m.cameraModel).joinToString(" ") == camera && m.linkMethod != LinkMethod.MANUAL && !m.isExcluded
        }.map { m ->
            val taken = m.takenUtcMs + offsetMs
            val segment = segmentFor(taken, segments, nowUtcMs)
            val points = segment?.let { s -> pointsCache.getOrPut(s.id) { tracking.validPoints(s.id) } } ?: emptyList()
            // EXIF GPS stays authoritative when present; only the time moved.
            val link = linkMedia(taken, m.exifLat, m.exifLon, segment, points, LinkConfig.forTransport(segment?.transport))
            m.copy(takenUtcMs = taken, segmentId = link.segmentId, linkedLat = link.lat, linkedLon = link.lon, linkMethod = link.method, linkConfidence = link.confidence, linkTimeGapMs = link.timeGapMs, needsReview = link.needsReview)
        }
        val n = media.applyLinks(emptyList(), updated)
        Log.i(TAG, "camera offset applied camera=$camera offsetMs=$offsetMs items=$n")
    }

    private suspend fun link(tripId: String, candidates: List<MediaCandidate>, segments: List<Segment>, nowUtcMs: Long): Result {
        var linked = 0
        var review = 0
        var skipped = 0
        val pointsCache = HashMap<String, List<TrackPoint>>()
        val newItems = ArrayList<MediaItem>()
        val relinked = ArrayList<MediaItem>()
        val offsets = HashMap(media.cameraOffsets(tripId))
        for (c in candidates) {
            val existing = media.getByMediaStoreId(c.mediaStoreId)
            if (existing != null && (existing.linkMethod == LinkMethod.MANUAL || existing.isExcluded || existing.tripId != tripId)) {
                // User edits win; an item already owned by another trip is not stolen (review M8).
                skipped++
                continue
            }
            val camera = listOfNotNull(c.exif?.make, c.exif?.model).joinToString(" ")
            val (resolved, source) = resolveTakenTime(c, segments, nowUtcMs)
            // DISENO §7.6: the GPS time stamp is true UTC. A camera whose own clock disagrees by
            // more than a minute gets its offset confirmed automatically, once, for the whole trip.
            val gpsUtc = c.exif?.let { ExifTime.gpsUtc(it.gpsDateStamp, it.gpsTimeStamp) }
            if (gpsUtc != null && camera.isNotBlank() && camera !in offsets && c.kind == MediaKind.PHOTO &&
                (source == TakenTimeSource.EXIF_WITH_OFFSET || source == TakenTimeSource.EXIF_LOCAL) &&
                kotlin.math.abs(gpsUtc - resolved) >= AUTO_OFFSET_MIN_MS
            ) {
                offsets[camera] = gpsUtc - resolved
                media.setCameraOffset(tripId, camera, gpsUtc - resolved)
                Log.i(TAG, "camera clock offset from GPS time camera=$camera offsetMs=${gpsUtc - resolved}")
            }
            val taken = resolved + (offsets[camera] ?: 0L)
            val segment = segmentFor(taken, segments, nowUtcMs)
            if (segment == null) {
                skipped++
                continue
            }
            val points = pointsCache.getOrPut(segment.id) { tracking.validPoints(segment.id) }
            val link = linkMedia(taken, c.exif?.lat, c.exif?.lon, segment, points, LinkConfig.forTransport(segment.transport))
            if (link.method != LinkMethod.NONE) linked++
            if (link.needsReview || link.method == LinkMethod.NONE) review++
            val item = MediaItem(
                id = existing?.id ?: UUID.randomUUID().toString(),
                tripId = tripId, segmentId = link.segmentId, kind = c.kind, source = MediaSource.MEDIASTORE,
                contentUri = c.contentUri.toString(), mediaStoreId = c.mediaStoreId, displayName = c.displayName, mimeType = c.mimeType,
                sizeBytes = c.sizeBytes, width = c.width, height = c.height, durationMs = c.durationMs, orientationDeg = c.orientationDeg,
                takenUtcMs = taken, takenTimeSource = source, exifLat = c.exif?.lat, exifLon = c.exif?.lon,
                cameraMake = c.exif?.make, cameraModel = c.exif?.model,
                linkedLat = link.lat, linkedLon = link.lon, linkMethod = link.method, linkConfidence = link.confidence,
                linkTimeGapMs = link.timeGapMs, needsReview = link.needsReview,
                sha256 = existing?.sha256, userCaption = existing?.userCaption,
                isFavorite = existing?.isFavorite ?: false, isExcluded = existing?.isExcluded ?: false,
            )
            if (existing == null) newItems += item else relinked += item
        }
        // Known rows are updated with a guarded UPDATE: a manual placement made while this
        // sweep ran is not overwritten (review I6).
        media.applyLinks(newItems, relinked)
        Log.i(TAG, "media link trip=$tripId scanned=${candidates.size} linked=$linked review=$review skipped=$skipped")
        return Result(candidates.size, linked, review, skipped)
    }

    companion object {
        private const val TAG = "Bitacora"

        /** Below this, GPS-vs-camera clock differences are rounding, not a wrong clock. */
        const val AUTO_OFFSET_MIN_MS = 60_000L

        /**
         * DISENO §7.5 priority list. Pure: testable without MediaStore. Videos: the
         * container's creation time is the END of the clip (review R7), so the
         * duration is subtracted.
         */
        fun resolveTakenTime(c: MediaCandidate, segments: List<Segment>, nowUtcMs: Long): Pair<Long, TakenTimeSource> {
            val exif = c.exif
            val durationAdjust = if (c.kind == MediaKind.VIDEO) (c.durationMs ?: 0L) else 0L
            ExifTime.withOffset(exif?.dateTimeOriginal, exif?.offsetTimeOriginal, exif?.subsecOriginal)?.let { return (it - durationAdjust) to TakenTimeSource.EXIF_WITH_OFFSET }
            if (exif?.dateTimeOriginal != null) {
                // Local time: convert with the zone of the segment in force then (not the phone's zone now).
                val guess = c.dateTakenMs ?: (c.dateAddedS ?: 0L) * 1000
                val tz = segmentFor(guess, segments, nowUtcMs)?.tzId ?: segments.lastOrNull()?.tzId ?: "UTC"
                ExifTime.local(exif.dateTimeOriginal, tz, exif.subsecOriginal)?.let { return (it - durationAdjust) to TakenTimeSource.EXIF_LOCAL }
            }
            c.dateTakenMs?.let { return (it - durationAdjust) to TakenTimeSource.MEDIASTORE }
            return ((c.dateModifiedS ?: c.dateAddedS ?: nowUtcMs / 1000) * 1000 - durationAdjust) to TakenTimeSource.FILE_MTIME
        }
    }
}
