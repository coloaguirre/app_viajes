package com.bitacoraviaje.app.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
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
import androidx.camera.video.FallbackStrategy
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.voice.VoiceNoteActivity
import com.bitacoraviaje.core.model.MediaKind
import kotlinx.coroutines.launch

/**
 * DISENO §7.8 / PLAN 5.2: the app's own camera. Photo, video and "a note about this photo".
 * CameraX writes the file into MediaStore (`Pictures/BitacoraViaje/<trip>/`) with the GPS of the
 * last fix; [CameraViewModel] hands the registration (row linked with confidence 1.0) to the
 * application-scoped [com.bitacoraviaje.app.camera.CameraCapture].
 */
@Composable
fun CameraRoute(onClose: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CameraScreen(
        state = state,
        locationProvider = { viewModel.location() },
        valuesFor = { kind, now -> viewModel.contentValues(kind, now) },
        onSaved = viewModel::onSaved,
        onError = viewModel::onError,
        onClearError = viewModel::clearError,
        onNoteOnPhoto = { id -> context.startActivity(VoiceNoteActivity.intent(context, mediaItemId = id)) },
        onClose = onClose,
    )
}

private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** CAMERA, plus WRITE_EXTERNAL_STORAGE on API 26–28 where MediaStore inserts still need it (review I6). */
fun cameraPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.CAMERA) else arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

/** Video finalize codes that still leave a playable file (CameraX docs): the shot is kept and registered (review I4). */
fun recordingUsable(error: Int): Boolean = error == VideoRecordEvent.Finalize.ERROR_NONE ||
    error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE || error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
    error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED || error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE

@Composable
fun CameraScreen(
    state: CameraUiState,
    locationProvider: suspend () -> Location?,
    valuesFor: suspend (MediaKind, Long) -> ContentValues,
    onSaved: (Uri, MediaKind, Location?, Long) -> Unit,
    onError: (Int, Int?) -> Unit,
    onClearError: () -> Unit,
    onNoteOnPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by rememberSaveable { mutableStateOf(cameraPermissions().all { granted(context, it) }) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> cameraGranted = result.values.all { it }; asked = true }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            cameraGranted -> CameraContent(state, locationProvider, valuesFor, onSaved, onError, onClearError, onNoteOnPhoto, onClose)
            else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.camera_rationale_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.camera_rationale_body), style = MaterialTheme.typography.bodyMedium)
                if (asked) Text(stringResource(R.string.camera_permission_denied), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("camera_denied"))
                Button(onClick = { askCamera.launch(cameraPermissions()) }, modifier = Modifier.fillMaxWidth().testTag("camera_ask")) { Text(stringResource(R.string.camera_allow)) }
                TextButton(onClick = onClose, modifier = Modifier.testTag("camera_close")) { Text(stringResource(R.string.active_cancel)) }
            }
        }
    }
}

// RECORD_AUDIO is checked with `granted()` before `withAudioEnabled()`; lint cannot follow the flag.
@SuppressLint("MissingPermission")
@Composable
private fun CameraContent(
    state: CameraUiState,
    locationProvider: suspend () -> Location?,
    valuesFor: suspend (MediaKind, Long) -> ContentValues,
    onSaved: (Uri, MediaKind, Location?, Long) -> Unit,
    onError: (Int, Int?) -> Unit,
    onClearError: () -> Unit,
    onNoteOnPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var videoMode by rememberSaveable { mutableStateOf(false) }
    var front by rememberSaveable { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingSinceMs by remember { mutableStateOf(0L) }
    var audioGranted by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    val previewView = remember { PreviewView(context).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    // FHD when the device encodes it, else HD, else SD: a camera whose profiles stop at 720p (the emulator) must still record.
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build())
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val mutedNotice = stringResource(R.string.camera_video_muted)
    // Video with sound needs RECORD_AUDIO (DISENO §7.8): asked in context when video mode is entered; denied → the user is told it records muted (review I8).
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> audioGranted = ok; if (!ok) scope.launch { snackbar.showSnackbar(mutedNotice) } }

    LaunchedEffect(Unit) { provider = ProcessCameraProvider.awaitInstance(context) }
    // Preview + photo, or preview + video: two use cases at a time keep LIMITED devices happy (skill media-linking).
    LaunchedEffect(provider, videoMode, front) {
        val p = provider ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, if (videoMode) videoCapture else imageCapture)
        }.onFailure { onError(R.string.camera_error_open, null) }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop(); provider?.unbindAll() } }
    LaunchedEffect(state.lastShot?.id) {
        val shot = state.lastShot ?: return@LaunchedEffect
        val base = resources.getString(if (shot.kind == MediaKind.PHOTO) R.string.camera_saved_photo else R.string.camera_saved_video)
        snackbar.showSnackbar(if (state.lastWithoutFix) resources.getString(R.string.camera_saved_with_note, base, resources.getString(R.string.camera_no_fix)) else base)
    }
    LaunchedEffect(state.errorRes, state.errorArg) {
        val res = state.errorRes ?: return@LaunchedEffect
        snackbar.showSnackbar(state.errorArg?.let { resources.getString(res, it) } ?: resources.getString(res))
        onClearError()
    }

    fun takePhoto() {
        scope.launch {
            locating = true
            val location = locationProvider()
            val now = System.currentTimeMillis() // after the (possibly slow) fix request: the shutter instant, not the tap (review I5)
            val values = valuesFor(MediaKind.PHOTO, now)
            locating = false
            val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                .setMetadata(ImageCapture.Metadata().apply { this.location = location })
                .build()
            imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { onSaved(it, MediaKind.PHOTO, location, now) } ?: onError(R.string.camera_error_save, null)
                }
                override fun onError(e: ImageCaptureException) = onError(R.string.camera_error_save, null)
            })
        }
    }

    fun startVideo() {
        scope.launch {
            locating = true
            val location = locationProvider()
            val now = System.currentTimeMillis()
            val values = valuesFor(MediaKind.VIDEO, now)
            locating = false
            val out = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).apply { location?.let { setLocation(it) } }.build()
            recordingSinceMs = now
            recording = videoCapture.output.prepareRecording(context, out).apply { if (audioGranted) withAudioEnabled() }
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        val uri = event.outputResults.outputUri
                        when {
                            recordingUsable(event.error) && uri != Uri.EMPTY -> onSaved(uri, MediaKind.VIDEO, location, recordingSinceMs)
                            else -> onError(R.string.camera_video_failed, event.error)
                        }
                    }
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val previewDescription = stringResource(R.string.camera_preview_description)
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().testTag("camera_preview").semantics { contentDescription = previewDescription })
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter)) { Snackbar(it) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.lastShot?.takeIf { it.kind == MediaKind.PHOTO }?.let { shot ->
                OutlinedButton(onClick = { onNoteOnPhoto(shot.id) }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("camera_note_on_photo")) { Text(stringResource(R.string.camera_note_on_photo)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (recording == null) {
                            videoMode = !videoMode
                            if (videoMode && !audioGranted) askAudio.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp).testTag("camera_mode"),
                ) { Text(stringResource(if (videoMode) R.string.camera_mode_photo else R.string.camera_mode_video)) }
                OutlinedButton(onClick = { if (recording == null) front = !front }, modifier = Modifier.weight(1f).height(56.dp).testTag("camera_flip")) { Text(stringResource(R.string.camera_flip)) }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(56.dp).testTag("camera_close")) { Text(stringResource(R.string.camera_done)) }
            }
            val recordingLabel = stringResource(R.string.camera_recording_state)
            Button(
                // Disabled while the fix is being fetched and while a shot is being saved: no double captures (review I5).
                enabled = provider != null && !state.saving && !locating,
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag("camera_shutter").semantics { if (recording != null) stateDescription = recordingLabel },
                onClick = {
                    when {
                        !videoMode -> takePhoto()
                        recording == null -> startVideo()
                        else -> recording?.stop()
                    }
                },
            ) {
                Text(
                    stringResource(when { locating -> R.string.camera_locating; !videoMode -> R.string.camera_shutter; recording == null -> R.string.camera_record; else -> R.string.camera_stop }),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
