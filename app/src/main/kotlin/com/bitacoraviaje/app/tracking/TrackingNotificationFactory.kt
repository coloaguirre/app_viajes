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
        val title = when {
            state.isPaused -> context.getString(R.string.notif_paused)
            state.isResting -> context.getString(R.string.notif_resting)
            else -> context.getString(R.string.notif_recording)
        }
        var text = context.getString(R.string.notif_summary, formatElapsed(state.elapsedMs(nowUtcMs)), formatKm(state.distanceM))
        if (state.profile == com.bitacoraviaje.core.model.SamplingProfile.SAVER) text = text + " · " + context.getString(R.string.notif_saver)
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
            .addAction(
                NotificationCompat.Action(
                    0, context.getString(R.string.action_voice_note),
                    PendingIntent.getActivity(context, 4, com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            // "Terminar" opens the app to confirm (DISENO §6.1): never ends a segment from the shade.
            .addAction(NotificationCompat.Action(0, context.getString(R.string.action_finish), open))
            .build()
    }

    fun notify(state: LiveTrackingState, nowUtcMs: Long) {
        manager.notify(NOTIFICATION_ID, build(state, nowUtcMs))
        com.bitacoraviaje.app.widget.WidgetUpdater.refresh(context) // the home-screen widget follows the notification (PLAN 5.3)
    }

    /**
     * After a reboot a location foreground service cannot restart itself (DISENO §6.9):
     * tell the user the recording was interrupted and let a tap bring the app back.
     */
    fun notifyResumeNeeded() {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.notif_interrupted_title))
            .setContentText(context.getString(R.string.notif_interrupted_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(RESUME_NOTIFICATION_ID, n)
    }

    fun cancelResumeNeeded() = manager.cancel(RESUME_NOTIFICATION_ID)

    private fun servicePending(code: Int, action: String): PendingIntent = PendingIntent.getService(
        context, code, Intent(context, TrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1001
        const val RESUME_NOTIFICATION_ID = 1002

        fun formatElapsed(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "%d h %02d min".format(h, m) else "%d min".format(m)
        }

        fun formatKm(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)
    }
}
