package com.bitacoraviaje.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        rule.onNodeWithTag("active_marker").performClick()
        rule.onNodeWithTag("marker_save").performClick()
        rule.onNodeWithTag("active_transport").performClick()
        rule.onNodeWithTag("transport_CAR").performClick()
        assertEquals(
            listOf(ActiveSessionAction.TogglePause, ActiveSessionAction.Finish, ActiveSessionAction.VoiceNote, ActiveSessionAction.Highlight, ActiveSessionAction.Marker(""), ActiveSessionAction.ChangeTransport(Transport.CAR)),
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
}
