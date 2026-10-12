package com.bitacoraviaje.app.ui.finish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.core.model.SegmentStats

@Composable
fun FinishSegmentRoute(onCancel: () -> Unit, onDone: () -> Unit, onResumeParent: (String) -> Unit, viewModel: FinishSegmentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FinishSegmentScreen(
        state = state,
        onCancel = onCancel,
        onCloseSegment = viewModel::closeSegment,
        onCloseTrip = viewModel::closeTrip,
        onDone = { state.resumedParentId?.let(onResumeParent) ?: onDone() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinishSegmentScreen(
    state: FinishSegmentUiState,
    onCancel: () -> Unit,
    onCloseSegment: () -> Unit,
    onCloseTrip: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(if (state.closed == null) R.string.finish_title else R.string.finish_done_title)) }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val closed = state.closed
            if (closed == null) {
                Text(stringResource(R.string.finish_body), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(stringResource(R.string.active_elapsed), TrackingNotificationFactory.formatElapsed(state.liveElapsedMs), Modifier.weight(1f))
                    Stat(stringResource(R.string.active_distance), TrackingNotificationFactory.formatKm(state.liveDistanceM), Modifier.weight(1f))
                    Stat(stringResource(R.string.active_points), "${state.pointCount}", Modifier.weight(1f))
                }
                if (state.closing) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = onCloseSegment, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("finish_segment")) { Text(stringResource(R.string.active_finish_segment)) }
                    OutlinedButton(onClick = onCloseTrip, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("finish_trip")) { Text(stringResource(R.string.active_finish_trip)) }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("finish_cancel")) { Text(stringResource(R.string.active_cancel)) }
                }
            } else {
                Text(stringResource(if (state.tripClosed) R.string.finish_trip_closed else R.string.finish_segment_closed), style = MaterialTheme.typography.bodyLarge)
                StatsBlock(closed.stats, state.stopCount)
                if (state.resumedParentId != null) Text(stringResource(R.string.finish_parent_resumed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("finish_done")) {
                    Text(stringResource(if (state.resumedParentId != null) R.string.finish_back_to_parent else R.string.finish_back_home))
                }
            }
        }
    }
}

@Composable
private fun StatsBlock(stats: SegmentStats, stops: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(stringResource(R.string.active_distance), TrackingNotificationFactory.formatKm(stats.distanceM ?: 0.0), Modifier.weight(1f))
        Stat(stringResource(R.string.stat_moving), TrackingNotificationFactory.formatElapsed(stats.movingTimeMs ?: 0), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(stringResource(R.string.stat_elevation), "+%.0f / −%.0f m".format(stats.elevationGainM ?: 0.0, stats.elevationLossM ?: 0.0), Modifier.weight(1f))
        Stat(stringResource(R.string.stat_stops), "$stops", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
