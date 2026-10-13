package com.bitacoraviaje.app.quick

import android.content.Intent
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.LiveTrackingState

/**
 * What the tracking tile shows and does for a given live state (pure, testable). [active] is the
 * toggle's visual state: lit only while the recording is really running (review M5 of PR #14).
 */
data class TrackingTileModel(val active: Boolean, val labelRes: Int, val action: Action) {
    enum class Action { OPEN_NEW_SESSION, OPEN_APP, PAUSE, RESUME }

    companion object {
        fun of(live: LiveTrackingState, hasOpenSegment: Boolean): TrackingTileModel = when {
            // The service is gone (killed, rebooted) but a stage is still open: back to the app, whose
            // Home offers to resume it (DISENO §6.9). Never a new session on top of an open one.
            !live.isRunning && hasOpenSegment -> TrackingTileModel(false, R.string.tile_tracking_back, Action.OPEN_APP)
            !live.isRunning -> TrackingTileModel(false, R.string.tile_tracking_start, Action.OPEN_NEW_SESSION)
            live.isPaused || live.isResting -> TrackingTileModel(false, R.string.tile_tracking_resume, Action.RESUME)
            else -> TrackingTileModel(true, R.string.tile_tracking_pause, Action.PAUSE)
        }
    }
}

/** Intent actions the launcher shortcuts and tiles send to MainActivity (DISENO §14.3). */
object ShortcutActions {
    const val VOICE_NOTE = "com.bitacoraviaje.app.action.VOICE_NOTE"
    const val NEW_SESSION = "com.bitacoraviaje.app.action.NEW_SESSION"

    enum class Kind { VOICE_NOTE, NEW_SESSION }

    fun of(intent: Intent?): Kind? = when (intent?.action) {
        VOICE_NOTE -> Kind.VOICE_NOTE
        NEW_SESSION -> Kind.NEW_SESSION
        else -> null
    }
}
