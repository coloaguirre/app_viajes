package com.bitacoraviaje.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
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
data class WidgetModel(
    val statusRes: Int,
    val elapsed: String? = null,
    val distance: String? = null,
    val recording: Boolean = false,
    /** The service died with a stage open: offer to go back to the app (Home resumes it). */
    val interrupted: Boolean = false,
) {
    companion object {
        fun of(live: LiveTrackingState, hasOpenSegment: Boolean, nowUtcMs: Long): WidgetModel = when {
            !live.isRunning && hasOpenSegment -> WidgetModel(R.string.widget_interrupted, interrupted = true)
            !live.isRunning -> WidgetModel(R.string.widget_idle)
            else -> {
                val elapsed = TrackingNotificationFactory.formatElapsed(live.elapsedMs(nowUtcMs))
                val distance = TrackingNotificationFactory.formatKm(live.distanceM)
                when {
                    live.isResting -> WidgetModel(R.string.widget_resting, elapsed, distance, recording = true)
                    live.isPaused -> WidgetModel(R.string.widget_paused, elapsed, distance, recording = true)
                    else -> WidgetModel(R.string.widget_running, elapsed, distance, recording = true)
                }
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
        provideContent {
            // Read inside the composition: a live session recomposes on every state change instead of
            // showing the value captured when it started (review I1 of PR #14).
            val live by entry.stateHolder().state.collectAsState()
            val hasOpen by androidx.compose.runtime.produceState(initialValue = live.isRunning, live.isRunning) {
                value = live.isRunning || runCatching { entry.tracking().getOpenSegment() != null }.getOrDefault(false)
            }
            GlanceTheme { WidgetContent(WidgetModel.of(live, hasOpen, System.currentTimeMillis())) }
        }
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
            Text(text = status, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(top = 2.dp, bottom = 10.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when {
                    model.interrupted -> WidgetButton(ctx.getString(R.string.widget_back), androidx.glance.appwidget.action.actionStartActivity(Intent(ctx, MainActivity::class.java)), "widget_back")
                    else -> WidgetButton(ctx.getString(R.string.widget_voice), actionStartActivity<VoiceNoteActivity>(), "widget_voice")
                }
                Spacer(GlanceModifier.width(8.dp))
                when {
                    model.recording -> WidgetButton(ctx.getString(R.string.widget_marker), actionRunCallback<MarkerAction>(), "widget_marker")
                    model.interrupted -> Unit
                    else -> WidgetButton(ctx.getString(R.string.widget_start), androidx.glance.appwidget.action.actionStartActivity(Intent(ctx, MainActivity::class.java).setAction(ShortcutActions.NEW_SESSION)), "widget_start")
                }
            }
        }
    }
}

/** The label is what TalkBack reads; the tag is only for tests (review I3). */
@Composable
private fun WidgetButton(label: String, action: androidx.glance.action.Action, tag: String) {
    androidx.glance.appwidget.components.FilledButton(text = label, onClick = action, modifier = GlanceModifier.semantics { testTag = tag })
}

/** "Marcar" from the widget: a marker without text at the current position, like the active screen's button. */
class MarkerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val saved = entry.notes().addMarker(null)
        Log.i(TAG, "widget marker saved=${saved != null}")
        // ActionCallback runs off the main thread; a Toast needs a Looper. A null means no open stage (review M3).
        val message = if (saved != null) R.string.widget_marker_saved else R.string.voice_no_session
        android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
        BitacoraWidget().updateAll(context)
    }

    private companion object { const val TAG = "Bitacora" }
}

class BitacoraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BitacoraWidget()
}

/** Called by the tracking service whenever its notification changes (every 15 s with new points, every 60 s by its ticker, and on each state change). */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        scope.launch { runCatching { BitacoraWidget().updateAll(context.applicationContext) }.onFailure { Log.w("Bitacora", "widget refresh failed: ${it.message}") } }
    }
}
