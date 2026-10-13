package com.bitacoraviaje.app.export

import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.core.export.Names
import com.bitacoraviaje.core.export.SegmentExport
import com.bitacoraviaje.core.export.TripExport
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import javax.inject.Inject

/** What the user chose in the export dialog (DISENO §12.1 options). */
data class ExportOptions(
    val includeMedia: Boolean = true,
    val includeAudio: Boolean = true,
    val includeFilteredPoints: Boolean = false,
)

/** Loads a whole trip from Room into the pure [TripExport], assigning the relative file names inside the ZIP. */
class ExportLoader @Inject constructor(
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: com.bitacoraviaje.data.repository.PlacesRepository,
) {
    suspend fun load(tripId: String, options: ExportOptions, nowUtcMs: Long = System.currentTimeMillis()): TripExport? {
        val trip = tracking.getTrip(tripId) ?: return null
        val segments = tracking.segmentsOf(tripId)
        // Excluded items travel too (with `excluded = true`, no file) so a re-import keeps the exclusion (review M9).
        val mediaByTrip = media.allOfTrip(tripId)
        val mediaBySegment = mediaByTrip.groupBy { it.segmentId }
        val orphan = mediaBySegment[null].orEmpty()
        val exports = segments.mapIndexed { i, s ->
            SegmentExport(
                segment = s,
                points = if (options.includeFilteredPoints) tracking.allPoints(s.id) else tracking.validPoints(s.id),
                pauses = tracking.pausesOf(s.id),
                stops = tracking.stopsOf(s.id),
                // Media not attached to any segment (unlinked) still travel with the trip, listed under the first segment.
                media = mediaBySegment[s.id].orEmpty() + if (i == 0) orphan else emptyList(),
                voiceNotes = notes.voiceNotesOf(s.id),
                textNotes = notes.textNotesOf(s.id),
            )
        }
        val tzBy = { segId: String? -> segments.firstOrNull { it.id == segId }?.tzId ?: segments.firstOrNull()?.tzId ?: "UTC" }
        val used = HashSet<String>()
        fun unique(path: String, id: String): String {
            if (used.add(path)) return path
            val alt = path.substringBeforeLast('.') + "_" + id.take(6) + "." + path.substringAfterLast('.', "bin")
            used += alt
            return alt
        }
        val mediaFiles = if (options.includeMedia) {
            mediaByTrip.filter { it.contentUri != null && !it.isExcluded }.associate { m ->
                m.id to unique(Names.mediaPath(if (m.kind == MediaKind.VIDEO) "videos" else "fotos", m.takenUtcMs, tzBy(m.segmentId), m.displayName, m.id), m.id)
            }
        } else {
            emptyMap()
        }
        val audioFiles = if (options.includeAudio) {
            exports.flatMap { se -> se.voiceNotes.map { v -> v.id to Names.audioPath(v.startedUtcMs, se.segment.tzId, v.id) } }.toMap()
        } else {
            emptyMap()
        }
        return TripExport(
            trip = trip,
            segments = exports,
            appVersion = BuildConfig.VERSION_NAME,
            exportedUtcMs = nowUtcMs,
            mediaFiles = mediaFiles,
            audioFiles = audioFiles,
            places = places.placesOfTrip(tripId),
            enrichments = places.enrichmentsOfTrip(tripId),
            segmentPlaces = places.segmentPlacesOfTrip(tripId),
            includeFilteredPoints = options.includeFilteredPoints,
        )
    }
}
