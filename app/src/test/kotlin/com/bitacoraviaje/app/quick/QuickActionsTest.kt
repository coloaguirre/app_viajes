package com.bitacoraviaje.app.quick

import android.content.Intent
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.LiveTrackingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 5.3: what the tracking tile and the launcher shortcuts do in every state (pure; Robolectric only for Intent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickActionsTest {
    @Test
    fun `tracking tile starts when idle, pauses when running and resumes when paused or resting`() {
        assertEquals(TrackingTileModel(false, R.string.tile_tracking_start, TrackingTileModel.Action.OPEN_NEW_SESSION), TrackingTileModel.of(LiveTrackingState(), hasOpenSegment = false))
        // The service died (phone killed it) but a stage is open: back to the app, never a new session on top (review I2).
        assertEquals(TrackingTileModel(false, R.string.tile_tracking_back, TrackingTileModel.Action.OPEN_APP), TrackingTileModel.of(LiveTrackingState(isRunning = false), hasOpenSegment = true))
        val running = LiveTrackingState(segmentId = "s", isRunning = true, startedUtcMs = 1)
        assertEquals(TrackingTileModel(true, R.string.tile_tracking_pause, TrackingTileModel.Action.PAUSE), TrackingTileModel.of(running, true))
        // Paused or resting: the toggle is off (review M5) and the tap resumes.
        assertEquals(TrackingTileModel(false, R.string.tile_tracking_resume, TrackingTileModel.Action.RESUME), TrackingTileModel.of(running.copy(isPaused = true), true))
        assertEquals(TrackingTileModel.Action.RESUME, TrackingTileModel.of(running.copy(isResting = true), true).action)
    }

    @Test
    fun `shortcut actions are recognised and anything else is ignored`() {
        assertEquals(ShortcutActions.Kind.VOICE_NOTE, ShortcutActions.of(Intent(ShortcutActions.VOICE_NOTE)))
        assertEquals(ShortcutActions.Kind.NEW_SESSION, ShortcutActions.of(Intent(ShortcutActions.NEW_SESSION)))
        assertNull(ShortcutActions.of(Intent(Intent.ACTION_MAIN)))
        assertNull(ShortcutActions.of(null))
    }
}
