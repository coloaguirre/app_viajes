package com.bitacoraviaje.app.ui.tripdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.bitacoraviaje.app.map.MapRenderer
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.timeline.TimelineEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TripDetailRoute(mapRenderer: MapRenderer, onBack: () -> Unit, onReview: (String) -> Unit, onVoiceNote: (String) -> Unit, viewModel: TripDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TripDetailScreen(
        state = state,
        onBack = onBack,
        onTab = viewModel::selectTab,
        onEvent = { e -> if (e is TimelineEvent.Voice) onVoiceNote(e.note.id) else viewModel.focusOn(e) },
        onReview = { onReview(viewModel.tripId) },
        map = { s, m -> mapRenderer.TrackMap(s, m) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onTab: (Int) -> Unit,
    onEvent: (TimelineEvent) -> Unit,
    onReview: () -> Unit,
    map: @Composable (TrackMapState, Modifier) -> Unit = { _, m -> Box(m) },
) {
    val titles = listOf(R.string.tab_timeline, R.string.tab_map, R.string.tab_photos)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.trip?.title ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.selectedTab) {
                titles.forEachIndexed { i, res ->
                    Tab(selected = state.selectedTab == i, onClick = { onTab(i) }, text = { Text(stringResource(res)) }, modifier = Modifier.testTag("trip_tab_$i"))
                }
            }
            when (state.selectedTab) {
                0 -> TimelineTab(state, onEvent)
                1 -> map(state.map, Modifier.fillMaxSize().testTag("trip_map"))
                else -> PhotosTab(state, onReview)
            }
        }
    }
}

@Composable
private fun TimelineTab(state: TripDetailUiState, onEvent: (TimelineEvent) -> Unit) {
    if (state.segments.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.timeline_empty)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("timeline_list")) {
        state.segments.forEach { st ->
            val zone = ZoneId.of(st.segment.tzId)
            item(key = "seg-${st.segment.id}") {
                Text(
                    segmentTitle(st),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(st.events, key = { "${st.segment.id}-${TripDetailViewModel.eventKey(it)}" }) { e -> EventRow(e, zone, selected = state.focusedEventKey == TripDetailViewModel.eventKey(e), onClick = { onEvent(e) }) }
        }
    }
}

@Composable
private fun segmentTitle(st: SegmentTimeline): String {
    val s = st.segment
    val date = Instant.ofEpochMilli(s.startedUtcMs).atZone(ZoneId.of(s.tzId)).toLocalDate()
    val kind = when (s.type) {
        com.bitacoraviaje.core.model.SegmentType.TREKKING -> stringResource(R.string.type_trekking)
        com.bitacoraviaje.core.model.SegmentType.EXCURSION -> stringResource(R.string.type_excursion)
        else -> stringResource(R.string.type_stage)
    }
    val dist = s.stats.distanceM?.let { " · " + TrackingNotificationFactory.formatKm(it) } ?: ""
    return "${DateTimeFormatter.ofPattern("EEE d MMM").format(date)} · ${s.title ?: kind}$dist"
}

@Composable
private fun EventRow(e: TimelineEvent, zone: ZoneId, selected: Boolean, onClick: () -> Unit) {
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(e.timeUtcMs).atZone(zone))
    val (icon, text) = when (e) {
        is TimelineEvent.SegmentStart -> "▶" to stringResource(R.string.ev_start)
        is TimelineEvent.SegmentEnd -> "■" to stringResource(R.string.ev_end)
        is TimelineEvent.Pause -> (if (e.kind == PauseKind.AUTO_REST) "💤" else "⏸") to stringResource(if (e.kind == PauseKind.AUTO_REST) R.string.ev_rest else if (e.kind == PauseKind.NESTED) R.string.ev_nested else R.string.ev_pause)
        is TimelineEvent.Resume -> "⏵" to stringResource(R.string.ev_resume, TrackingNotificationFactory.formatElapsed(e.pausedMs))
        is TimelineEvent.StopArrived -> "📍" to stringResource(R.string.ev_stop_arrived)
        is TimelineEvent.StopLeft -> "🚶" to stringResource(R.string.ev_stop_left, TrackingNotificationFactory.formatElapsed(e.dwellMs))
        is TimelineEvent.Moment -> (if (e.hasVideo) "🎬" else "📷") to (if (e.isSingle) stringResource(R.string.ev_photo) else stringResource(R.string.ev_moment, e.media.size))
        is TimelineEvent.Voice -> "🎙" to (e.note.displayText ?: stringResource(if (e.note.transcriptStatus == TranscriptStatus.FAILED) R.string.ev_voice_failed else R.string.ev_voice_pending))
        is TimelineEvent.Text -> "📝" to (e.note.text ?: stringResource(R.string.ev_marker))
        is TimelineEvent.Highlight -> "⭐" to stringResource(R.string.ev_highlight)
        is TimelineEvent.DayChange -> "📅" to DateTimeFormatter.ofPattern("EEEE d 'de' MMMM").format(e.localDate)
        is TimelineEvent.Summary -> "↝" to stringResource(R.string.ev_summary, TrackingNotificationFactory.formatKm(e.distanceM), TrackingNotificationFactory.formatElapsed(e.durationMs))
    }
    ListItem(
        headlineContent = { Text(text, style = if (e is TimelineEvent.Summary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge) },
        leadingContent = { Text(icon) },
        trailingContent = { Text(time, style = MaterialTheme.typography.labelMedium) },
        supportingContent = if (e is TimelineEvent.Moment && e.isSingle) {
            { AsyncImage(model = e.media.first().contentUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick).then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
    )
}

@Composable
private fun PhotosTab(state: TripDetailUiState, onReview: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.reviewCount > 0) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.photos_review_pending, state.reviewCount), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onReview, modifier = Modifier.testTag("photos_review")) { Text(stringResource(R.string.photos_review_action)) }
            }
        }
        if (state.media.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.photos_empty)) }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxSize().testTag("photos_grid")) {
                items(state.media, key = { it.id }) { m -> PhotoCell(m) }
            }
        }
    }
}

@Composable
private fun PhotoCell(m: MediaItem) {
    Box(Modifier.padding(2.dp).aspectRatio(1f)) {
        AsyncImage(model = m.contentUri, contentDescription = m.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (m.needsReview || m.linkMethod == com.bitacoraviaje.core.model.LinkMethod.NONE) {
            Badge(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) { Text("?") }
        }
    }
}
