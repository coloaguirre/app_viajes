package com.bitacoraviaje.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.settings.REST_AFTER_OPTIONS_MIN
import com.bitacoraviaje.core.model.Units
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
            SectionTitle(stringResource(R.string.settings_rest_after))
            Text(
                stringResource(R.string.settings_rest_after_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            REST_AFTER_OPTIONS_MIN.forEach { min ->
                RadioRow(
                    title = pluralStringResource(R.plurals.settings_rest_after_option, min, min),
                    selected = settings.restAfterMin == min,
                    onClick = { viewModel.setRestAfterMin(min) },
                )
            }
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
            SectionTitle(stringResource(R.string.settings_engine))
            RadioRow(stringResource(R.string.settings_engine_auto), settings.transcriptionEngine == "auto", { viewModel.setTranscriptionEngine("auto") }, stringResource(R.string.settings_engine_auto_desc))
            RadioRow(stringResource(R.string.settings_engine_whisper), settings.transcriptionEngine == "whisper", { viewModel.setTranscriptionEngine("whisper") }, stringResource(R.string.settings_engine_whisper_desc))
            RadioRow(stringResource(R.string.settings_engine_system), settings.transcriptionEngine == "system", { viewModel.setTranscriptionEngine("system") }, stringResource(R.string.settings_engine_system_desc))
            SectionTitle(stringResource(R.string.settings_whisper))
            Text(stringResource(R.string.settings_whisper_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            val models by viewModel.models.collectAsStateWithLifecycle()
            models.forEach { m ->
                ModelRow(
                    state = m,
                    selected = settings.transcriptionModel == m.model.id,
                    onSelect = { viewModel.setTranscriptionModel(m.model) },
                    onDownload = { viewModel.downloadModel(m.model) },
                    onCancel = { viewModel.cancelDownload(m.model) },
                    onDelete = { viewModel.deleteModel(m.model) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = settings.transcribeOnlyCharging, onCheckedChange = viewModel::setTranscribeOnlyCharging, modifier = Modifier.testTag("settings_only_charging"))
                Text(stringResource(R.string.settings_transcribe_only_charging), modifier = Modifier.padding(start = 12.dp))
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_transcription_language)) },
                supportingContent = { Text(settings.transcriptionLanguage) },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_backups))
            val backups by viewModel.backups.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshBackups() }
            if (backups.isEmpty()) {
                Text(stringResource(R.string.settings_backups_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                Text(stringResource(R.string.settings_backups_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                backups.forEach { f ->
                    ListItem(
                        headlineContent = { Text(f.name.removeSuffix(".json.gz").removeSuffix(".json").take(8) + "… · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(f.lastModified()))) },
                        supportingContent = { Text(stringResource(R.string.settings_backup_size, f.length() / 1024)) },
                        trailingContent = { androidx.compose.material3.TextButton(onClick = { viewModel.restoreBackup(f) }, modifier = Modifier.testTag("backup_restore_${f.name}")) { Text(stringResource(R.string.settings_backup_restore)) } },
                    )
                }
            }
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_osm))
            Text(stringResource(R.string.settings_osm_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            UrlField(stringResource(R.string.settings_nominatim), settings.nominatimUrl, viewModel::setNominatimUrl, "settings_nominatim")
            UrlField(stringResource(R.string.settings_overpass), settings.overpassUrl, viewModel::setOverpassUrl, "settings_overpass")
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_enrich_places)) },
                supportingContent = { Text(stringResource(R.string.settings_enrich_places_desc)) },
                trailingContent = { androidx.compose.material3.Switch(checked = settings.enrichPlaces, onCheckedChange = viewModel::setEnrichPlaces, modifier = Modifier.testTag("settings_enrich_places")) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_round_coords)) },
                supportingContent = { Text(stringResource(R.string.settings_round_coords_desc)) },
                trailingContent = { androidx.compose.material3.Switch(checked = settings.roundEnrichmentCoords, onCheckedChange = viewModel::setRoundEnrichmentCoords, enabled = settings.enrichPlaces, modifier = Modifier.testTag("settings_round_coords")) },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_privacy))
            Text(stringResource(R.string.settings_privacy_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            PrivacyZonesBlock(
                zones = settings.privacyZones,
                message = viewModel.privacyMessage.collectAsStateWithLifecycle().value,
                onAddHere = viewModel::addPrivacyZoneHere,
                onAddManual = viewModel::addPrivacyZone,
                onRemove = viewModel::removePrivacyZone,
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_llm))
            Text(stringResource(R.string.settings_llm_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            val hasKey by viewModel.hasLlmKey.collectAsStateWithLifecycle()
            SecretField(hasKey = hasKey, onSave = { viewModel.setLlmKey(it) }, onDelete = { viewModel.setLlmKey(null) })
            if (hasKey) UrlField(stringResource(R.string.settings_llm_model), settings.llmModel, viewModel::setLlmModel, "settings_llm_model")
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            )
        }
    }
}

@Composable
private fun ModelRow(state: ModelState, selected: Boolean, onSelect: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val m = state.model
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(m.labelEs, style = MaterialTheme.typography.bodyLarge)
            when {
                state.downloading -> {
                    Text(stringResource(R.string.model_downloading, state.doneBytes / 1_048_576, m.sizeBytes / 1_048_576), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.LinearProgressIndicator(progress = { state.doneBytes.toFloat() / m.sizeBytes }, modifier = Modifier.fillMaxWidth())
                }
                state.installed -> Text(stringResource(R.string.model_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                state.error != null -> Text(stringResource(R.string.model_failed, state.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(R.string.model_not_downloaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            state.downloading -> androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.testTag("model_cancel_${m.id}")) { Text(stringResource(R.string.active_cancel)) }
            state.installed -> androidx.compose.material3.TextButton(onClick = onDelete, modifier = Modifier.testTag("model_delete_${m.id}")) { Text(stringResource(R.string.model_delete)) }
            else -> androidx.compose.material3.Button(onClick = onDownload, modifier = Modifier.testTag("model_download_${m.id}")) { Text(stringResource(R.string.model_download)) }
        }
    }
}

@Composable
private fun UrlField(label: String, value: String, onCommit: (String) -> Unit, tag: String) {
    var text by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableStateOf(value) }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        trailingIcon = { androidx.compose.material3.TextButton(onClick = { onCommit(text) }, enabled = text != value) { Text(stringResource(R.string.settings_apply)) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

/** PLAN 5.5: list of zones, add from the current position or by coordinates, remove. */
@Composable
fun PrivacyZonesBlock(
    zones: List<com.bitacoraviaje.core.model.PrivacyZone>,
    message: String?,
    onAddHere: (String, Int) -> Unit,
    onAddManual: (String, Double, Double, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var dialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) } // "here" | "manual"
    if (zones.isEmpty()) {
        Text(stringResource(R.string.settings_privacy_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("privacy_none"))
    } else {
        zones.forEach { z ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_privacy_zone_line, z.name, z.radiusM.toInt())) },
                trailingContent = { androidx.compose.material3.TextButton(onClick = { onRemove(z.id) }, modifier = Modifier.testTag("privacy_remove_${z.id}")) { Text(stringResource(R.string.settings_privacy_delete)) } },
                modifier = Modifier.testTag("privacy_zone_${z.id}"),
            )
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).testTag("privacy_message")) }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = { dialog = "here" }, modifier = Modifier.testTag("privacy_add_here")) { Text(stringResource(R.string.settings_privacy_add_here)) }
        androidx.compose.material3.TextButton(onClick = { dialog = "manual" }, modifier = Modifier.testTag("privacy_add_manual")) { Text(stringResource(R.string.settings_privacy_add_manual)) }
    }
    dialog?.let { mode ->
        var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var lat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var lon by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var radius by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.bitacoraviaje.app.settings.PrivacyZones.RADII_M[1]) }
        val coords = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 } to lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (mode == "here") R.string.settings_privacy_add_here else R.string.settings_privacy_add_manual)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.settings_privacy_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("privacy_name"))
                    if (mode == "manual") {
                        androidx.compose.material3.OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text(stringResource(R.string.settings_privacy_lat)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("privacy_lat"))
                        androidx.compose.material3.OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text(stringResource(R.string.settings_privacy_lon)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("privacy_lon"))
                    }
                    Text(stringResource(R.string.settings_privacy_radius), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    // Scrolls sideways: four chips do not fit a narrow dialog and the last one would wrap vertically.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        com.bitacoraviaje.app.settings.PrivacyZones.RADII_M.forEach { r ->
                            androidx.compose.material3.FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text(stringResource(R.string.settings_privacy_radius_m, r)) }, modifier = Modifier.testTag("privacy_radius_$r"))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (mode == "here") onAddHere(name, radius) else coords.first?.let { la -> coords.second?.let { lo -> onAddManual(name, la, lo, radius) } }
                        dialog = null
                    },
                    enabled = mode == "here" || (coords.first != null && coords.second != null),
                    modifier = Modifier.testTag("privacy_save"),
                ) { Text(stringResource(R.string.settings_privacy_save)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_privacy_cancel)) } },
        )
    }
}

/** API key entry: never shows the stored value, only whether one exists (skill play-policy-privacy). */
@Composable
private fun SecretField(hasKey: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var text by androidx.compose.runtime.remember(hasKey) { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.settings_llm_key)) },
        placeholder = { Text(stringResource(if (hasKey) R.string.settings_llm_key_set else R.string.settings_llm_key_none)) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            Row {
                if (hasKey) androidx.compose.material3.TextButton(onClick = onDelete, modifier = Modifier.testTag("settings_llm_key_delete")) { Text(stringResource(R.string.settings_llm_key_delete)) }
                androidx.compose.material3.TextButton(onClick = { onSave(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.testTag("settings_llm_key_save")) { Text(stringResource(R.string.settings_llm_key_save)) }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("settings_llm_key"),
    )
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
