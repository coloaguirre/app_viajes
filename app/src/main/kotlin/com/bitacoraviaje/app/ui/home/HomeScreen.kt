package com.bitacoraviaje.app.ui.home

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
import androidx.compose.material3.ListItem
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
fun HomeRoute(onOpenSettings: () -> Unit, onNewSession: () -> Unit, onOpenActiveSession: (String) -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val denied = stringResource(R.string.home_permission_denied)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is HomeEvent.OpenActiveSession -> onOpenActiveSession(e.segmentId)
                HomeEvent.PermissionDenied -> snackbar.showSnackbar(denied)
            }
        }
    }
    HomeScreen(
        state = state,
        snackbar = snackbar,
        onOpenSettings = onOpenSettings,
        onNewRecording = onNewSession,
        onResumeActive = { id -> onOpenActiveSession(id) },
        onRecoverResume = viewModel::resumeOpenSegment,
        onRecoverClose = viewModel::closeOpenSegmentAsRecovered,
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
            if (state.trips.isEmpty()) {
                EmptyTrips()
            } else {
                TripList(state.trips)
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

@Composable
private fun TripList(trips: List<Trip>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(trips, key = { it.id }) { trip ->
            ListItem(
                headlineContent = { Text(trip.title) },
                supportingContent = { Text(trip.status.name) },
            )
        }
    }
}
