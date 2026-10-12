package com.bitacoraviaje.app.ui.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bitacoraviaje.app.voice.VoiceNoteActivity
import com.bitacoraviaje.core.model.TextNoteKind
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.map.MapRenderer
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory

@Composable
fun ActiveSessionRoute(mapRenderer: MapRenderer, onFinish: (String) -> Unit, viewModel: ActiveSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val savedMarker = stringResource(R.string.active_marker_saved)
    val savedHighlight = stringResource(R.string.active_highlight_saved)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                ActiveSessionEvent.OpenVoiceNote -> context.startActivity(VoiceNoteActivity.intent(context))
                is ActiveSessionEvent.Saved -> snackbar.showSnackbar(if (e.kind == TextNoteKind.HIGHLIGHT) savedHighlight else savedMarker)
            }
        }
    }
    ActiveSessionScreen(
        state = state,
        onAction = { a ->
            if (a == ActiveSessionAction.Finish) onFinish(state.live.segmentId ?: viewModel.segmentId) else viewModel.onAction(a)
        },
        map = { s, m -> mapRenderer.TrackMap(s, m) },
        snackbar = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(
    state: ActiveSessionUiState,
    onAction: (ActiveSessionAction) -> Unit,
    map: @Composable (TrackMapState, Modifier) -> Unit = { _, m -> Box(m) },
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val live = state.live
    var markerDialog by remember { mutableStateOf(false) }
    var markerText by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text(state.segment?.title ?: stringResource(R.string.active_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Map takes ~60 % of the screen (DISENO §14.1).
            Box(Modifier.fillMaxWidth().weight(1.5f)) {
                map(state.map, Modifier.fillMaxSize().testTag("active_map"))
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            if (live.gpsAvailable) stringResource(R.string.active_gps_ok) else stringResource(R.string.active_gps_waiting),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("active_gps"),
                        )
                        Text(stringResource(R.string.active_profile, live.profile.name), style = MaterialTheme.typography.labelSmall)
                        if (live.isPaused) Text(stringResource(R.string.active_paused), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("active_paused"))
                        if (live.isResting) Text(stringResource(R.string.active_resting), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("active_resting"))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(stringResource(R.string.active_elapsed), TrackingNotificationFactory.formatElapsed(state.elapsedMs), "active_elapsed", Modifier.weight(1f))
                    Stat(stringResource(R.string.active_distance), TrackingNotificationFactory.formatKm(live.distanceM), "active_distance", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(stringResource(R.string.active_speed), live.currentSpeedMps?.let { "%.1f km/h".format(it * 3.6) } ?: "—", "active_speed", Modifier.weight(1f))
                    Stat(stringResource(R.string.active_points), "${live.acceptedPoints}", "active_points", Modifier.weight(1f))
                }
                // Big one-hand buttons (≥ 64 dp, DISENO §14.2): voice note, marker, highlight.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onAction(ActiveSessionAction.VoiceNote) }, modifier = Modifier.weight(1.4f).height(64.dp).testTag("active_voice")) { Text(stringResource(R.string.action_voice_note)) }
                    OutlinedButton(onClick = { markerText = ""; markerDialog = true }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_marker")) { Text(stringResource(R.string.action_marker)) }
                    OutlinedButton(onClick = { onAction(ActiveSessionAction.Highlight) }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_highlight")) { Text(stringResource(R.string.action_highlight)) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onAction(ActiveSessionAction.TogglePause) }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_pause")) {
                        Text(if (live.isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause))
                    }
                    Button(onClick = { onAction(ActiveSessionAction.Finish) }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_finish")) {
                        Text(stringResource(R.string.action_finish))
                    }
                }
            }
        }
    }
    if (markerDialog) {
        AlertDialog(
            onDismissRequest = { markerDialog = false },
            title = { Text(stringResource(R.string.marker_title)) },
            text = {
                OutlinedTextField(
                    value = markerText,
                    onValueChange = { markerText = it },
                    label = { Text(stringResource(R.string.marker_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("marker_text"),
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(ActiveSessionAction.Marker(markerText)); markerDialog = false }, modifier = Modifier.testTag("marker_save")) { Text(stringResource(R.string.marker_save)) }
            },
            dismissButton = { TextButton(onClick = { markerDialog = false }) { Text(stringResource(R.string.active_cancel)) } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, tag: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(tag))
        }
    }
}
