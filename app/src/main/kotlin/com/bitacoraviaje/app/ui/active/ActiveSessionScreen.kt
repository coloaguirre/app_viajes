package com.bitacoraviaje.app.ui.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory

@Composable
fun ActiveSessionRoute(onClosed: () -> Unit, viewModel: ActiveSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.events.collect { if (it == ActiveSessionEvent.Closed) onClosed() } }
    ActiveSessionScreen(state, viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(state: ActiveSessionUiState, onAction: (ActiveSessionAction) -> Unit) {
    val live = state.live
    Scaffold(topBar = { TopAppBar(title = { Text(state.segment?.title ?: stringResource(R.string.active_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Map placeholder: MapLibre arrives in item 1.6.
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (live.gpsAvailable) stringResource(R.string.active_gps_ok) else stringResource(R.string.active_gps_waiting),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.testTag("active_gps"),
                    )
                    Text(stringResource(R.string.active_profile, live.profile.name), style = MaterialTheme.typography.bodySmall)
                    if (live.isPaused) Text(stringResource(R.string.active_paused), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("active_paused"))
                    if (live.isResting) Text(stringResource(R.string.active_resting), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("active_resting"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.active_elapsed), TrackingNotificationFactory.formatElapsed(state.elapsedMs), "active_elapsed", Modifier.weight(1f))
                Stat(stringResource(R.string.active_distance), TrackingNotificationFactory.formatKm(live.distanceM), "active_distance", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.active_speed), live.currentSpeedMps?.let { "%.1f km/h".format(it * 3.6) } ?: "—", "active_speed", Modifier.weight(1f))
                Stat(stringResource(R.string.active_points), "${live.acceptedPoints}", "active_points", Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(ActiveSessionAction.TogglePause) }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_pause")) {
                    Text(if (live.isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause))
                }
                Button(onClick = { onAction(ActiveSessionAction.AskFinish) }, modifier = Modifier.weight(1f).height(64.dp).testTag("active_finish")) {
                    Text(stringResource(R.string.action_finish))
                }
            }
        }
    }
    if (state.showFinishDialog) {
        AlertDialog(
            onDismissRequest = { onAction(ActiveSessionAction.DismissFinish) },
            title = { Text(stringResource(R.string.active_finish_confirm_title)) },
            text = { Text(stringResource(R.string.active_finish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(ActiveSessionAction.FinishSegment) }, modifier = Modifier.testTag("active_finish_segment")) {
                    Text(stringResource(R.string.active_finish_segment))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onAction(ActiveSessionAction.FinishTrip) }, modifier = Modifier.testTag("active_finish_trip")) { Text(stringResource(R.string.active_finish_trip)) }
                    TextButton(onClick = { onAction(ActiveSessionAction.DismissFinish) }) { Text(stringResource(R.string.active_cancel)) }
                }
            },
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
