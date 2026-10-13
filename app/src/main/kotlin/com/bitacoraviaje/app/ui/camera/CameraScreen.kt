package com.bitacoraviaje.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.camera.CameraCapture
import com.bitacoraviaje.app.voice.VoiceNoteActivity
import com.bitacoraviaje.core.model.MediaKind
import kotlinx.coroutines.launch

/**
 * DISENO §7.8 / PLAN 5.2: the app's own camera. Photo, video and "a note about this photo".
 * CameraX writes the file into MediaStore (`Pictures/BitacoraViaje/<trip>/`) with the GPS of the
 * last fix; [CameraViewModel] registers the row linked with confidence 1.0.
 */
@Composable
fun CameraRoute(onClose: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tripTitle = remember { mutableStateOf<String?>(null) }
    CameraScreen(
        state = state,
        segmentId = viewModel.segmentId,
        locationProvider = { viewModel.location() },
        valuesFor = { kind, now, tz -> viewModel.contentValues(kind, now, tz) },
        onSaved = viewModel::onSaved,
        onError = viewModel::onError,
        onClearError = viewModel::clearError,
        onNoteOnPhoto = { id -> context.startActivity(VoiceNoteActivity.intent(context, mediaItemId = id)) },
        onClose = onClose,
    )
}

private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraScreen(
    state: CameraUiState,
    segmentId: String,
    locationProvider: suspend () -> android.location.Location?,
    valuesFor: suspend (MediaKind, Long, String?) -> Pair<android.content.ContentValues, String>,
    onSaved: (android.net.Uri, MediaKind, android.location.Location?, Long) -> Unit,
    onError: (String) -> Unit,
    onClearError: () -> Unit,
    onNoteOnPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by rememberSaveable { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> cameraGranted = ok; asked = true }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            cameraGranted -> CameraContent(state, segmentId, locationProvider, valuesFor, onSaved, onError, onClearError, onNoteOnPhoto, onClose)
            else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.camera_rationale_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.camera_rationale_body), style = MaterialTheme.typography.bodyMedium)
                if (asked) Text(stringResource(R.string.camera_permission_denied), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("camera_denied"))
                Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().testTag("camera_ask")) { Text(stringResource(R.string.camera_allow)) }
                TextButton(onClick = onClose, modifier = Modifier.testTag("camera_close")) { Text(stringResource(R.string.active_cancel)) }
            }
        }
    }
}

// RECORD_AUDIO is checked with `granted()` before `withAudioEnabled()`; lint cannot follow the flag.
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun CameraContent(
    state: CameraUiState,
    segmentId: String,
    locationProvider: suspend () -> android.location.Location?,
    valuesFor: suspend (MediaKind, Long, String?) -> Pair<android.content.ContentValues, String>,
    onSaved: (android.net.Uri, MediaKind, android.location.Location?, Long) -> Unit,
    onError: (String) -> Unit,
    onClearError: () -> Unit,
    onNoteOnPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var videoMode by rememberSaveable { mutableStateOf(false) }
    var front by rememberSaveable { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingSinceMs by remember { mutableStateOf(0L) }
    val previewView = remember { PreviewView(context).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    // FHD when the device encodes it, else HD, else SD: a camera whose profiles stop at 720p (the emulator) must still record.
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build(),
        )
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val savedPhoto = stringResource(R.string.camera_saved_photo)
    val savedVideo = stringResource(R.string.camera_saved_video)
    val noFix = stringResource(R.string.camera_no_fix)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val audioGranted = granted(context, Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(Unit) { provider = ProcessCameraProvider.awaitInstance(context) }
    // Preview + photo, or preview + video: two use cases at a time keep LIMITED devices happy (skill media-linking).
    LaunchedEffect(provider, videoMode, front) {
        val p = provider ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, if (videoMode) videoCapture else imageCapture)
        }.onFailure { onError(it.message ?: it.javaClass.simpleName) }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop(); provider?.unbindAll() } }
    LaunchedEffect(state.lastShot?.id) {
        val shot = state.lastShot ?: return@LaunchedEffect
        snackbar.showSnackbar(if (shot.kind == MediaKind.PHOTO) savedPhoto else savedVideo + if (state.lastWithoutFix) " · $noFix" else "")
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); onClearError() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().testTag("camera_preview"))
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter)) { Snackbar(it) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.lastShot?.takeIf { it.kind == MediaKind.PHOTO }?.let { shot ->
                OutlinedButton(onClick = { onNoteOnPhoto(shot.id) }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("camera_note_on_photo")) { Text(stringResource(R.string.camera_note_on_photo)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { if (recording == null) videoMode = !videoMode }, modifier = Modifier.weight(1f).height(56.dp).testTag("camera_mode")) {
                    Text(stringResource(if (videoMode) R.string.camera_mode_photo else R.string.camera_mode_video))
                }
                OutlinedButton(onClick = { if (recording == null) front = !front }, modifier = Modifier.weight(1f).height(56.dp).testTag("camera_flip")) { Text(stringResource(R.string.camera_flip)) }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(56.dp).testTag("camera_close")) { Text(stringResource(R.string.camera_done)) }
            }
            Button(
                enabled = provider != null && !state.saving,
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag("camera_shutter"),
                onClick = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val location = locationProvider()
                        if (!videoMode) {
                            val (values, _) = valuesFor(MediaKind.PHOTO, now, null)
                            val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                .setMetadata(ImageCapture.Metadata().apply { this.location = location })
                                .build()
                            imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    output.savedUri?.let { onSaved(it, MediaKind.PHOTO, location, now) } ?: onError("sin uri")
                                }
                                override fun onError(e: ImageCaptureException) = onError(e.message ?: e.javaClass.simpleName)
                            })
                        } else if (recording == null) {
                            val (values, _) = valuesFor(MediaKind.VIDEO, now, null)
                            val out = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).apply { location?.let { setLocation(it) } }.build()
                            recordingSinceMs = now
                            recording = videoCapture.output.prepareRecording(context, out).apply { if (audioGranted) withAudioEnabled() }
                                .start(ContextCompat.getMainExecutor(context)) { event ->
                                    if (event is VideoRecordEvent.Finalize) {
                                        recording = null
                                        if (event.hasError()) onError(resources.getString(R.string.camera_video_failed, event.error)) else onSaved(event.outputResults.outputUri, MediaKind.VIDEO, location, recordingSinceMs)
                                    }
                                }
                        } else {
                            recording?.stop()
                        }
                    }
                },
            ) {
                Text(stringResource(when { !videoMode -> R.string.camera_shutter; recording == null -> R.string.camera_record; else -> R.string.camera_stop }), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
