@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // Media3 Transformer API (PLAN 4.6)

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
    val render by viewModel.render.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    FinishSegmentScreen(
        state = state,
        onCancel = onCancel,
        onCloseSegment = viewModel::closeSegment,
        onCloseTrip = viewModel::closeTrip,
        onDone = { state.resumedParentId?.let(onResumeParent) ?: onDone() },
        render = render,
        onCreateVideo = viewModel::createVideo,
        onRetryVideo = viewModel::retryVideo,
        onOpenVideo = { uri -> openVideo(context, uri, share = false) },
        onShareVideo = { uri -> openVideo(context, uri, share = true) },
    )
}

/** Plays or shares the finished MP4 (a MediaStore uri; a cache file goes through the FileProvider). */
private fun openVideo(context: android.content.Context, result: String, share: Boolean) {
    val parsed = android.net.Uri.parse(result)
    // cache/render/ is a declared FileProvider root (review I4); any failure shows a toast instead of crashing.
    val uri = runCatching { if (result.startsWith("content://")) parsed else androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(parsed.path ?: result)) }
        .getOrElse { android.widget.Toast.makeText(context, R.string.finish_video_open_failed, android.widget.Toast.LENGTH_SHORT).show(); return }
    val intent = if (share) {
        android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/mp4").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.finish_video_share))
    } else {
        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { android.widget.Toast.makeText(context, R.string.finish_video_open_failed, android.widget.Toast.LENGTH_SHORT).show() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinishSegmentScreen(
    state: FinishSegmentUiState,
    onCancel: () -> Unit,
    onCloseSegment: () -> Unit,
    onCloseTrip: () -> Unit,
    onDone: () -> Unit,
    render: com.bitacoraviaje.app.video.RenderStatus = com.bitacoraviaje.app.video.RenderStatus(),
    onCreateVideo: () -> Unit = {},
    onRetryVideo: () -> Unit = {},
    onOpenVideo: (String) -> Unit = {},
    onShareVideo: (String) -> Unit = {},
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
                VideoBlock(render, onCreateVideo, onRetryVideo, onOpenVideo, onShareVideo)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("finish_done")) {
                    Text(stringResource(if (state.resumedParentId != null) R.string.finish_back_to_parent else R.string.finish_back_home))
                }
            }
        }
    }
}

/** Adenda 1 / PLAN 4.6: the on-demand 1-minute video of the stage, right where the stage closes. */
@Composable
private fun VideoBlock(render: com.bitacoraviaje.app.video.RenderStatus, onCreate: () -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit, onShare: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.finish_video_title), style = MaterialTheme.typography.titleMedium)
            when {
                render.running -> {
                    Text(stringResource(R.string.finish_video_running, render.progress), modifier = Modifier.testTag("video_running"))
                    androidx.compose.material3.LinearProgressIndicator(progress = { render.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                render.finished && render.resultUri != null -> {
                    Text(
                        stringResource(R.string.finish_video_done, render.durationMs / 1000, render.elapsedMs / 1000) + if (render.skipped > 0) " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.finish_video_skipped, render.skipped, render.skipped) else "",
                        modifier = Modifier.testTag("video_done"),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onOpen(render.resultUri) }, modifier = Modifier.weight(1f).testTag("video_open")) { Text(stringResource(R.string.finish_video_open)) }
                        OutlinedButton(onClick = { onShare(render.resultUri) }, modifier = Modifier.weight(1f).testTag("video_share")) { Text(stringResource(R.string.finish_video_share)) }
                    }
                }
                render.failed -> {
                    Text(stringResource(R.string.finish_video_failed, render.error ?: ""), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video_failed"))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("video_retry")) { Text(stringResource(R.string.finish_video_retry)) }
                }
                else -> {
                    Text(stringResource(R.string.finish_video_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("video_create")) { Text(stringResource(R.string.finish_video_create)) }
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
