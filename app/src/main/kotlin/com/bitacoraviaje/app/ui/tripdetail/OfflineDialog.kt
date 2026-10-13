package com.bitacoraviaje.app.ui.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.map.OfflineMaps
import com.bitacoraviaje.app.map.OfflineProgress
import com.bitacoraviaje.core.geo.haversineM
import com.bitacoraviaje.core.model.BoundingBox

/** What the trip screen knows about offline maps (PLAN 5.4). */
data class OfflineUiState(
    val available: Boolean = false,
    /** One raw bounding box per stage with track points; empty until the tracks are loaded. */
    val stageBoxes: List<BoundingBox> = emptyList(),
    /** A complete region already covers this trip. */
    val hasRegion: Boolean = false,
    val running: Boolean = false,
    val progress: OfflineProgress? = null,
)

/** Raw bounding box of each track that has points (one per stage). */
fun stageBoxesOf(tracks: List<List<com.bitacoraviaje.app.map.MapPoint>>): List<BoundingBox> =
    tracks.filter { it.isNotEmpty() }.map { pts -> BoundingBox(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon }) }

fun formatMb(bytes: Long): String = if (bytes < 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "%.0f MB".format(bytes / 1_048_576.0)

/** Pick a detail level (with its estimated size), watch the download, see the result. */
@Composable
fun OfflineDialog(state: OfflineUiState, onStart: (Int) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val bounds = state.stageBoxes.takeIf { it.isNotEmpty() }?.let { OfflineMaps.regionBounds(it.reduce { a, b -> BoundingBox(minOf(a.minLat, b.minLat), minOf(a.minLon, b.minLon), maxOf(a.maxLat, b.maxLat), maxOf(a.maxLon, b.maxLon)) }) }
    val progress = state.progress
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text(stringResource(R.string.offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.running && progress != null -> {
                        val fraction = if (progress.requiredTiles > 0) (progress.completedTiles.toFloat() / progress.requiredTiles).coerceIn(0f, 1f) else 0f
                        val line = if (progress.regions > 1) stringResource(R.string.offline_progress_with_region, progress.region, progress.regions, progress.completedTiles, progress.requiredTiles, formatMb(progress.sizeBytes))
                        else stringResource(R.string.offline_progress, progress.completedTiles, progress.requiredTiles, formatMb(progress.sizeBytes))
                        Text(line, modifier = Modifier.testTag("offline_progress"))
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    state.running -> Text(stringResource(R.string.offline_starting), modifier = Modifier.testTag("offline_progress"))
                    progress?.error != null -> Text(
                        if (progress.error.startsWith("tile_limit")) stringResource(R.string.offline_too_big) else stringResource(R.string.offline_failed, progress.error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("offline_error"),
                    )
                    progress?.done == true -> Text(stringResource(R.string.offline_done, progress.completedTiles, formatMb(progress.sizeBytes)), modifier = Modifier.testTag("offline_done"))
                    bounds == null -> Text(stringResource(R.string.offline_no_track))
                    else -> {
                        val widthKm = haversineM(bounds.minLat, bounds.minLon, bounds.minLat, bounds.maxLon) / 1000
                        val heightKm = haversineM(bounds.minLat, bounds.minLon, bounds.maxLat, bounds.minLon) / 1000
                        Text(stringResource(R.string.offline_body, "%.0f".format(widthKm), "%.0f".format(heightKm)), style = MaterialTheme.typography.bodyMedium)
                        if (state.hasRegion) Text(stringResource(R.string.offline_already), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("offline_already"))
                        var anyFits = false
                        OfflineMaps.DETAIL_ZOOMS.forEach { z ->
                            val plan = OfflineMaps.plan(state.stageBoxes, z)
                            val fits = plan != null
                            if (fits) anyFits = true
                            val (_, bytes) = if (plan != null) OfflineMaps.estimate(plan, z) else 0L to 0L
                            val label = stringResource(if (z <= 12) R.string.offline_level_streets else R.string.offline_level_trails, z, formatMb(bytes)) +
                                if (plan != null && plan.size > 1) " · " + stringResource(R.string.offline_level_regions, plan.size) else ""
                            OutlinedButton(onClick = { onStart(z) }, enabled = fits, modifier = Modifier.fillMaxWidth().testTag("offline_zoom_$z")) {
                                Text(if (fits) label else stringResource(R.string.offline_level_too_big, z))
                            }
                        }
                        if (!anyFits) Text(stringResource(R.string.offline_too_big), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("offline_error"))
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.running -> TextButton(onClick = onCancel, modifier = Modifier.testTag("offline_cancel")) { Text(stringResource(R.string.offline_cancel)) }
                else -> Button(onClick = onDismiss, modifier = Modifier.testTag("offline_close")) { Text(stringResource(R.string.offline_close)) }
            }
        },
    )
}
