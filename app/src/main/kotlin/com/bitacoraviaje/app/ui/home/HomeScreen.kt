package com.bitacoraviaje.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ListItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.model.Trip

@Composable
fun HomeRoute(onOpenSettings: () -> Unit, onNewSession: () -> Unit, onOpenActiveSession: (String) -> Unit, onOpenTrip: (String) -> Unit = {}, onExportTrip: (String) -> Unit = {}, onCreateVideo: (String) -> Unit = {}, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = androidx.compose.ui.platform.LocalResources.current
    val denied = stringResource(R.string.home_permission_denied)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is HomeEvent.OpenActiveSession -> onOpenActiveSession(e.segmentId)
                HomeEvent.PermissionDenied -> launch { snackbar.showSnackbar(denied) }
                // Each snackbar in its own coroutine: a 10 s "Deshacer" must not hold back the next event (review M5).
                is HomeEvent.TripDeleted -> launch {
                    val r = snackbar.showSnackbar(resources.getString(R.string.trip_deleted, e.title), actionLabel = resources.getString(R.string.trip_delete_undo), duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoDelete(e.tripId) else viewModel.commitDelete(e.tripId)
                }
                HomeEvent.TripInUse -> launch { snackbar.showSnackbar(resources.getString(R.string.trip_delete_in_use)) }
                is HomeEvent.TripDeleteFailed -> launch { snackbar.showSnackbar(resources.getString(R.string.trip_delete_failed, e.title), duration = androidx.compose.material3.SnackbarDuration.Long) }
            }
        }
    }
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFrom) }
    HomeScreen(
        state = state,
        snackbar = snackbar,
        onOpenSettings = onOpenSettings,
        onNewRecording = onNewSession,
        onResumeActive = { id -> onOpenActiveSession(id) },
        onRecoverResume = viewModel::resumeOpenSegment,
        onRecoverClose = viewModel::closeOpenSegmentAsRecovered,
        onOpenTrip = onOpenTrip,
        onTripAction = { a ->
            when (a) {
                is TripAction.Rename -> viewModel.renameTrip(a.tripId, a.title)
                is TripAction.Archive -> viewModel.setArchived(a.tripId, a.archived)
                is TripAction.Export -> onExportTrip(a.tripId)
                is TripAction.Video -> onCreateVideo(a.tripId)
                is TripAction.Delete -> viewModel.deleteTrip(a.tripId)
            }
        },
        onImport = { pickFile.launch(arrayOf("application/zip", "application/json", "application/gpx+xml", "application/octet-stream", "text/xml", "application/xml")) },
    )
    ImportDialog(
        status = importStatus,
        onOpenTrip = { id -> viewModel.dismissImport(); onOpenTrip(id) },
        onReplace = { viewModel.resolveImportConflict(com.bitacoraviaje.app.export.ImportConflict.REPLACE) },
        onCopy = { viewModel.resolveImportConflict(com.bitacoraviaje.app.export.ImportConflict.COPY) },
        onDismiss = viewModel::dismissImport,
    )
}

/** Result of an import: open the trip, answer the conflict question, or read the error. */
@Composable
fun ImportDialog(status: com.bitacoraviaje.app.export.ImportStatus, onOpenTrip: (String) -> Unit, onReplace: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    if (status.idle) return
    AlertDialog(
        onDismissRequest = { if (!status.running) onDismiss() },
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column {
                when {
                    status.running -> Text(stringResource(R.string.import_running), modifier = Modifier.testTag("import_running"))
                    status.conflictFile != null -> Text(stringResource(R.string.import_conflict, status.conflictFile), modifier = Modifier.testTag("import_conflict"))
                    status.error != null -> Text(stringResource(R.string.import_failed, status.error), modifier = Modifier.testTag("import_failed"))
                    else -> {
                        Text(stringResource(R.string.import_done), modifier = Modifier.testTag("import_done"))
                        status.warnings.forEach { Text("• $it") }
                    }
                }
            }
        },
        confirmButton = {
            when {
                status.running -> Unit
                status.conflictFile != null -> Column {
                    TextButton(onClick = onReplace, modifier = Modifier.testTag("import_replace")) { Text(stringResource(R.string.import_replace)) }
                    TextButton(onClick = onCopy, modifier = Modifier.testTag("import_copy")) { Text(stringResource(R.string.import_copy)) }
                }
                status.tripId != null -> TextButton(onClick = { onOpenTrip(status.tripId) }, modifier = Modifier.testTag("import_open")) { Text(stringResource(R.string.import_open)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.active_cancel)) }
            }
        },
        dismissButton = { if (!status.running) TextButton(onClick = onDismiss) { Text(stringResource(R.string.active_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onNewRecording: () -> Unit,
    onResumeActive: (String) -> Unit,
    onRecoverResume: () -> Unit = {},
    onRecoverClose: () -> Unit = {},
    onOpenTrip: (String) -> Unit = {},
    onTripAction: (TripAction) -> Unit = {},
    onImport: () -> Unit = {},
) {
    if (state.needsRecovery) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recover_title)) },
            text = { Text(stringResource(R.string.recover_body)) },
            confirmButton = { TextButton(onClick = onRecoverResume) { Text(stringResource(R.string.recover_resume)) } },
            dismissButton = { TextButton(onClick = onRecoverClose) { Text(stringResource(R.string.recover_close)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onImport, modifier = Modifier.testTag("home_import")) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.import_title))
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("home_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            run {
                ExtendedFloatingActionButton(
                    onClick = onNewRecording,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(if (state.openSegment == null) R.string.home_new_recording else R.string.home_new_excursion)) },
                    modifier = Modifier.testTag("home_new_recording"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.openSegment?.let { open ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("home_active_banner"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.home_active_banner), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onResumeActive(open.id) }) { Text(stringResource(R.string.home_active_banner_action)) }
                    }
                }
            }
            if (state.trips.isEmpty() && state.archived.isEmpty()) {
                EmptyTrips()
            } else {
                TripList(state.trips, state.archived, state.openSegment?.tripId, onOpenTrip, onTripAction)
            }
        }
    }
}

@Composable
private fun EmptyTrips() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Backlog D1: what the three-dot menu of a trip row can do. */
sealed interface TripAction {
    data class Rename(val tripId: String, val title: String) : TripAction
    data class Archive(val tripId: String, val archived: Boolean) : TripAction
    data class Export(val tripId: String) : TripAction
    data class Video(val tripId: String) : TripAction
    data class Delete(val tripId: String) : TripAction
}

/** [openTripId]: the trip with the segment being recorded; it cannot be archived or deleted until that segment is closed. */
@Composable
private fun TripList(trips: List<Trip>, archived: List<Trip>, openTripId: String?, onOpenTrip: (String) -> Unit, onTripAction: (TripAction) -> Unit) {
    var showArchived by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(trips, key = { it.id }) { trip -> TripRow(trip, trip.id == openTripId, onOpenTrip, onTripAction) }
        if (archived.isNotEmpty()) {
            item(key = "archived-header") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_archived, archived.size), style = MaterialTheme.typography.titleSmall) },
                    trailingContent = { Icon(if (showArchived) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = stringResource(if (showArchived) R.string.home_archived_collapse else R.string.home_archived_expand)) },
                    modifier = Modifier.clickable { showArchived = !showArchived }.testTag("home_archived_toggle"),
                )
            }
            if (showArchived) items(archived, key = { it.id }) { trip -> TripRow(trip, trip.id == openTripId, onOpenTrip, onTripAction) }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, recording: Boolean, onOpenTrip: (String) -> Unit, onTripAction: (TripAction) -> Unit) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var rename by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var confirmDelete by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val active = trip.status == com.bitacoraviaje.core.model.TripStatus.ACTIVE
    ListItem(
        headlineContent = { Text(trip.title) },
        supportingContent = { Text(stringResource(if (active) R.string.trip_status_active else if (trip.archived) R.string.trip_status_archived else R.string.trip_status_finished)) },
        trailingContent = {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("trip_menu_${trip.id}")) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trip_menu)) }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.trip_rename)) }, onClick = { menu = false; rename = trip.title }, modifier = Modifier.testTag("trip_rename_${trip.id}"))
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(if (trip.archived) R.string.trip_unarchive else R.string.trip_archive)) },
                        onClick = { menu = false; onTripAction(TripAction.Archive(trip.id, !trip.archived)) },
                        // An active trip is where the next recording goes: it cannot be put away (review I5).
                        enabled = !recording && !active,
                        modifier = Modifier.testTag("trip_archive_${trip.id}"),
                    )
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.trip_export)) }, onClick = { menu = false; onTripAction(TripAction.Export(trip.id)) }, modifier = Modifier.testTag("trip_export_${trip.id}"))
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.trip_video)) }, onClick = { menu = false; onTripAction(TripAction.Video(trip.id)) }, modifier = Modifier.testTag("trip_video_${trip.id}"))
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.trip_delete)) },
                        onClick = { menu = false; confirmDelete = true },
                        enabled = !recording,
                        colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("trip_delete_${trip.id}"),
                    )
                }
            }
        },
        modifier = Modifier.clickable { onOpenTrip(trip.id) }.testTag("home_trip_${trip.id}"),
    )
    rename?.let { initial ->
        // The whole title starts selected so typing replaces it (the common case is a fresh name).
        var field by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
            androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length)))
        }
        val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(stringResource(R.string.trip_rename)) },
            text = { androidx.compose.material3.OutlinedTextField(value = field, onValueChange = { field = it }, singleLine = true, modifier = Modifier.focusRequester(focus).testTag("trip_rename_field")) },
            confirmButton = { TextButton(onClick = { onTripAction(TripAction.Rename(trip.id, field.text)); rename = null }, enabled = field.text.isNotBlank(), modifier = Modifier.testTag("trip_rename_save")) { Text(stringResource(R.string.settings_privacy_save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.active_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trip_delete_title, trip.title)) },
            text = { Text(stringResource(R.string.trip_delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onTripAction(TripAction.Delete(trip.id)) }, modifier = Modifier.testTag("trip_delete_confirm")) { Text(stringResource(R.string.trip_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.active_cancel)) } },
        )
    }
}
