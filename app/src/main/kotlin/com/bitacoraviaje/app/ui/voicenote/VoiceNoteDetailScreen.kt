package com.bitacoraviaje.app.ui.voicenote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.bitacoraviaje.core.model.TranscriptStatus

@Composable
fun VoiceNoteDetailRoute(mapRenderer: MapRenderer, onBack: () -> Unit, viewModel: VoiceNoteDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VoiceNoteDetailScreen(state, onBack, viewModel::togglePlay, viewModel::saveEdit, viewModel::retryTranscription, map = { s, m -> mapRenderer.TrackMap(s, m) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNoteDetailScreen(
    state: VoiceNoteDetailUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
    map: @Composable (TrackMapState, Modifier) -> Unit = { _, m -> Box(m) },
) {
    val n = state.note
    var text by remember { mutableStateOf("") }
    LaunchedEffect(n?.id, n?.transcript, n?.transcriptEdited) { text = n?.displayText ?: "" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_detail_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (n == null) return@Column
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTogglePlay, modifier = Modifier.height(56.dp).testTag("voice_play")) {
                    Text(stringResource(if (state.isPlaying) R.string.voice_pause else R.string.voice_play))
                }
                Text(TrackingNotificationFactory.formatElapsed(n.durationMs).let { if (n.durationMs < 60_000) "${n.durationMs / 1000} s" else it }, style = MaterialTheme.typography.titleMedium)
                val statusRes = when (n.transcriptStatus) {
                    TranscriptStatus.PENDING, TranscriptStatus.RUNNING -> R.string.voice_status_pending
                    TranscriptStatus.DONE -> R.string.voice_status_done
                    TranscriptStatus.FAILED -> R.string.voice_status_failed
                }
                AssistChip(onClick = {}, label = { Text(stringResource(statusRes)) }, modifier = Modifier.testTag("voice_status"))
            }
            // Retry also when a past run stored nothing (status DONE, empty text): otherwise the note is stuck.
            if (n.transcriptStatus == TranscriptStatus.FAILED || (n.transcriptStatus == TranscriptStatus.DONE && n.displayText.isNullOrBlank())) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("voice_retry")) { Text(stringResource(R.string.voice_retry)) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.voice_transcript_label)) },
                modifier = Modifier.fillMaxWidth().testTag("voice_transcript"),
                minLines = 3,
            )
            Button(onClick = { onSave(text) }, enabled = text != (n.displayText ?: ""), modifier = Modifier.fillMaxWidth().testTag("voice_save")) { Text(stringResource(R.string.voice_save)) }
            if (n.lat != null) map(state.map, Modifier.fillMaxWidth().weight(1f).testTag("voice_map")) else Text(stringResource(R.string.voice_no_position))
        }
    }
}
