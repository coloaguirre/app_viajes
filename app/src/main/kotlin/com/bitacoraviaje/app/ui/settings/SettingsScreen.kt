package com.bitacoraviaje.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.settings.Units
import com.bitacoraviaje.core.model.RecordingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_recording_mode))
            RadioRow(
                title = stringResource(R.string.settings_recording_mode_full),
                description = stringResource(R.string.settings_recording_mode_full_desc),
                selected = settings.recordingMode == RecordingMode.FULL,
                onClick = { viewModel.setRecordingMode(RecordingMode.FULL) },
            )
            RadioRow(
                title = stringResource(R.string.settings_recording_mode_economy),
                description = stringResource(R.string.settings_recording_mode_economy_desc),
                selected = settings.recordingMode == RecordingMode.ECONOMY,
                onClick = { viewModel.setRecordingMode(RecordingMode.ECONOMY) },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_units))
            RadioRow(
                title = stringResource(R.string.settings_units_metric),
                selected = settings.units == Units.METRIC,
                onClick = { viewModel.setUnits(Units.METRIC) },
            )
            RadioRow(
                title = stringResource(R.string.settings_units_imperial),
                selected = settings.units == Units.IMPERIAL,
                onClick = { viewModel.setUnits(Units.IMPERIAL) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_transcription_language)) },
                supportingContent = { Text(settings.transcriptionLanguage) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, description: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
