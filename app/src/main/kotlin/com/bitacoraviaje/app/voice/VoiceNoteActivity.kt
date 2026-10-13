package com.bitacoraviaje.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Translucent recording bubble (DISENO §8.2). Opened from the notification action, the
 * active screen, a Quick Settings tile or a shortcut; works over the lock screen.
 * Being an activity in the foreground is what makes the microphone usable when the
 * service was started long ago.
 */
@AndroidEntryPoint
class VoiceNoteActivity : ComponentActivity() {
    @Inject lateinit var recorder: VoiceRecorder

    @Inject lateinit var controller: NotesController

    @Inject lateinit var tracking: TrackingRepository

    private var segmentId: String? = null
    private var tripId: String? = null
    private var noteId: String = ""
    private var startedUtcMs = 0L
    private var state by mutableStateOf(State.ASKING)
    private var elapsedMs by mutableLongStateOf(0L)
    private var level by mutableFloatStateOf(0f)
    private var errorRes by mutableStateOf<Int?>(null)

    private enum class State { ASKING, RECORDING, SAVING, DONE }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else { errorRes = R.string.voice_permission_denied; state = State.DONE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            BitacoraTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 6.dp,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val err = errorRes
                            when {
                                err != null -> {
                                    Text(stringResource(err), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("voice_error"))
                                    Button(onClick = { finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.voice_close)) }
                                }
                                state == State.ASKING -> {
                                    Text(stringResource(R.string.voice_rationale_title), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.voice_rationale_body), style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth().testTag("voice_allow")) { Text(stringResource(R.string.voice_allow)) }
                                    OutlinedButton(onClick = { finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.active_cancel)) }
                                }
                                state == State.RECORDING -> {
                                    Text(stringResource(R.string.voice_recording), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("voice_recording"))
                                    Text(TrackingNotificationFactory.formatElapsed(elapsedMs).let { if (elapsedMs < 60_000) "${elapsedMs / 1000} s" else it }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("voice_timer"))
                                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(onClick = { cancelRecording() }, modifier = Modifier.weight(1f).height(56.dp).testTag("voice_cancel")) { Text(stringResource(R.string.active_cancel)) }
                                        Button(onClick = { finishRecording() }, modifier = Modifier.weight(1f).height(56.dp).testTag("voice_done")) { Text(stringResource(R.string.voice_done)) }
                                    }
                                }
                                else -> Text(stringResource(if (startedUtcMs == 0L) R.string.voice_preparing else R.string.voice_saving), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                LaunchedEffect(state) {
                    while (state == State.RECORDING) {
                        elapsedMs = System.currentTimeMillis() - startedUtcMs
                        level = (recorder.amplitude() / 32767f).coerceIn(0f, 1f)
                        delay(200)
                    }
                }
            }
        }
        lifecycleScope.launch {
            val open = tracking.getOpenSegment()
            if (open == null) {
                errorRes = R.string.voice_no_session
                state = State.DONE
                return@launch
            }
            segmentId = open.id
            tripId = open.tripId
            if (ContextCompat.checkSelfPermission(this@VoiceNoteActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        }
    }

    private fun startRecording() {
        val trip = tripId ?: return
        noteId = controller.newNoteId()
        val file = VoiceRecorder.fileFor(this, trip, noteId)
        if (!recorder.hasFreeSpace(file)) {
            errorRes = R.string.voice_no_space
            state = State.DONE
            return
        }
        state = State.SAVING // "preparing" while the recorder starts off the main thread
        // 5-minute cap reached: save what was recorded instead of letting a later stop() discard it (review B1).
        recorder.onMaxDurationReached = { runOnUiThread { if (state == State.RECORDING) finishRecording() } }
        lifecycleScope.launch {
            // MediaRecorder.start() can block for seconds when the audio HAL is missing or busy
            // (measured: ANR on the emulator without audio). Never on the main thread, and bounded.
            val started = withTimeoutOrNull(START_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runCatching { recorder.start(file) }.onFailure { Log.w(TAG, "recorder start failed: ${it.javaClass.simpleName} ${it.message}") }.isSuccess }
            } ?: false
            if (!started) {
                withContext(Dispatchers.IO) { recorder.cancel() }
                file.delete()
                errorRes = R.string.voice_mic_failed
                state = State.DONE
                return@launch
            }
            startedUtcMs = System.currentTimeMillis()
            vibrate(longArrayOf(0, 40))
            state = State.RECORDING
        }
    }

    private fun finishRecording() {
        val seg = segmentId ?: return
        val trip = tripId ?: return
        state = State.SAVING
        val wallClockMs = System.currentTimeMillis() - startedUtcMs
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { recorder.stop() }
            vibrate(longArrayOf(0, 40, 80, 40))
            if (!ok) {
                VoiceRecorder.fileFor(this@VoiceNoteActivity, trip, noteId).delete()
                finishAndRemoveTask()
                return@launch
            }
            // The encoded audio is ~100 ms shorter than the wall clock (start/stop latency); the video needs the
            // real length, or its clip ends past the file and the export stalls (field report 2026-10-13).
            val duration = withContext(Dispatchers.IO) { VoiceRecorder.durationMs(VoiceRecorder.fileFor(this@VoiceNoteActivity, trip, noteId)) } ?: wallClockMs
            val pos = controller.position(System.currentTimeMillis())
            controller.saveVoiceNote(seg, trip, noteId, startedUtcMs, duration, pos, mediaItemId = intent.getStringExtra(EXTRA_MEDIA_ITEM_ID))
            Log.i(TAG, "voice note saved id=$noteId duration=${duration}ms pos=${pos.lat != null}")
            finishAndRemoveTask()
        }
    }

    private fun cancelRecording() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { recorder.cancel() }
            tripId?.let { VoiceRecorder.fileFor(this@VoiceNoteActivity, it, noteId).delete() }
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        // A configuration change keeps the Activity (manifest configChanges); any other destroy while
        // recording is a real teardown and the half note is not worth keeping (review I1).
        if (!isChangingConfigurations && recorder.isRecording) recorder.cancel()
        super.onDestroy()
    }

    private fun vibrate(pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val TAG = "Bitacora"
        const val START_TIMEOUT_MS = 4_000L

        /** A note "about this photo" (DISENO §7.8) carries the media id it describes. */
        const val EXTRA_MEDIA_ITEM_ID = "media_item_id"

        fun intent(context: Context, mediaItemId: String? = null): Intent = Intent(context, VoiceNoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .apply { mediaItemId?.let { putExtra(EXTRA_MEDIA_ITEM_ID, it) } }
    }
}
