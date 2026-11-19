package com.bitacoraviaje.app.ui.newsession

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport

@Composable
fun NewSessionRoute(onBack: () -> Unit, onStarted: (String) -> Unit, viewModel: NewSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val denied = stringResource(R.string.home_permission_denied)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshMediaAccess() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is NewSessionEvent.Started -> onStarted(e.segmentId)
                NewSessionEvent.PermissionDenied -> snackbar.showSnackbar(denied)
            }
        }
    }
    NewSessionScreen(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onUpdate = viewModel::update,
        onStart = { permissionLauncher.launch(viewModel.requiredPermissions().toTypedArray()) },
        onRequestMedia = { mediaLauncher.launch(viewModel.mediaPermissionSet()) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(
    state: NewSessionUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onUpdate: ((NewSessionForm) -> NewSessionForm) -> Unit,
    onStart: () -> Unit,
    onRequestMedia: () -> Unit = {},
) {
    val f = state.form
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.mode == NewSessionMode.ADD_TO_TRIP) R.string.newsession_title_add else R.string.newsession_title_new_trip)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.mode == null) {
            // The one-tap entry is deciding (continue / new stage / this form): a moment, never a half-built form.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.testTag("newsession_resolving")) }
            return@Scaffold
        }
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val nestedOpen = state.openSegment?.parentSegmentId != null
            // The trip the open segment belongs to is the active one; adding to another trip closes it (backlog D4).
            val switching = state.closesOtherTrip
            if (state.mode == NewSessionMode.ADD_TO_TRIP) {
                state.targetTrip?.let { Text(stringResource(R.string.newsession_add_to, it.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("newsession_target")) }
                Text(stringResource(R.string.newsession_step_component), style = MaterialTheme.typography.titleMedium)
                val types = if (nestedOpen && !switching) listOf(SegmentType.STAGE) else listOf(SegmentType.STAGE, SegmentType.EXCURSION, SegmentType.TREKKING) // one nesting level (DISENO §16.2)
                if (nestedOpen && !switching) Text(stringResource(R.string.newsession_nested_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                types.forEach { type -> TypeCard(type, selected = f.type == type, nestedUnder = state.openSegment?.takeIf { !switching && type != SegmentType.STAGE }) { onUpdate { it.copy(type = type) } } }
                if (state.openSegment != null && !switching && f.type == SegmentType.STAGE) {
                    Text(stringResource(R.string.newsession_closes_open_stage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (switching) {
                Text(stringResource(R.string.newsession_closes_trip, state.activeTrip?.title ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("newsession_closes_trip"))
            }

            Text(stringResource(R.string.newsession_step_details), style = MaterialTheme.typography.titleMedium)
            if (state.mode == NewSessionMode.NEW_TRIP) {
                OutlinedTextField(
                    value = f.tripTitle,
                    onValueChange = { v -> onUpdate { it.copy(tripTitle = v) } },
                    label = { Text(stringResource(R.string.newsession_trip_title_label)) },
                    placeholder = { Text(stringResource(R.string.default_trip_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("newsession_trip_title"),
                )
            } else {
                OutlinedTextField(
                    value = f.title,
                    onValueChange = { v -> onUpdate { it.copy(title = v) } },
                    label = { Text(stringResource(R.string.newsession_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("newsession_title"),
                )
            }
            OutlinedTextField(
                value = f.intentText,
                onValueChange = { v -> onUpdate { it.copy(intentText = v) } },
                label = { Text(stringResource(R.string.newsession_intent_label)) },
                supportingText = { Text(stringResource(R.string.newsession_intent_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("newsession_intent"),
            )

            Text(stringResource(R.string.newsession_step_transport), style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Transport.entries.forEach { t -> TransportChip(t, f.transport == t) { onUpdate { it.copy(transport = t) } } }
            }

            Text(stringResource(R.string.settings_recording_mode), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = f.mode == RecordingMode.FULL, onClick = { onUpdate { it.copy(mode = RecordingMode.FULL) } }, label = { Text(stringResource(R.string.settings_recording_mode_full)) }, modifier = Modifier.testTag("newsession_mode_full"))
                FilterChip(selected = f.mode == RecordingMode.ECONOMY, onClick = { onUpdate { it.copy(mode = RecordingMode.ECONOMY) } }, label = { Text(stringResource(R.string.settings_recording_mode_economy)) }, modifier = Modifier.testTag("newsession_mode_economy"))
            }
            if (f.mode == RecordingMode.ECONOMY) Text(stringResource(R.string.settings_recording_mode_economy_desc), style = MaterialTheme.typography.bodySmall)

            // Media access, asked in context with its reason (DISENO §15.1). The session works without it.
            Card(Modifier.fillMaxWidth().testTag("newsession_media_card")) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.media_card_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.media_card_body), style = MaterialTheme.typography.bodyMedium)
                    val status = when (state.mediaAccess) {
                        com.bitacoraviaje.app.media.MediaAccess.FULL -> R.string.media_access_full
                        com.bitacoraviaje.app.media.MediaAccess.PARTIAL -> R.string.media_access_partial
                        com.bitacoraviaje.app.media.MediaAccess.NONE -> R.string.media_access_none
                    }
                    Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("newsession_media_status"))
                    if (state.mediaAccess != com.bitacoraviaje.app.media.MediaAccess.FULL) {
                        androidx.compose.material3.TextButton(onClick = onRequestMedia, modifier = Modifier.testTag("newsession_media_request")) {
                            Text(stringResource(if (state.mediaAccess == com.bitacoraviaje.app.media.MediaAccess.PARTIAL) R.string.media_access_more else R.string.media_access_allow))
                        }
                    }
                }
            }

            Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("newsession_start")) {
                Text(stringResource(if (state.mode == NewSessionMode.NEW_TRIP) R.string.newsession_start_trip else R.string.newsession_start))
            }
        }
    }
}

@Composable
private fun TypeCard(type: SegmentType, selected: Boolean, nestedUnder: com.bitacoraviaje.core.model.Segment?, onClick: () -> Unit) {
    val (title, body) = when (type) {
        SegmentType.TRIP_START -> R.string.type_trip_start to R.string.type_trip_start_desc
        SegmentType.STAGE -> R.string.type_stage to R.string.type_stage_desc
        SegmentType.EXCURSION -> R.string.type_excursion to R.string.type_excursion_desc
        SegmentType.TREKKING -> R.string.type_trekking to R.string.type_trekking_desc
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("newsession_type_${type.name}"),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            if (nestedUnder != null) Text(stringResource(R.string.newsession_nested_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TransportChip(t: Transport, selected: Boolean, onClick: () -> Unit) {
    val label = when (t) {
        Transport.WALK -> R.string.transport_walk
        Transport.BIKE -> R.string.transport_bike
        Transport.CAR -> R.string.transport_car
        Transport.BUS -> R.string.transport_bus
        Transport.TRAIN -> R.string.transport_train
        Transport.BOAT -> R.string.transport_boat
        Transport.PLANE -> R.string.transport_plane
        Transport.MIXED -> R.string.transport_mixed
    }
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("newsession_transport_${t.name}"))
}
