package com.bitacoraviaje.core.model

enum class TranscriptStatus { PENDING, RUNNING, DONE, FAILED }

/** A dictated note: the audio is kept forever; the transcript is editable (DISENO §8). */
data class VoiceNote(
    val id: String,
    val segmentId: String,
    val startedUtcMs: Long,
    val durationMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val hAccuracyM: Float? = null,
    /** Relative to the app's files dir: `audio/<tripId>/<id>.m4a`. */
    val audioPath: String,
    val audioFormat: String = "m4a-aac",
    val transcript: String? = null,
    val transcriptEdited: String? = null,
    val transcriptLang: String? = null,
    val transcriptEngine: String? = null,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.PENDING,
    /** JSON list of {startMs,endMs,text} for subtitles (DISENO §5.2). */
    val transcriptSegmentsJson: String? = null,
    /** Attached photo/video when dictated "about this photo" (DISENO §7.8). */
    val mediaItemId: String? = null,
) {
    val displayText: String? get() = transcriptEdited ?: transcript
}

enum class TextNoteKind { NOTE, MARKER, HIGHLIGHT }

/** Typed note, marker ("this viewpoint") or highlight ("this moment matters") (DISENO §8.4). */
data class TextNote(
    val id: String,
    val segmentId: String,
    val timeUtcMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val kind: TextNoteKind,
    val text: String? = null,
)
