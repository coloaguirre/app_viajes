package com.bitacoraviaje.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bitacoraviaje.app.MainActivity
import com.bitacoraviaje.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingNotificationFactory @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_tracking), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_tracking_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: LiveTrackingState, nowUtcMs: Long): Notification {
        val title = if (state.isPaused) context.getString(R.string.notif_paused) else context.getString(R.string.notif_recording)
        val text = context.getString(R.string.notif_summary, formatElapsed(state.elapsedMs(nowUtcMs)), formatKm(state.distanceM))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = if (state.isPaused) {
            NotificationCompat.Action(0, context.getString(R.string.action_resume), servicePending(1, TrackingService.ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, context.getString(R.string.action_pause), servicePending(2, TrackingService.ACTION_PAUSE))
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(toggle)
            // "Terminar" opens the app to confirm (DISENO §6.1): never ends a segment from the shade.
            .addAction(NotificationCompat.Action(0, context.getString(R.string.action_finish), open))
            .build()
    }

    fun notify(state: LiveTrackingState, nowUtcMs: Long) = manager.notify(NOTIFICATION_ID, build(state, nowUtcMs))

    private fun servicePending(code: Int, action: String): PendingIntent = PendingIntent.getService(
        context, code, Intent(context, TrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1001

        fun formatElapsed(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "%d h %02d min".format(h, m) else "%d min".format(m)
        }

        fun formatKm(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)
    }
}
