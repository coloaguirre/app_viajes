package com.bitacoraviaje.app.quick

import android.content.Intent
import com.bitacoraviaje.app.tracking.LiveTrackingState

/** What the tracking tile shows and does for a given live state (pure, testable). */
data class TrackingTileModel(val active: Boolean, val labelRes: Int, val action: Action) {
    enum class Action { OPEN_NEW_SESSION, PAUSE, RESUME }

    companion object {
        fun of(live: LiveTrackingState, hasOpenSegment: Boolean): TrackingTileModel = when {
            // No recording (or the service is gone after a kill): the tile opens the app to start or resume one.
            !live.isRunning || !hasOpenSegment -> TrackingTileModel(false, com.bitacoraviaje.app.R.string.tile_tracking_start, Action.OPEN_NEW_SESSION)
            live.isPaused || live.isResting -> TrackingTileModel(true, com.bitacoraviaje.app.R.string.tile_tracking_resume, Action.RESUME)
            else -> TrackingTileModel(true, com.bitacoraviaje.app.R.string.tile_tracking_pause, Action.PAUSE)
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
