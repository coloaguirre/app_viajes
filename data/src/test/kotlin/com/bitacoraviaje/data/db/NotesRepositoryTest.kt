package com.bitacoraviaje.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.repository.NotesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotesRepositoryTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var repo: NotesRepository

    @BeforeTest
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        repo = NotesRepository(db)
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `voice note lifecycle keeps the original transcript when edited`() = runTest {
        val n = VoiceNote("v1", "s1", Fixtures.T0, 4_200, -34.6, -58.4, 5f, "audio/t1/v1.m4a")
        repo.addVoiceNote(n)
        assertEquals(1, repo.pendingTranscriptions().size)
        repo.setTranscriptStatus("v1", TranscriptStatus.RUNNING)
        repo.setTranscript("v1", "stub", "es-AR", "había un mirador increíble", null)
        assertEquals(0, repo.pendingTranscriptions().size)
        repo.editTranscript("v1", "Había un mirador increíble.")
        val v = repo.getVoiceNote("v1")!!
        assertEquals("había un mirador increíble", v.transcript)
        assertEquals("Había un mirador increíble.", v.displayText)
        assertEquals(TranscriptStatus.DONE, v.transcriptStatus)
        assertEquals(listOf("v1"), repo.observeVoiceNotesOfTrip("t1").first().map { it.id })
    }

    @Test
    fun `failed transcriptions are pending again and notes follow their segment`() = runTest {
        repo.addVoiceNote(VoiceNote("v1", "s1", Fixtures.T0, 1_000, null, null, null, "audio/t1/v1.m4a"))
        repo.setTranscriptStatus("v1", TranscriptStatus.FAILED)
        assertEquals(1, repo.pendingTranscriptions().size)
        db.tripDao().delete("t1")
        assertNull(repo.getVoiceNote("v1"), "cascade through segment")
    }

    @Test
    fun `text notes, markers and highlights`() = runTest {
        repo.addTextNote("s1", TextNoteKind.MARKER, Fixtures.T0, -34.6, -58.4, "mirador")
        repo.addTextNote("s1", TextNoteKind.HIGHLIGHT, Fixtures.T0 + 1, -34.6, -58.4, "   ")
        val notes = repo.observeTextNotes("s1").first()
        assertEquals(listOf(TextNoteKind.MARKER, TextNoteKind.HIGHLIGHT), notes.map { it.kind })
        assertNull(notes[1].text, "blank text is stored as null")
        assertEquals(2, repo.observeTextNotesOfTrip("t1").first().size)
    }
}
