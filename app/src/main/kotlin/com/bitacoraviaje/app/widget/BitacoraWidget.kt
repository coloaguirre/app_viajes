package com.bitacoraviaje.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bitacoraviaje.app.MainActivity
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.quick.ShortcutActions
import com.bitacoraviaje.app.tracking.LiveTrackingState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.app.voice.NotesController
import com.bitacoraviaje.app.voice.VoiceNoteActivity
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the widget shows (pure, testable): a status line and which buttons make sense. */
data class WidgetModel(val statusRes: Int, val elapsed: String? = null, val distance: String? = null, val recording: Boolean = false) {
    companion object {
        fun of(live: LiveTrackingState, hasOpenSegment: Boolean, nowUtcMs: Long): WidgetModel {
            val elapsed = TrackingNotificationFactory.formatElapsed(live.elapsedMs(nowUtcMs))
            val distance = TrackingNotificationFactory.formatKm(live.distanceM)
            return when {
                !live.isRunning || !hasOpenSegment -> WidgetModel(R.string.widget_idle)
                live.isResting -> WidgetModel(R.string.widget_resting, elapsed, distance, recording = true)
                live.isPaused -> WidgetModel(R.string.widget_paused, elapsed, distance, recording = true)
                else -> WidgetModel(R.string.widget_running, elapsed, distance, recording = true)
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun stateHolder(): TrackingStateHolder
    fun tracking(): TrackingRepository
    fun notes(): NotesController
}

/** Home-screen widget (DISENO §14.3, PLAN 5.3): live status plus "Nota de voz" and "Marcar"; "Nuevo registro" when idle. */
class BitacoraWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val live = entry.stateHolder().state.value
        val hasOpen = live.isRunning || runCatching { entry.tracking().getOpenSegment() != null }.getOrDefault(false)
        val model = WidgetModel.of(live, hasOpen, System.currentTimeMillis())
        provideContent { GlanceTheme { WidgetContent(model) } }
    }
}

@Composable
fun WidgetContent(model: WidgetModel) {
    Scaffold(modifier = GlanceModifier.fillMaxWidth(), titleBar = {}) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = androidx.glance.LocalContext.current.getString(R.string.widget_label),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
            )
            val ctx = androidx.glance.LocalContext.current
            val status = when {
                model.elapsed != null && model.distance != null && model.statusRes == R.string.widget_running -> ctx.getString(model.statusRes, model.elapsed, model.distance)
                model.elapsed != null -> ctx.getString(model.statusRes, model.elapsed)
                else -> ctx.getString(model.statusRes)
            }
            Text(text = status, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(top = 2.dp, bottom = 10.dp).semantics { contentDescription = status })
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetButton(ctx.getString(R.string.widget_voice), actionStartActivity<VoiceNoteActivity>(), "widget_voice")
                Spacer(GlanceModifier.width(8.dp))
                if (model.recording) {
                    WidgetButton(ctx.getString(R.string.widget_marker), actionRunCallback<MarkerAction>(), "widget_marker")
                } else {
                    WidgetButton(ctx.getString(R.string.widget_start), androidx.glance.appwidget.action.actionStartActivity(Intent(ctx, MainActivity::class.java).setAction(ShortcutActions.NEW_SESSION)), "widget_start")
                }
            }
        }
    }
}

@Composable
private fun WidgetButton(label: String, action: androidx.glance.action.Action, tag: String) {
    androidx.glance.appwidget.components.FilledButton(text = label, onClick = action, modifier = GlanceModifier.semantics { contentDescription = tag })
}

/** "Marcar" from the widget: a marker without text at the current position, like the active screen's button. */
class MarkerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val saved = entry.notes().addMarker(null)
        Log.i(TAG, "widget marker saved=${saved != null}")
        // ActionCallback runs off the main thread; a Toast needs a Looper.
        if (saved != null) android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context, R.string.widget_marker_saved, android.widget.Toast.LENGTH_SHORT).show() }
        BitacoraWidget().updateAll(context)
    }

    private companion object { const val TAG = "Bitacora" }
}

class BitacoraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BitacoraWidget()
}

/** Called by the tracking service whenever its notification changes: the widget follows the same cadence. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        scope.launch { runCatching { BitacoraWidget().updateAll(context.applicationContext) }.onFailure { Log.w("Bitacora", "widget refresh failed: ${it.message}") } }
    }
}
