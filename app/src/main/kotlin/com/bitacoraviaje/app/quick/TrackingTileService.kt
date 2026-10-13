package com.bitacoraviaje.app.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bitacoraviaje.app.MainActivity
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Iniciar/Pausar" (DISENO §14.3): pauses or resumes the running recording
 * (the foreground service is already up, so a plain command intent is allowed) and, when nothing
 * is recording, opens the app on the new-session screen (a location foreground service cannot be
 * started from a tile).
 */
@AndroidEntryPoint
class TrackingTileService : TileService() {
    @Inject lateinit var stateHolder: TrackingStateHolder

    @Inject lateinit var controller: TrackingController

    @Inject lateinit var repository: TrackingRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var model: TrackingTileModel = TrackingTileModel(false, com.bitacoraviaje.app.R.string.tile_tracking_start, TrackingTileModel.Action.OPEN_NEW_SESSION)

    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch {
            stateHolder.state.collectLatest { live ->
                val hasOpen = if (live.isRunning) true else withIo { repository.getOpenSegment() != null }
                model = TrackingTileModel.of(live, hasOpen)
                qsTile?.apply {
                    state = if (model.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    // Below API 29 there is no subtitle: the label itself says what the tap does.
                    if (Build.VERSION.SDK_INT >= 29) {
                        label = getString(com.bitacoraviaje.app.R.string.tile_tracking)
                        subtitle = getString(model.labelRes)
                    } else {
                        label = getString(model.labelRes)
                    }
                    updateTile()
                }
            }
        }
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    // The Intent overload is the only one below API 34; the PendingIntent one is used from 34 on.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        android.util.Log.i("Bitacora", "tracking tile click action=${model.action} live=${stateHolder.state.value.let { "running=${it.isRunning} paused=${it.isPaused}" }}")
        when (model.action) {
            TrackingTileModel.Action.PAUSE -> controller.pause()
            TrackingTileModel.Action.RESUME -> controller.resume()
            TrackingTileModel.Action.OPEN_NEW_SESSION -> {
                val intent = Intent(this, MainActivity::class.java).setAction(ShortcutActions.NEW_SESSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    override fun onDestroy() {
        listening?.cancel()
        super.onDestroy()
    }
}
