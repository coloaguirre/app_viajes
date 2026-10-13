package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.VoiceNote

/** Everything the writers need for one segment, already loaded from the database. */
data class SegmentExport(
    val segment: Segment,
    /** Accepted points in time order; filtered-out ones only when the export asks for them. */
    val points: List<TrackPoint>,
    val pauses: List<SegmentPause> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val voiceNotes: List<VoiceNote> = emptyList(),
    val textNotes: List<TextNote> = emptyList(),
)

/** A data source whose license requires a credit in the exported diary (DISENO §11.5). */
data class Attribution(val name: String, val license: String, val url: String)

/**
 * The export input (DISENO §12): the whole trip as domain objects plus the relative
 * paths the media and audio files will have inside the ZIP. Pure data, so every writer
 * is testable on the JVM.
 */
data class TripExport(
    val trip: Trip,
    val segments: List<SegmentExport>,
    val appVersion: String,
    val exportedUtcMs: Long,
    /** media id → path inside the ZIP (`media/fotos/2026-10-02/IMG_1.jpg`); absent = not included. */
    val mediaFiles: Map<String, String> = emptyMap(),
    /** voice note id → path inside the ZIP (`audio/2026-10-02/nota_101732.m4a`); absent = not included. */
    val audioFiles: Map<String, String> = emptyMap(),
    val attributions: List<Attribution> = DEFAULT_ATTRIBUTIONS,
    val includeFilteredPoints: Boolean = false,
) {
    val allMedia: List<MediaItem> get() = segments.flatMap { it.media }
    val allVoiceNotes: List<VoiceNote> get() = segments.flatMap { it.voiceNotes }
    val allTextNotes: List<TextNote> get() = segments.flatMap { it.textNotes }
    val allStops: List<Stop> get() = segments.flatMap { it.stops }
    val startUtcMs: Long get() = segments.minOfOrNull { it.segment.startedUtcMs } ?: trip.startedUtcMs ?: trip.createdUtcMs
    val endUtcMs: Long? get() = segments.mapNotNull { it.segment.endedUtcMs }.maxOrNull() ?: trip.endedUtcMs
    val tzId: String get() = segments.firstOrNull()?.segment?.tzId ?: "UTC"

    companion object {
        val DEFAULT_ATTRIBUTIONS = listOf(
            Attribution("Datos del mapa © colaboradores de OpenStreetMap", "ODbL", "https://www.openstreetmap.org/copyright"),
        )
    }
}

/** One writer per format: streams to [out]; [path] is where it lives inside the ZIP. */
interface TripWriter {
    val path: String

    fun write(trip: TripExport, out: java.io.OutputStream)
}
