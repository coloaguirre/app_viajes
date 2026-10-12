package com.bitacoraviaje.app.ui.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
    ActiveSessionScreen(
        state = state,
        onAction = { a ->
            if (a == ActiveSessionAction.Finish) onFinish(state.live.segmentId ?: viewModel.segmentId) else viewModel.onAction(a)
        },
        map = { s, m -> mapRenderer.TrackMap(s, m) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(
    state: ActiveSessionUiState,
    onAction: (ActiveSessionAction) -> Unit,
    map: @Composable (TrackMapState, Modifier) -> Unit = { _, m -> Box(m) },
) {
    val live = state.live
    Scaffold(topBar = { TopAppBar(title = { Text(state.segment?.title ?: stringResource(R.string.active_title)) }) }) { padding ->
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
