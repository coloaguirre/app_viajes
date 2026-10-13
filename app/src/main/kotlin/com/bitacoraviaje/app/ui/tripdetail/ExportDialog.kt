package com.bitacoraviaje.app.ui.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.export.ExportDestination
import com.bitacoraviaje.app.export.ExportOptions
import com.bitacoraviaje.app.export.ExportStatus

/** Options + destination (DISENO §12.1, §12.4); shows the running job and its result. */
@Composable
fun ExportDialog(
    status: ExportStatus,
    onDismiss: () -> Unit,
    onStart: (ExportOptions, ExportDestination) -> Unit,
    onShareResult: () -> Unit,
    onReset: () -> Unit,
    /** Privacy zones configured in Settings (PLAN 5.5); 0 hides the options. */
    zones: Int = 0,
) {
    var media by rememberSaveable { mutableStateOf(true) }
    var audio by rememberSaveable { mutableStateOf(true) }
    var filtered by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(true) }
    var stripExif by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    status.running -> {
                        Text(stringResource(R.string.export_running, status.done, status.total), modifier = Modifier.testTag("export_running"))
                        if (status.total > 0) LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    status.finished && status.failed -> Text(stringResource(R.string.export_failed, status.error ?: ""), modifier = Modifier.testTag("export_failed"))
                    status.finished -> {
                        Text(stringResource(R.string.export_done, status.folder ?: ""), modifier = Modifier.testTag("export_done"))
                        Text(stringResource(R.string.export_done_counts, status.mediaWritten, status.mediaTotal, status.audioWritten, status.audioTotal))
                        if (status.missing > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.export_done_missing, status.missing, status.missing))
                        if (status.privacyPoints + status.privacyStops + status.privacyMedia > 0) {
                            val parts = listOf(
                                androidx.compose.ui.res.pluralStringResource(R.plurals.export_done_privacy_points, status.privacyPoints, status.privacyPoints),
                                androidx.compose.ui.res.pluralStringResource(R.plurals.export_done_privacy_stops, status.privacyStops, status.privacyStops),
                                androidx.compose.ui.res.pluralStringResource(R.plurals.export_done_privacy_media, status.privacyMedia, status.privacyMedia),
                            )
                            Text(stringResource(R.string.export_done_privacy, parts.joinToString(", ")), modifier = Modifier.testTag("export_done_privacy"))
                        }
                    }
                    else -> {
                        OptionRow(stringResource(R.string.export_opt_media), media, { media = it }, "export_opt_media")
                        OptionRow(stringResource(R.string.export_opt_audio), audio, { audio = it }, "export_opt_audio")
                        OptionRow(stringResource(R.string.export_opt_filtered), filtered, { filtered = it }, "export_opt_filtered")
                        if (zones > 0) {
                            OptionRow(stringResource(R.string.export_opt_privacy, zones), privacy, { privacy = it }, "export_opt_privacy")
                            if (privacy) OptionRow(stringResource(R.string.export_opt_strip_exif), stripExif, { stripExif = it }, "export_opt_strip_exif")
                        }
                        Text(stringResource(R.string.export_destination_hint))
                    }
                }
            }
        },
        confirmButton = {
            // The worker reads the zones itself: a tap before the preference count arrives must not export unfiltered (review M4).
            val opts = ExportOptions(media, audio, filtered, applyPrivacyZones = privacy, stripExifGps = stripExif)
            when {
                status.running -> Unit
                status.finished && !status.failed -> Button(onClick = onShareResult, modifier = Modifier.testTag("export_share_result")) { Text(stringResource(R.string.export_share)) }
                status.finished -> TextButton(onClick = onReset, modifier = Modifier.testTag("export_reset")) { Text(stringResource(R.string.export_retry)) }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { onStart(opts, ExportDestination.SAF) }, modifier = Modifier.fillMaxWidth().testTag("export_saf")) { Text(stringResource(R.string.export_dest_saf)) }
                    OutlinedButton(onClick = { onStart(opts, ExportDestination.DOWNLOADS) }, modifier = Modifier.fillMaxWidth().testTag("export_downloads")) { Text(stringResource(R.string.export_dest_downloads)) }
                    OutlinedButton(onClick = { onStart(opts, ExportDestination.SHARE) }, modifier = Modifier.fillMaxWidth().testTag("export_share")) { Text(stringResource(R.string.export_dest_share)) }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // A finished job stays visible (WorkManager keeps it) until the user asks for a new export (E2E 5.5: there was no way back).
                if (status.finished && !status.failed) TextButton(onClick = onReset, modifier = Modifier.testTag("export_reset")) { Text(stringResource(R.string.export_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.active_cancel)) }
            }
        },
    )
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Text(label)
    }
}
