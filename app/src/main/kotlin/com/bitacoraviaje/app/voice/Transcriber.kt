package com.bitacoraviaje.app.voice

import java.io.File

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class TranscriptResult(val text: String, val segments: List<TranscriptSegment>, val language: String?)

/**
 * Pluggable speech-to-text (DISENO §8.3). Implementations: the system recognizer from
 * a file (Phase 2), whisper.cpp on device (Phase 3), an optional cloud service.
 */
interface Transcriber {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun transcribe(audio: File, languageTag: String): TranscriptResult
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Last resort: records that no engine could run, so the note shows "Reintentar". */
class StubTranscriber : Transcriber {
    override val id: String = "stub"
    override suspend fun isAvailable(): Boolean = false
    override suspend fun transcribe(audio: File, languageTag: String): TranscriptResult = throw TranscriptionException("no transcription engine available")
}
