package com.bitacoraviaje.data.repository

import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.mapper.toDomain
import com.bitacoraviaje.data.mapper.toEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Voice notes, text notes, markers and highlights (DISENO §8). */
@Singleton
class NotesRepository @Inject constructor(private val db: BitacoraDatabase) {
    private val voice get() = db.voiceNoteDao()
    private val text get() = db.textNoteDao()

    suspend fun addVoiceNote(note: VoiceNote) = voice.insert(note.toEntity())

    suspend fun getVoiceNote(id: String): VoiceNote? = voice.getById(id)?.toDomain()

    fun observeVoiceNotes(segmentId: String): Flow<List<VoiceNote>> = voice.observeBySegment(segmentId).map { l -> l.map { it.toDomain() } }

    fun observeVoiceNotesOfTrip(tripId: String): Flow<List<VoiceNote>> = voice.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    suspend fun pendingTranscriptions(): List<VoiceNote> = voice.pendingTranscriptions().map { it.toDomain() }

    suspend fun setTranscriptStatus(id: String, status: TranscriptStatus) {
        val n = voice.getById(id)?.toDomain() ?: return
        voice.update(n.copy(transcriptStatus = status).toEntity())
    }

    suspend fun setTranscript(id: String, engine: String, lang: String?, textValue: String, segmentsJson: String?) {
        val n = voice.getById(id)?.toDomain() ?: return
        voice.update(n.copy(transcript = textValue, transcriptEngine = engine, transcriptLang = lang, transcriptSegmentsJson = segmentsJson, transcriptStatus = TranscriptStatus.DONE).toEntity())
    }

    /** User correction: the original transcript is kept (DISENO §8.3). */
    suspend fun editTranscript(id: String, edited: String) {
        val n = voice.getById(id)?.toDomain() ?: return
        voice.update(n.copy(transcriptEdited = edited).toEntity())
    }

    suspend fun deleteVoiceNote(id: String) = voice.delete(id)

    suspend fun addTextNote(segmentId: String, kind: TextNoteKind, timeUtcMs: Long, lat: Double?, lon: Double?, textValue: String? = null): TextNote {
        val n = TextNote(UUID.randomUUID().toString(), segmentId, timeUtcMs, lat, lon, kind, textValue?.ifBlank { null })
        text.insert(n.toEntity())
        return n
    }

    fun observeTextNotes(segmentId: String): Flow<List<TextNote>> = text.observeBySegment(segmentId).map { l -> l.map { it.toDomain() } }

    fun observeTextNotesOfTrip(tripId: String): Flow<List<TextNote>> = text.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    suspend fun deleteTextNote(id: String) = text.delete(id)
}
