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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.export.ExportDestination
import com.bitacoraviaje.app.export.ExportOptions
import com.bitacoraviaje.app.map.MapRenderer
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.tracking.TrackingNotificationFactory
import com.bitacoraviaje.app.ui.common.transportLabelRes
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.timeline.TimelineEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The app's texts are Spanish only; dates follow suit regardless of the phone's locale. */
private val APP_LOCALE: Locale = Locale.forLanguageTag("es-AR")

@Composable
fun TripDetailRoute(mapRenderer: MapRenderer, onBack: () -> Unit, onReview: (String) -> Unit, onVoiceNote: (String) -> Unit, onCreateVideo: (String) -> Unit = {}, viewModel: TripDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    var showOffline by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val offlineBoxes = androidx.compose.runtime.remember(state.map.tracks) { stageBoxesOf(state.map.tracks) }
    val export by viewModel.export.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showExport by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var pendingMedia by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var pendingAudio by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var pendingFiltered by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var pendingPrivacy by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var pendingStrip by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    val createZip = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.startExport(ExportOptions(pendingMedia, pendingAudio, pendingFiltered, applyPrivacyZones = pendingPrivacy, stripExifGps = pendingStrip), ExportDestination.SAF, it) }
    }
    TripDetailScreen(
        state = state,
        onBack = onBack,
        onTab = viewModel::selectTab,
        onEvent = { e -> if (e is TimelineEvent.Voice) onVoiceNote(e.note.id) else viewModel.focusOn(e) },
        onReview = { onReview(viewModel.tripId) },
        onExport = { showExport = true },
        onCreateVideo = { onCreateVideo(viewModel.tripId) },
        onPlaceAction = viewModel::onPlaceAction,
        map = { s, m -> mapRenderer.TrackMap(s, m) },
        offline = offline.copy(stageBoxes = offlineBoxes),
        onOffline = { showOffline = true },
    )
    if (showOffline) {
        OfflineDialog(
            state = offline.copy(stageBoxes = offlineBoxes),
            onStart = viewModel::startOfflineDownload,
            onCancel = viewModel::cancelOfflineDownload,
            onDismiss = { showOffline = false; viewModel.clearOfflineResult() },
        )
    }
    if (showExport) {
        val zones by viewModel.privacyZones.collectAsStateWithLifecycle()
        ExportDialog(
            zones = zones,
            status = export,
            onDismiss = { showExport = false },
            onStart = { opts, dest ->
                when (dest) {
                    ExportDestination.SAF -> {
                        pendingMedia = opts.includeMedia
                        pendingAudio = opts.includeAudio
                        pendingFiltered = opts.includeFilteredPoints
                        pendingPrivacy = opts.applyPrivacyZones
                        pendingStrip = opts.stripExifGps
                        createZip.launch(com.bitacoraviaje.core.export.Names.packageFolder(state.trip?.title ?: "viaje", System.currentTimeMillis(), state.segments.firstOrNull()?.segment?.tzId ?: "UTC") + ".zip")
                    }
                    else -> viewModel.startExport(opts, dest)
                }
            },
            onShareResult = { export.resultUri?.let { shareExport(context, it) } },
            onReset = viewModel::dismissExport,
        )
    }
}

/** Share sheet for a finished export: a cache file goes through the FileProvider, a MediaStore/SAF uri as is. */
private fun shareExport(context: android.content.Context, result: String) {
    val uri = if (result.startsWith("content://")) result.toUri() else androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(result))
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.export_share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onTab: (Int) -> Unit,
    onEvent: (TimelineEvent) -> Unit,
    onReview: () -> Unit,
    onExport: () -> Unit = {},
    onCreateVideo: () -> Unit = {},
    onPlaceAction: (PlaceAction) -> Unit = {},
    offline: OfflineUiState = OfflineUiState(),
    onOffline: () -> Unit = {},
    map: @Composable (TrackMapState, Modifier) -> Unit = { _, m -> Box(m) },
) {
    val titles = listOf(R.string.tab_timeline, R.string.tab_map, R.string.tab_photos, R.string.tab_places)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.trip?.title ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = onCreateVideo, modifier = Modifier.testTag("trip_video")) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.video_title)) }
                    IconButton(onClick = onExport, modifier = Modifier.testTag("trip_export")) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.export_title)) }
                },
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
                1 -> Box(Modifier.fillMaxSize()) {
                    map(state.map, Modifier.fillMaxSize().testTag("trip_map"))
                    // PLAN 5.4: download this area for use without network (only when the map provider is configured).
                    if (offline.available) {
                        androidx.compose.material3.FilledTonalButton(onClick = onOffline, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("trip_offline")) {
                            Text(stringResource(if (offline.hasRegion) R.string.offline_title else R.string.offline_button))
                        }
                    }
                }
                2 -> PhotosTab(state, onReview)
                else -> PlacesTab(state, onPlaceAction)
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
    return "${DateTimeFormatter.ofPattern("EEE d MMM", APP_LOCALE).format(date)} · ${s.displayName ?: kind}$dist"
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
        is TimelineEvent.TransportChange -> "🔀" to stringResource(R.string.ev_transport_change, e.transport?.let { stringResource(transportLabelRes(it)) } ?: (e.note.text ?: ""))
        is TimelineEvent.DayChange -> "📅" to DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", APP_LOCALE).format(e.localDate)
        is TimelineEvent.PlaceVisited -> "🏛" to stringResource(R.string.ev_place, e.place.name)
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

/** DISENO §14 "Lugares": cards with the text, its source and license; approve for the video, discard, edit (DISENO §11.4). */
@Composable
private fun PlacesTab(state: TripDetailUiState, onAction: (PlaceAction) -> Unit) {
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var editText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.placesMessage ?: when (state.placesWork) {
                    PlacesWork.RUNNING -> stringResource(R.string.places_searching)
                    PlacesWork.WAITING_NETWORK -> stringResource(R.string.places_waiting_network)
                    PlacesWork.IDLE -> if (state.placesEnabled) "" else stringResource(R.string.places_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("places_status"),
            )
            androidx.compose.material3.OutlinedButton(onClick = { onAction(PlaceAction.Refresh()) }, enabled = state.placesEnabled && state.placesWork == PlacesWork.IDLE, modifier = Modifier.testTag("places_refresh")) {
                Text(stringResource(R.string.places_refresh))
            }
        }
        if (state.places.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("places_empty")) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("places_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.places.groupBy { it.segment.id }.forEach { (_, cards) ->
                item(key = "seg-${cards.first().segment.id}") {
                    val seg = cards.first().segment
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(seg.displayName ?: DateTimeFormatter.ofPattern("EEE d MMM", APP_LOCALE).format(Instant.ofEpochMilli(seg.startedUtcMs).atZone(ZoneId.of(seg.tzId))), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).weight(1f))
                        // Forced refresh is per segment: up to ~45 requests to public instances each (review I4).
                        androidx.compose.material3.TextButton(onClick = { onAction(PlaceAction.Refresh(seg.id)) }, enabled = state.placesEnabled && state.placesWork == PlacesWork.IDLE, modifier = Modifier.testTag("places_refresh_${seg.id}")) { Text(stringResource(R.string.places_refresh_segment)) }
                    }
                }
                items(cards, key = { "${it.segment.id}-${it.place.id}" }) { card ->
                    PlaceCardView(
                        card = card,
                        llmAvailable = state.llmAvailable,
                        rewriting = state.rewriting,
                        onApprove = { e, v -> onAction(PlaceAction.Approve(e.id, v)) },
                        onDiscard = { onAction(PlaceAction.Discard(card.segment.id, card.place.id)) },
                        onEdit = { e -> editing = e.id; editText = e.summary },
                        onRewrite = { e -> onAction(PlaceAction.Rewrite(e.id)) },
                        onRestore = { e -> onAction(PlaceAction.Restore(e.id)) },
                        onOpen = { url -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } },
                    )
                }
            }
        }
    }
    editing?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.places_edit_title)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().testTag("place_edit_text"), minLines = 3)
                    Text(stringResource(R.string.places_edit_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onAction(PlaceAction.Edit(id, editText)); editing = null }, enabled = editText.isNotBlank(), modifier = Modifier.testTag("place_edit_save")) { Text(stringResource(R.string.places_save)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = null }) { Text(stringResource(R.string.places_cancel)) } },
        )
    }
}

@Composable
private fun PlaceCardView(
    card: PlaceCard,
    llmAvailable: Boolean,
    rewriting: Set<String>,
    onApprove: (com.bitacoraviaje.core.model.Enrichment, Boolean) -> Unit,
    onDiscard: () -> Unit,
    onEdit: (com.bitacoraviaje.core.model.Enrichment) -> Unit,
    onRewrite: (com.bitacoraviaje.core.model.Enrichment) -> Unit,
    onRestore: (com.bitacoraviaje.core.model.Enrichment) -> Unit,
    onOpen: (String) -> Unit,
) {
    val p = card.place
    val wiki = card.texts.firstOrNull { it.sourceName == com.bitacoraviaje.core.model.Enrichment.SOURCE_WIKIPEDIA }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().testTag("place_${p.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                wiki?.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small)) }
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    val sub = listOfNotNull(p.kind, p.locality?.takeIf { it != p.name }, p.admin1, card.visit.dwellMs?.takeIf { it > 0 }?.let { stringResource(R.string.places_dwell, TrackingNotificationFactory.formatElapsed(it)) }).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (card.texts.isEmpty()) Text(stringResource(R.string.places_no_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.texts.forEach { e ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.places_source_line, e.sourceName, e.license ?: "") + if (e.llmRewritten) " · " + stringResource(R.string.places_rewritten) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).then(if (e.sourceUrl.isNotBlank()) Modifier.clickable { onOpen(e.sourceUrl) } else Modifier),
                        )
                        androidx.compose.material3.TextButton(onClick = { onEdit(e) }, modifier = Modifier.testTag("place_edit_${e.id}")) { Text(stringResource(R.string.places_edit)) }
                    }
                    // PLAN 4.3: the rewrite exists only with a configured key, and only for Wikipedia texts (facts are literal).
                    if (e.sourceName == com.bitacoraviaje.core.model.Enrichment.SOURCE_WIKIPEDIA && (llmAvailable || e.originalSummary != null)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (llmAvailable) {
                                androidx.compose.material3.TextButton(onClick = { onRewrite(e) }, enabled = e.id !in rewriting, modifier = Modifier.testTag("place_rewrite_${e.id}")) {
                                    Text(stringResource(if (e.id in rewriting) R.string.places_rewriting else R.string.places_rewrite))
                                }
                            }
                            if (e.originalSummary != null) {
                                androidx.compose.material3.TextButton(onClick = { onRestore(e) }, modifier = Modifier.testTag("place_restore_${e.id}")) { Text(stringResource(R.string.places_restore)) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = e.userApproved, onCheckedChange = { onApprove(e, it) }, modifier = Modifier.testTag("place_approve_${e.id}"))
                        Text(stringResource(if (e.userApproved) R.string.places_used_in_video else R.string.places_use_in_video), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onDiscard, modifier = Modifier.testTag("place_discard_${p.id}")) { Text(stringResource(R.string.places_discard)) }
            }
        }
    }
}
