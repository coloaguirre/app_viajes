package com.bitacoraviaje.app.video

import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.videoplan.PlanInput
import com.bitacoraviaje.core.videoplan.PlanMedia
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.PlanPlace
import com.bitacoraviaje.core.videoplan.PlanSegment
import com.bitacoraviaje.core.videoplan.PlanVoice
import com.bitacoraviaje.core.videoplan.Planner
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.PlacesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Room → [PlanInput] for a whole trip (PLAN 5.1), same rules as core/export `PlanInputs.of`
 * and [MiniVideoPlanner]: the same `VideoPlan` renders on the desktop and on the phone.
 */
class TripVideoPlanner @Inject constructor(
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: PlacesRepository,
) {
    data class Prepared(val segments: List<Segment>, val input: PlanInput)

    suspend fun segment(segmentId: String): Segment? = tracking.getSegment(segmentId)

    /** [onlySegmentId]: a stage video keeps only that segment and never the unlinked media. */
    suspend fun input(tripId: String, onlySegmentId: String? = null): Prepared? {
        val trip = tracking.getTrip(tripId) ?: return null
        val segments = tracking.segmentsOf(tripId).sortedBy { it.startedUtcMs }.filter { onlySegmentId == null || it.id == onlySegmentId }
        val allMedia = media.allOfTrip(tripId)
        val planSegments = segments.mapIndexed { i, s ->
            val mediaItems = allMedia.filter { it.segmentId == s.id || (onlySegmentId == null && i == 0 && it.segmentId == null) }.sortedBy { it.takenUtcMs } // orphans with the first segment, like the export
            val voices = notes.voiceNotesOf(s.id).sortedBy { it.startedUtcMs }
            val texts = notes.textNotesOf(s.id)
            val placeRows = places.placesOf(s.id).flatMap { p ->
                places.enrichmentsOf(p.id).filter { it.userApproved }.sortedBy { if (it.sourceName == Enrichment.SOURCE_WIKIPEDIA) 0 else 1 }
                    .map { e -> PlanPlace(p.id, p.name, e.summary, e.sourceName, e.license, e.sourceUrl.ifBlank { null }) }
            }
            PlanSegment(
                id = s.id,
                title = s.displayName ?: when (s.type) { SegmentType.TREKKING -> "Trekking"; SegmentType.EXCURSION -> "Excursión"; else -> "Etapa" },
                date = Instant.ofEpochMilli(s.startedUtcMs).atZone(runCatching { ZoneId.of(s.tzId) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate().toString(),
                startedUtcMs = s.startedUtcMs, endedUtcMs = s.endedUtcMs,
                distanceM = s.stats.distanceM, movingTimeMs = s.stats.movingTimeMs, elevationGainM = s.stats.elevationGainM, maxAltM = s.stats.maxAltM,
                hasTrack = tracking.countValidPoints(s.id) >= 2, // a count, not the whole track (review M7)
                media = mediaItems.map { m -> PlanMedia(m.id, m.kind == MediaKind.VIDEO, m.takenUtcMs, m.durationMs, m.isFavorite, m.isExcluded, m.userCaption) },
                voiceNotes = voices.map { v -> PlanVoice(v.id, v.startedUtcMs, v.durationMs, v.displayText) },
                highlightsUtcMs = texts.filter { it.kind == TextNoteKind.HIGHLIGHT }.map { it.timeUtcMs }.sorted(),
                places = placeRows,
            )
        }
        return Prepared(segments, PlanInput(trip.id, trip.title, trip.coverMediaId, planSegments, extraCredits = listOf(MiniVideoPlanner.TILES_CREDIT)))
    }

    suspend fun plan(tripId: String, params: PlanParams): Pair<Prepared, VideoPlan>? {
        val p = input(tripId) ?: return null
        return p to Planner.plan(p.input, params)
    }
}
