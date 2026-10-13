package com.bitacoraviaje.app.quick

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bitacoraviaje.app.voice.VoiceNoteActivity

/**
 * Quick Settings tile "Nota de voz" (DISENO §8.2, §14.3): opens the translucent recording bubble,
 * which works over the lock screen and makes the microphone usable (the service alone could not).
 */
class VoiceNoteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    // The Intent overload is the only one below API 34; the PendingIntent one is used from 34 on.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = VoiceNoteActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
