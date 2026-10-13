package com.bitacoraviaje.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.ui.review.MediaReviewScreen
import com.bitacoraviaje.app.ui.review.MediaReviewUiState
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import com.bitacoraviaje.app.ui.tripdetail.SegmentTimeline
import com.bitacoraviaje.app.ui.tripdetail.TripDetailScreen
import com.bitacoraviaje.app.ui.tripdetail.TripDetailUiState
import com.bitacoraviaje.app.ui.voicenote.VoiceNoteDetailScreen
import com.bitacoraviaje.app.ui.voicenote.VoiceNoteDetailUiState
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.timeline.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PLAN item 2.7: trip detail (tabs synced with the map), media review and voice note detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailScreensComposeTest {
    @get:Rule
    val rule = createComposeRule()

    private val t0 = 1_760_000_000_000L
    private val trip = Trip(id = "t1", title = "Viaje de prueba", createdUtcMs = t0, startedUtcMs = t0, status = TripStatus.ACTIVE)
    private val segment = Segment(
        id = "s1", tripId = "t1", type = SegmentType.TREKKING, startedUtcMs = t0, endedUtcMs = t0 + 3_600_000, tzId = "America/Argentina/Buenos_Aires",
        samplingProfile = "WALK", recordingMode = RecordingMode.FULL, status = SegmentStatus.FINISHED, stats = SegmentStats(distanceM = 4_200.0),
    )
    private val photo = MediaItem(
        id = "m1", tripId = "t1", segmentId = "s1", kind = MediaKind.PHOTO, source = MediaSource.MEDIASTORE, contentUri = "content://media/1", displayName = "IMG_1.jpg",
        takenUtcMs = t0 + 600_000, takenTimeSource = TakenTimeSource.EXIF_WITH_OFFSET, linkMethod = LinkMethod.NONE,
    )
    private val voice = VoiceNote(id = "v1", segmentId = "s1", startedUtcMs = t0 + 1_200_000, durationMs = 7_000, lat = -34.6, lon = -58.4, audioPath = "audio/t1/v1.m4a", transcript = "hola mundo", transcriptStatus = TranscriptStatus.DONE)
    private val events = listOf(
        TimelineEvent.SegmentStart(t0, -34.6, -58.4, segment),
        TimelineEvent.Moment(photo.takenUtcMs, -34.61, -58.41, listOf(photo)),
        TimelineEvent.Voice(voice.startedUtcMs, voice.lat, voice.lon, voice),
        TimelineEvent.Highlight(t0 + 1_800_000, -34.62, -58.42, TextNote("x1", "s1", t0 + 1_800_000, -34.62, -58.42, TextNoteKind.HIGHLIGHT)),
        TimelineEvent.SegmentEnd(t0 + 3_600_000, -34.63, -58.43, segment),
    )

    @Test
    fun `trip detail lists events, switches tabs and reports taps on events and review`() {
        val tapped = mutableListOf<TimelineEvent>()
        var reviews = 0
        rule.setContent {
            var tab by remember { mutableStateOf(0) }
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = listOf(SegmentTimeline(segment, events, emptyList())), media = listOf(photo), reviewCount = 1, map = TrackMapState(), selectedTab = tab),
                    onBack = {},
                    onTab = { tab = it },
                    onEvent = { tapped += it },
                    onReview = { reviews++ },
                )
            }
        }
        rule.onNodeWithText("Viaje de prueba").assertIsDisplayed()
        rule.onNodeWithText("Inicio de la etapa").assertIsDisplayed()
        rule.onNodeWithText("hola mundo").performClick()
        rule.onNodeWithText("Momento destacado").performClick()
        assertEquals(listOf(events[2], events[3]), tapped)
        rule.onNodeWithTag("trip_tab_1").performClick()
        rule.onNodeWithTag("trip_map").assertIsDisplayed()
        rule.onNodeWithTag("trip_tab_2").performClick()
        rule.onNodeWithTag("photos_grid").assertIsDisplayed()
        rule.onNodeWithTag("photos_review").performClick()
        assertEquals(1, reviews)
    }

    @Test
    fun `export dialog starts with the chosen options and destination and shows the result`() {
        val started = mutableListOf<Pair<com.bitacoraviaje.app.export.ExportOptions, com.bitacoraviaje.app.export.ExportDestination>>()
        rule.setContent {
            var status by remember { mutableStateOf(com.bitacoraviaje.app.export.ExportStatus()) }
            BitacoraTheme {
                com.bitacoraviaje.app.ui.tripdetail.ExportDialog(
                    status = status,
                    onDismiss = {},
                    onStart = { o, d -> started += o to d; status = com.bitacoraviaje.app.export.ExportStatus(finished = true, folder = "Viaje_2026-10-13", mediaWritten = 4, mediaTotal = 5, audioWritten = 2, audioTotal = 2, missing = 1) },
                    onShareResult = {},
                    onReset = {},
                )
            }
        }
        rule.onNodeWithTag("export_opt_filtered").performClick()
        rule.onNodeWithTag("export_opt_audio").performClick()
        rule.onNodeWithTag("export_downloads").performClick()
        assertEquals(listOf(com.bitacoraviaje.app.export.ExportOptions(includeMedia = true, includeAudio = false, includeFilteredPoints = true) to com.bitacoraviaje.app.export.ExportDestination.DOWNLOADS), started)
        rule.onNodeWithTag("export_done").assertIsDisplayed()
        rule.onNodeWithText("Fotos y videos: 4 de 5 · Audios: 2 de 2").assertIsDisplayed()
        rule.onNodeWithTag("export_share_result").assertIsDisplayed()
    }

    @Test
    fun `media review exposes the three actions and the camera fix only with a suggestion`() {
        var useTrack = 0
        var excluded = 0
        var fixed = 0
        rule.setContent {
            var state by remember { mutableStateOf(MediaReviewUiState(queue = listOf(photo), selected = photo)) }
            BitacoraTheme {
                MediaReviewScreen(
                    state = state,
                    onBack = {},
                    onSelect = {},
                    onUseTrackTime = { useTrack++ },
                    onExclude = { excluded++; state = state.copy(suggestedOffsetMs = 3_720_000, suggestedCamera = "Canon EOS") },
                    onApplyOffset = { fixed++ },
                )
            }
        }
        rule.onNodeWithTag("review_use_track").performClick()
        rule.onNodeWithTag("review_exclude").performClick()
        rule.onNodeWithTag("review_fix_camera").assertIsDisplayed().performClick()
        rule.onNodeWithText("Corregir el reloj de Canon EOS (+1 h 02 min) en todas sus fotos").assertIsDisplayed()
        assertEquals(listOf(1, 1, 1), listOf(useTrack, excluded, fixed))
    }

    @Test
    fun `media review shows the empty state when the queue is empty but keeps the camera fix offer`() {
        rule.setContent { BitacoraTheme { MediaReviewScreen(MediaReviewUiState(suggestedOffsetMs = -960_000, suggestedCamera = "CamA"), {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("review_empty").assertIsDisplayed()
        rule.onNodeWithTag("review_fix_camera").assertIsDisplayed()
    }

    @Test
    fun `voice note detail enables save only when the text changed and offers retry on failure`() {
        var saved: String? = null
        var retried = 0
        rule.setContent {
            BitacoraTheme {
                VoiceNoteDetailScreen(
                    state = VoiceNoteDetailUiState(note = voice.copy(transcriptStatus = TranscriptStatus.FAILED)),
                    onBack = {},
                    onTogglePlay = {},
                    onSave = { saved = it },
                    onRetry = { retried++ },
                )
            }
        }
        rule.onNodeWithTag("voice_save").assertIsNotEnabled()
        rule.onNodeWithTag("voice_transcript").performTextReplacement("hola mundo corregido")
        rule.onNodeWithTag("voice_save").assertIsEnabled().performClick()
        rule.onNodeWithTag("voice_retry").performClick()
        assertEquals("hola mundo corregido", saved)
        assertEquals(1, retried)
    }
}
