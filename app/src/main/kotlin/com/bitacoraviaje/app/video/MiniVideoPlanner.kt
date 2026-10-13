package com.bitacoraviaje.app.video

import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.MediaKind
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
 * Room → [PlanInput] for one segment, with the same rules as core/export `PlanInputs.of`
 * (title, approved places only, highlights, voice texts), then the shared [Planner]
 * with the adenda-1 preset: a 1-minute video of the stage (PLAN 4.6).
 */
class MiniVideoPlanner @Inject constructor(
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: PlacesRepository,
) {
    data class Prepared(val segment: com.bitacoraviaje.core.model.Segment, val input: PlanInput, val plan: VideoPlan)

    /**
     * Media without a segment (unlinked) stays out: a stage video is about that stage. The export
     * adapter lists them under the first segment only so they travel in the ZIP (review M6).
     */
    suspend fun prepare(segmentId: String): Prepared? {
        val s = tracking.getSegment(segmentId) ?: return null
        val trip = tracking.getTrip(s.tripId) ?: return null
        val points = tracking.validPoints(segmentId)
        val mediaItems = media.allOfTrip(s.tripId).filter { it.segmentId == segmentId }.sortedBy { it.takenUtcMs }
        val voices = notes.voiceNotesOf(segmentId).sortedBy { it.startedUtcMs }
        val texts = notes.textNotesOf(segmentId)
        val placeRows = places.placesOf(segmentId).flatMap { p ->
            places.enrichmentsOf(p.id).filter { it.userApproved }.sortedBy { if (it.sourceName == Enrichment.SOURCE_WIKIPEDIA) 0 else 1 }
                .map { e -> PlanPlace(p.id, p.name, e.summary, e.sourceName, e.license, e.sourceUrl.ifBlank { null }) }
        }
        val segment = PlanSegment(
            id = s.id,
            title = s.displayName ?: when (s.type) { SegmentType.TREKKING -> "Trekking"; SegmentType.EXCURSION -> "Excursión"; else -> "Etapa" },
            date = Instant.ofEpochMilli(s.startedUtcMs).atZone(runCatching { ZoneId.of(s.tzId) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate().toString(),
            startedUtcMs = s.startedUtcMs,
            endedUtcMs = s.endedUtcMs,
            distanceM = s.stats.distanceM,
            movingTimeMs = s.stats.movingTimeMs,
            elevationGainM = s.stats.elevationGainM,
            maxAltM = s.stats.maxAltM,
            hasTrack = points.size >= 2,
            media = mediaItems.map { m -> PlanMedia(m.id, m.kind == MediaKind.VIDEO, m.takenUtcMs, m.durationMs, m.isFavorite, m.isExcluded, m.userCaption) },
            voiceNotes = voices.map { v -> PlanVoice(v.id, v.startedUtcMs, v.durationMs, v.displayText) },
            highlightsUtcMs = texts.filter { it.kind == TextNoteKind.HIGHLIGHT }.map { it.timeUtcMs }.sorted(),
            places = placeRows,
        )
        val input = PlanInput(trip.id, trip.title, trip.coverMediaId, listOf(segment), extraCredits = listOf(TILES_CREDIT))
        return Prepared(s, input, Planner.plan(input, PlanParams.quickOneMinute(segmentId)))
    }

    companion object {
        /** The static map of the stage comes from MapTiler/OSM when the key is configured (D2). */
        const val TILES_CREDIT = "Mapa: © MapTiler © OpenStreetMap contributors"
    }
}
