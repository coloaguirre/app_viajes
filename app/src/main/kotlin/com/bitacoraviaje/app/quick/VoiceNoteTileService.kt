package com.bitacoraviaje.app.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.app.voice.VoiceNoteActivity
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile "Nota de voz" (DISENO §8.2, §14.3): opens the translucent recording bubble,
 * which works over the lock screen and makes the microphone usable (the service alone could not).
 * Lit only while a stage is open: without one the bubble can only say "no recording" (review I4).
 */
@AndroidEntryPoint
class VoiceNoteTileService : TileService() {
    @Inject lateinit var stateHolder: TrackingStateHolder

    @Inject lateinit var repository: TrackingRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        scope.launch {
            val open = stateHolder.state.value.isRunning || withContext(Dispatchers.IO) { repository.getOpenSegment() != null }
            qsTile?.apply {
                state = if (open) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (open) R.string.tile_voice_ready else R.string.tile_voice_no_session)
                updateTile()
            }
        }
    }

    // The Intent overload is the only one below API 34; the PendingIntent one is used from 34 on.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = VoiceNoteActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
