package com.bitacoraviaje.app.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.LiveTrackingState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 5.3: the widget shows the recording state and offers voice note + marker (or "new session" when idle). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BitacoraWidgetTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `model follows the live state`() {
        val t0 = 1_760_000_000_000L
        assertEquals(WidgetModel(R.string.widget_idle), WidgetModel.of(LiveTrackingState(), false, t0))
        val running = LiveTrackingState(segmentId = "s", isRunning = true, startedUtcMs = t0 - 61_000, distanceM = 1234.0)
        assertEquals(WidgetModel(R.string.widget_running, "1 min", "1.2 km", recording = true), WidgetModel.of(running, true, t0))
        assertEquals(R.string.widget_paused, WidgetModel.of(running.copy(isPaused = true, pausedSinceUtcMs = t0), true, t0).statusRes)
        assertEquals(R.string.widget_resting, WidgetModel.of(running.copy(isResting = true), true, t0).statusRes)
        assertEquals(WidgetModel(R.string.widget_interrupted, interrupted = true), WidgetModel.of(LiveTrackingState(), hasOpenSegment = true, nowUtcMs = t0), "service gone, stage open: offer to go back")
    }

    @Test
    fun `recording widget offers voice note and marker, idle widget offers a new session`() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        provideComposable { WidgetContent(WidgetModel(R.string.widget_running, "42 min", "3.1 km", recording = true)) }
        onNode(hasText("En curso · 42 min · 3.1 km")).assertExists()
        onNode(hasTestTag("widget_voice")).assertExists()
        onNode(hasText("Nota de voz")).assertExists()
        onNode(hasTestTag("widget_marker")).assertExists()
        onNode(hasTestTag("widget_start")).assertDoesNotExist()
    }

    @Test
    fun `idle widget shows the idle line and the start button`() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        provideComposable { WidgetContent(WidgetModel(R.string.widget_idle)) }
        onNode(hasText("Sin registro en curso")).assertExists()
        onNode(hasTestTag("widget_start")).assertExists()
        onNode(hasTestTag("widget_marker")).assertDoesNotExist()
    }

    @Test
    fun `interrupted recording offers only the way back to the app`() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        provideComposable { WidgetContent(WidgetModel(R.string.widget_interrupted, interrupted = true)) }
        onNode(hasText("Registro interrumpido")).assertExists()
        onNode(hasTestTag("widget_back")).assertExists()
        onNode(hasTestTag("widget_marker")).assertDoesNotExist()
        onNode(hasTestTag("widget_start")).assertDoesNotExist()
    }
}
