package com.bitacoraviaje.app.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.MapRenderer
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.app.ui.common.linkMethodLabelRes
import com.bitacoraviaje.core.model.LinkMethod

@Composable
fun MediaReviewRoute(mapRenderer: MapRenderer, onBack: () -> Unit, viewModel: MediaReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MediaReviewScreen(
        state = state,
        onBack = onBack,
        onSelect = viewModel::select,
        onUseTrackTime = viewModel::useTrackTime,
        onExclude = viewModel::exclude,
        onApplyOffset = viewModel::applyCameraOffset,
        map = { s, m, click -> mapRenderer.TrackMap(s, m, click) },
        onMapClick = viewModel::placeAt,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaReviewScreen(
    state: MediaReviewUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onUseTrackTime: () -> Unit,
    onExclude: () -> Unit,
    onApplyOffset: () -> Unit,
    map: @Composable (TrackMapState, Modifier, ((MapPoint) -> Unit)?) -> Unit = { _, m, _ -> Box(m) },
    onMapClick: (MapPoint) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.queue.isEmpty()) {
                // Keep the camera-fix offer visible: placing the last photo by hand is exactly when it appears.
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(stringResource(R.string.review_empty), modifier = Modifier.testTag("review_empty")) }
            } else {
                ReviewBody(state, onSelect, onUseTrackTime, onExclude, map, onMapClick)
            }
            if (state.suggestedOffsetMs != null && state.suggestedCamera != null) {
                val off = state.suggestedOffsetMs
                Button(onClick = onApplyOffset, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp).testTag("review_fix_camera")) {
                    Text(stringResource(R.string.review_fix_camera, state.suggestedCamera, (if (off > 0) "+" else "−") + TrackingNotificationFactory.formatElapsed(kotlin.math.abs(off))))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewBody(
    state: MediaReviewUiState,
    onSelect: (String) -> Unit,
    onUseTrackTime: () -> Unit,
    onExclude: () -> Unit,
    map: @Composable (TrackMapState, Modifier, ((MapPoint) -> Unit)?) -> Unit,
    onMapClick: (MapPoint) -> Unit,
) {
            LazyRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.queue, key = { it.id }) { m ->
                    val sel = m.id == state.selected?.id
                    AsyncImage(
                        model = m.contentUri, contentDescription = m.displayName, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier).clickable { onSelect(m.id) }.testTag("review_item_${m.id}"),
                    )
                }
            }
            val m = state.selected
            if (m != null) {
                Text(
                    stringResource(R.string.review_hint, m.displayName ?: "", linkLabel(m.linkMethod, m.linkConfidence)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            map(state.map, Modifier.fillMaxWidth().weight(1f).testTag("review_map"), onMapClick)
            Text(stringResource(R.string.review_tap_map), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUseTrackTime, modifier = Modifier.weight(1f).height(56.dp).testTag("review_use_track")) { Text(stringResource(R.string.review_use_track)) }
                OutlinedButton(onClick = onExclude, modifier = Modifier.weight(1f).height(56.dp).testTag("review_exclude")) { Text(stringResource(R.string.review_exclude)) }
            }
}

@Composable
private fun linkLabel(method: LinkMethod, confidence: Double): String = when (method) {
    LinkMethod.NONE, LinkMethod.MANUAL -> stringResource(linkMethodLabelRes(method))
    else -> stringResource(R.string.link_auto, stringResource(linkMethodLabelRes(method)), (confidence * 100).toInt())
}
