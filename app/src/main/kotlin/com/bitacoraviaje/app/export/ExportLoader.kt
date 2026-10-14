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
    /** PLAN 5.5: cut the export at the user's privacy zones (on by default when zones exist). */
    val applyPrivacyZones: Boolean = true,
    /** Rewrite the exported JPEG copies without GPS tags when their location was blanked (originals untouched). */
    val stripExifGps: Boolean = true,
)

/** Loads a whole trip from Room into the pure [TripExport], assigning the relative file names inside the ZIP. */
class ExportLoader @Inject constructor(
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: com.bitacoraviaje.data.repository.PlacesRepository,
    private val weather: com.bitacoraviaje.data.repository.WeatherRepository,
) {
    data class ForExport(val trip: TripExport, val privacy: com.bitacoraviaje.core.export.PrivacyReport?, val blankedMediaIds: Set<String>)

    /** The export view of a trip: the full load, then the privacy zones when asked (PLAN 5.5). Backups use [load] and keep everything. */
    suspend fun loadForExport(tripId: String, options: ExportOptions, zones: List<com.bitacoraviaje.core.model.PrivacyZone>, nowUtcMs: Long = System.currentTimeMillis()): ForExport? {
        val full = load(tripId, options, nowUtcMs) ?: return null
        if (!options.applyPrivacyZones || zones.isEmpty()) return ForExport(full, null, emptySet())
        val (filtered, report) = com.bitacoraviaje.core.export.PrivacyFilter.apply(full, zones)
        val before = full.allMedia.associateBy { it.id }
        val blanked = filtered.allMedia.filter { m -> val b = before[m.id]; b != null && (b.linkedLat != null || b.exifLat != null) && m.linkedLat == null && m.exifLat == null }.map { it.id }.toSet()
        return ForExport(filtered, report, blanked)
    }

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
                weather = weather.samplesOf(s.id),
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
