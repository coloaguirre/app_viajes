package com.bitacoraviaje.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * On boot, if a segment was left open, post "tap to resume". Starting the
 * location foreground service from here is not allowed on recent Android, and
 * we would not want to: the user decides whether the trip goes on.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: TrackingRepository

    @Inject lateinit var notifications: TrackingNotificationFactory

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        try {
            // Receivers get ~10 s; one indexed query is well within budget.
            val open = runBlocking { repository.getOpenSegment() }
            if (open != null) notifications.notifyResumeNeeded()
        } finally {
            pending.finish()
        }
    }
}
