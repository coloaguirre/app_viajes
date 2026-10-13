@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // Media3 Transformer API (PLAN 4.6)

package com.bitacoraviaje.app.ui

import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import com.bitacoraviaje.app.tracking.LiveTrackingState
import com.bitacoraviaje.app.ui.active.ActiveSessionAction
import com.bitacoraviaje.app.ui.active.ActiveSessionScreen
import com.bitacoraviaje.app.ui.active.ActiveSessionUiState
import com.bitacoraviaje.app.ui.finish.FinishSegmentScreen
import com.bitacoraviaje.app.ui.finish.FinishSegmentUiState
import com.bitacoraviaje.app.ui.newsession.NewSessionForm
import com.bitacoraviaje.app.ui.newsession.NewSessionScreen
import com.bitacoraviaje.app.ui.newsession.NewSessionUiState
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PLAN item 1.6 acceptance: Compose tests of start / pause / finish on the stateless
 * screens (Robolectric, no emulator). Screens emit actions; view models are covered
 * by repository tests and the emulator E2E.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensComposeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `new session wizard enables Iniciar only after a type and reports the form`() {
        var form = NewSessionForm(mode = RecordingMode.FULL)
        var started = 0
        rule.setContent {
            var state by remember { mutableStateOf(NewSessionUiState(form)) }
            BitacoraTheme {
                NewSessionScreen(
                    state = state,
                    snackbar = SnackbarHostState(),
                    onBack = {},
                    onUpdate = { t -> form = t(form); state = state.copy(form = form) },
                    onStart = { started++ },
                )
            }
        }
        rule.onNodeWithTag("newsession_start").assertIsNotEnabled()
        rule.onNodeWithTag("newsession_type_TREKKING").performScrollTo().performClick()
        rule.onNodeWithTag("newsession_transport_BIKE").performScrollTo().performClick()
        rule.onNodeWithTag("newsession_mode_economy").performScrollTo().performClick()
        rule.onNodeWithTag("newsession_start").performScrollTo().assertIsEnabled().performClick()
        assertEquals(SegmentType.TREKKING, form.type)
        assertEquals(Transport.BIKE, form.transport)
        assertEquals(RecordingMode.ECONOMY, form.mode)
        assertEquals(1, started)
    }

    @Test
    fun `active session pause toggles label and finish emits Finish`() {
        val actions = mutableListOf<ActiveSessionAction>()
        rule.setContent {
            var live by remember { mutableStateOf(LiveTrackingState(isRunning = true, distanceM = 1234.0)) }
            BitacoraTheme {
                ActiveSessionScreen(
                    state = ActiveSessionUiState(live = live, elapsedMs = 61_000),
                    onAction = { a ->
                        actions += a
                        if (a == ActiveSessionAction.TogglePause) live = live.copy(isPaused = !live.isPaused)
                    },
                )
            }
        }
        rule.onNodeWithTag("active_distance").assertExists()
        rule.onNodeWithText("1.2 km").assertExists()
        rule.onNodeWithText("Pausar").assertExists()
        rule.onNodeWithTag("active_pause").performClick()
        rule.onNodeWithText("Reanudar").assertExists()
        rule.onNodeWithTag("active_paused").assertExists()
        rule.onNodeWithTag("active_finish").performClick()
        rule.onNodeWithTag("active_voice").performClick()
        rule.onNodeWithTag("active_highlight").performClick()
        rule.onNodeWithTag("active_camera").performClick() // PLAN 5.2
        rule.onNodeWithTag("active_marker").performClick()
        rule.onNodeWithTag("marker_save").performClick()
        rule.onNodeWithTag("active_transport").performClick()
        rule.onNodeWithTag("transport_CAR").performClick()
        assertEquals(
            listOf(ActiveSessionAction.TogglePause, ActiveSessionAction.Finish, ActiveSessionAction.VoiceNote, ActiveSessionAction.Highlight, ActiveSessionAction.Camera, ActiveSessionAction.Marker(""), ActiveSessionAction.ChangeTransport(Transport.CAR)),
            actions,
        )
    }

    @Test
    fun `finish screen offers segment, trip and cancel, then shows the result`() {
        var closedSegment = 0
        var closedTrip = 0
        var cancelled = 0
        rule.setContent {
            BitacoraTheme {
                FinishSegmentScreen(
                    state = FinishSegmentUiState(liveDistanceM = 500.0, pointCount = 42),
                    onCancel = { cancelled++ },
                    onCloseSegment = { closedSegment++ },
                    onCloseTrip = { closedTrip++ },
                    onDone = {},
                )
            }
        }
        rule.onNodeWithTag("finish_segment").performScrollTo().performClick()
        rule.onNodeWithTag("finish_trip").performScrollTo().performClick()
        rule.onNodeWithTag("finish_cancel").performScrollTo().performClick()
        assertEquals(1, closedSegment)
        assertEquals(1, closedTrip)
        assertEquals(1, cancelled)
    }

    @Test
    fun `finish screen offers the one minute video and reflects its progress, result and failure`() {
        var created = 0
        var opened: String? = null
        var render by mutableStateOf(com.bitacoraviaje.app.video.RenderStatus())
        val closed = com.bitacoraviaje.core.model.Segment("s1", "t1", null, com.bitacoraviaje.core.model.SegmentType.STAGE, null, "Etapa", null, 0L, 1000L, "UTC", "WALK", com.bitacoraviaje.core.model.RecordingMode.FULL, false, com.bitacoraviaje.core.model.SegmentStatus.FINISHED)
        rule.setContent {
            BitacoraTheme {
                com.bitacoraviaje.app.ui.finish.FinishSegmentScreen(
                    state = com.bitacoraviaje.app.ui.finish.FinishSegmentUiState(segment = closed, closed = closed),
                    onCancel = {}, onCloseSegment = {}, onCloseTrip = {}, onDone = {},
                    render = render, onCreateVideo = { created++ }, onOpenVideo = { opened = it },
                )
            }
        }
        rule.onNodeWithTag("video_create").performClick()
        assertEquals(1, created)
        render = com.bitacoraviaje.app.video.RenderStatus(running = true, progress = 42)
        rule.onNodeWithText("Creando el video… 42 %").assertIsDisplayed()
        render = com.bitacoraviaje.app.video.RenderStatus(finished = true, resultUri = "content://media/external/video/1", durationMs = 59_000, elapsedMs = 95_000)
        rule.onNodeWithText("Video listo (59 s, generado en 95 s)").assertIsDisplayed()
        rule.onNodeWithTag("video_open").performClick()
        assertEquals("content://media/external/video/1", opened)
        render = com.bitacoraviaje.app.video.RenderStatus(failed = true, error = "codec")
        rule.onNodeWithTag("video_retry").assertIsDisplayed()
    }

    @Test
    fun `video create screen edits parameters, generates and edits the plan and renders`() {
        val events = mutableListOf<String>()
        var ui by mutableStateOf(com.bitacoraviaje.app.ui.video.VideoCreateUiState())
        var render by mutableStateOf(com.bitacoraviaje.app.video.RenderStatus())
        val t0 = 1_760_000_000_000L
        val plan = com.bitacoraviaje.core.videoplan.Planner.plan(
            com.bitacoraviaje.core.videoplan.PlanInput("t", "Patagonia", null, listOf(com.bitacoraviaje.core.videoplan.PlanSegment("s1", "Etapa 1", "2026-03-14", t0, t0 + 3_600_000, media = (0 until 4).map { i -> com.bitacoraviaje.core.videoplan.PlanMedia("m$i", false, t0 + i * 600_000L) }))),
            com.bitacoraviaje.core.videoplan.PlanParams(60),
        )
        rule.setContent {
            BitacoraTheme {
                com.bitacoraviaje.app.ui.video.VideoCreateScreen(
                    state = ui, render = render, onBack = {},
                    onParams = { f -> ui = ui.copy(params = f(ui.params)); events += "params" },
                    onPickMusic = { events += "music" }, onGenerate = { events += "generate"; ui = ui.copy(plan = plan) },
                    onRemove = { events += "remove:$it" }, onMove = { i, d -> events += "move:$i:$d" }, onRender = { events += "render" }, onCancel = { events += "cancel" },
                    onSavePlan = { events += "save" }, onOpen = { events += "open:$it" }, onShare = { events += "share" },
                )
            }
        }
        fun scrollTo(tag: String) = rule.onNodeWithTag("video_list").performScrollToNode(hasTestTag(tag))
        scrollTo("video_plan_empty")
        rule.onNodeWithTag("video_plan_empty").assertIsDisplayed()
        scrollTo("video_duration_300")
        rule.onNodeWithTag("video_duration_300").performClick()
        assertEquals(300, ui.params.durationS)
        scrollTo("video_voice_both")
        rule.onNodeWithTag("video_voice_both").performClick()
        assertEquals("both", ui.params.voice)
        scrollTo("video_generate")
        rule.onNodeWithTag("video_generate").performClick()
        scrollTo("video_plan_summary")
        rule.onNodeWithTag("video_plan_summary").assertIsDisplayed()
        scrollTo("scene_remove_1")
        rule.onNodeWithTag("scene_remove_1").performClick()
        scrollTo("scene_down_0")
        rule.onNodeWithTag("scene_down_0").performClick()
        scrollTo("video_render")
        rule.onNodeWithTag("video_render").performClick()
        assertTrue(events.containsAll(listOf("params", "generate", "remove:1", "move:0:1", "render")), events.toString())
        render = com.bitacoraviaje.app.video.RenderStatus(running = true, progress = 55)
        scrollTo("video_running")
        rule.onNodeWithText("Renderizando… 55 %").assertIsDisplayed()
        render = com.bitacoraviaje.app.video.RenderStatus(finished = true, resultUri = "content://media/1", durationMs = 180_000, elapsedMs = 240_000, skipped = 2)
        scrollTo("video_done")
        rule.onNodeWithText("Video listo (180 s, generado en 240 s) · 2 fotos sin archivo").assertIsDisplayed()
        scrollTo("video_open")
        rule.onNodeWithTag("video_open").performClick()
        assertTrue("open:content://media/1" in events)
    }

    // Backlog D1: three-dot menu per trip and the folded archived section.

    private fun trip(id: String, title: String, status: com.bitacoraviaje.core.model.TripStatus, archived: Boolean = false) =
        com.bitacoraviaje.core.model.Trip(id = id, title = title, createdUtcMs = 1L, status = status, archived = archived)

    @Test
    fun `home trip menu blocks archive and delete only while that trip's segment is recording`() {
        val actions = ArrayList<com.bitacoraviaje.app.ui.home.TripAction>()
        val state = com.bitacoraviaje.app.ui.home.HomeUiState(
            trips = listOf(trip("a", "Activo", com.bitacoraviaje.core.model.TripStatus.ACTIVE), trip("f", "Terminado", com.bitacoraviaje.core.model.TripStatus.FINISHED)),
            openSegment = com.bitacoraviaje.core.model.Segment("s1", "a", null, com.bitacoraviaje.core.model.SegmentType.STAGE, null, "Etapa", null, 0L, null, "UTC", "WALK", com.bitacoraviaje.core.model.RecordingMode.FULL, false, com.bitacoraviaje.core.model.SegmentStatus.ACTIVE),
        )
        rule.setContent { BitacoraTheme { com.bitacoraviaje.app.ui.home.HomeScreen(state = state, snackbar = SnackbarHostState(), onOpenSettings = {}, onNewRecording = {}, onResumeActive = {}, onTripAction = { actions += it }) } }
        rule.onNodeWithTag("trip_menu_a").performClick()
        rule.onNodeWithTag("trip_archive_a").assertIsNotEnabled()
        rule.onNodeWithTag("trip_delete_a").assertIsNotEnabled()
        rule.onNodeWithTag("trip_video_a").performClick()
        rule.onNodeWithTag("trip_menu_f").performClick()
        rule.onNodeWithTag("trip_archive_f").assertIsEnabled()
        rule.onNodeWithTag("trip_delete_f").assertIsEnabled()
        rule.onNodeWithTag("trip_export_f").performClick()
        assertEquals(listOf(com.bitacoraviaje.app.ui.home.TripAction.Video("a"), com.bitacoraviaje.app.ui.home.TripAction.Export("f")), actions)
    }

    @Test
    fun `home trip menu reports rename, archive, delete and the folded archived section`() {
        val actions = ArrayList<com.bitacoraviaje.app.ui.home.TripAction>()
        val state = com.bitacoraviaje.app.ui.home.HomeUiState(
            trips = listOf(trip("f", "Terminado", com.bitacoraviaje.core.model.TripStatus.FINISHED)),
            archived = listOf(trip("z", "Guardado", com.bitacoraviaje.core.model.TripStatus.FINISHED, archived = true)),
        )
        rule.setContent { BitacoraTheme { com.bitacoraviaje.app.ui.home.HomeScreen(state = state, snackbar = SnackbarHostState(), onOpenSettings = {}, onNewRecording = {}, onResumeActive = {}, onTripAction = { actions += it }) } }
        rule.onNodeWithText("Guardado").assertDoesNotExist()
        rule.onNodeWithTag("home_archived_toggle").performClick()
        rule.onNodeWithText("Guardado").assertIsDisplayed()

        rule.onNodeWithTag("trip_menu_f").performClick()
        rule.onNodeWithTag("trip_archive_f").assertIsEnabled().performClick()
        rule.onNodeWithTag("trip_menu_f").performClick()
        rule.onNodeWithTag("trip_delete_f").performClick()
        rule.onNodeWithTag("trip_delete_confirm").performClick()
        rule.onNodeWithTag("trip_menu_f").performClick()
        rule.onNodeWithTag("trip_rename_f").performClick()
        rule.onNodeWithTag("trip_rename_field").performTextClearance()
        rule.onNodeWithTag("trip_rename_field").performTextInput("Nuevo nombre")
        rule.onNodeWithTag("trip_rename_save").performClick()

        rule.onNodeWithTag("trip_menu_z").performClick()
        rule.onNodeWithText("Desarchivar").assertExists()
        rule.onNodeWithTag("trip_archive_z").performClick()

        assertEquals(
            listOf(
                com.bitacoraviaje.app.ui.home.TripAction.Archive("f", true),
                com.bitacoraviaje.app.ui.home.TripAction.Delete("f"),
                com.bitacoraviaje.app.ui.home.TripAction.Rename("f", "Nuevo nombre"),
                com.bitacoraviaje.app.ui.home.TripAction.Archive("z", false),
            ),
            actions,
        )
    }
}
