@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bitacoraviaje.app.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.video.RenderStatus
import com.bitacoraviaje.app.video.VideoParams
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.VideoPlan

@Composable
fun VideoCreateRoute(onBack: () -> Unit, viewModel: VideoCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val render by viewModel.render.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickMusic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> viewModel.setMusic(uri) }
    val savePlan = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::savePlanTo) }
    VideoCreateScreen(
        state = state,
        render = render,
        onBack = onBack,
        onParams = viewModel::setParams,
        onPickMusic = { pickMusic.launch(arrayOf("audio/*")) },
        onGenerate = viewModel::generatePlan,
        onRemove = viewModel::removeScene,
        onMove = viewModel::moveScene,
        onRender = viewModel::renderVideo,
        onCancel = viewModel::cancelRender,
        onSavePlan = { savePlan.launch("video_plan.json") },
        onOpen = { uri -> openResult(context, uri, share = false) },
        onShare = { uri -> openResult(context, uri, share = true) },
    )
}

private fun openResult(context: android.content.Context, result: String, share: Boolean) {
    val uri = runCatching { if (result.startsWith("content://")) android.net.Uri.parse(result) else androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(android.net.Uri.parse(result).path ?: result)) }
        .getOrElse { android.widget.Toast.makeText(context, R.string.finish_video_open_failed, android.widget.Toast.LENGTH_SHORT).show(); return }
    val intent = if (share) {
        android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/mp4").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.video_share))
    } else {
        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { android.widget.Toast.makeText(context, R.string.finish_video_open_failed, android.widget.Toast.LENGTH_SHORT).show() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoCreateScreen(
    state: VideoCreateUiState,
    render: RenderStatus,
    onBack: () -> Unit,
    onParams: ((VideoParams) -> VideoParams) -> Unit,
    onPickMusic: () -> Unit,
    onGenerate: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    onSavePlan: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val p = state.params
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.video_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("video_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.video_duration), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanParams.DURATIONS_S.forEach { s ->
                        FilterChip(selected = p.durationS == s, onClick = { onParams { it.copy(durationS = s) } }, label = { Text(stringResource(R.string.video_duration_min, s / 60)) }, modifier = Modifier.testTag("video_duration_$s"))
                    }
                }
                Text(stringResource(R.string.video_aspect), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = p.aspect == "16:9", onClick = { onParams { it.copy(aspect = "16:9") } }, label = { Text(stringResource(R.string.video_aspect_landscape)) }, modifier = Modifier.testTag("video_aspect_landscape"))
                    FilterChip(selected = p.aspect == "9:16", onClick = { onParams { it.copy(aspect = "9:16") } }, label = { Text(stringResource(R.string.video_aspect_portrait)) }, modifier = Modifier.testTag("video_aspect_portrait"))
                }
                Text(stringResource(R.string.video_voice), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("subtitles" to R.string.video_voice_subtitles, "audio" to R.string.video_voice_audio, "both" to R.string.video_voice_both).forEach { (id, res) ->
                        FilterChip(selected = p.voice == id, onClick = { onParams { it.copy(voice = id) } }, label = { Text(stringResource(res)) }, modifier = Modifier.testTag("video_voice_$id"))
                    }
                }
                ToggleRow(stringResource(R.string.video_include_places), p.includePlaces, "video_places") { v -> onParams { it.copy(includePlaces = v) } }
                ToggleRow(stringResource(R.string.video_include_map), p.includeMap, "video_map") { v -> onParams { it.copy(includeMap = v) } }
                ToggleRow(stringResource(R.string.video_include_videos), p.includeVideos, "video_videos") { v -> onParams { it.copy(includeVideos = v) } }
                Text(stringResource(R.string.video_music), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.musicUri?.let { android.net.Uri.parse(it).lastPathSegment ?: it } ?: stringResource(R.string.video_music_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onPickMusic, modifier = Modifier.testTag("video_music_pick")) { Text(stringResource(R.string.video_music_pick)) }
                    if (p.musicUri != null) TextButton(onClick = { onParams { it.copy(musicUri = null) } }) { Text(stringResource(R.string.video_music_clear)) }
                }
                if (p.musicUri != null) {
                    Text(stringResource(R.string.video_music_volume, (p.musicVolume * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    Slider(value = p.musicVolume.toFloat(), onValueChange = { v -> onParams { it.copy(musicVolume = v.toDouble()) } }, valueRange = 0f..1f)
                }
                Button(onClick = onGenerate, enabled = !state.planning && !render.running, modifier = Modifier.fillMaxWidth().testTag("video_generate")) { Text(stringResource(R.string.video_generate_plan)) }
                if (state.planning) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("video_message")) }
            }
            val plan = state.plan
            if (plan == null) {
                item { Text(stringResource(R.string.video_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("video_plan_empty")) }
            } else {
                item {
                    Text(stringResource(R.string.video_plan_summary, plan.scenes.size, plan.total_duration_ms / 1000), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("video_plan_summary"))
                }
                itemsIndexed(plan.scenes, key = { i, s -> "$i-${s::class.simpleName}" }) { i, scene ->
                    ListItem(
                        headlineContent = { Text(sceneLabel(scene)) },
                        supportingContent = { Text("${scene.durationMs / 1000.0} s") },
                        leadingContent = { Text("${i + 1}") },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onMove(i, -1) }, enabled = i > 0, modifier = Modifier.testTag("scene_up_$i")) { Text("↑") }
                                IconButton(onClick = { onMove(i, 1) }, enabled = i < plan.scenes.lastIndex, modifier = Modifier.testTag("scene_down_$i")) { Text("↓") }
                                IconButton(onClick = { onRemove(i) }, modifier = Modifier.testTag("scene_remove_$i")) { Text("✕") }
                            }
                        },
                        modifier = Modifier.testTag("scene_$i"),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            render.running -> {
                                Text(stringResource(R.string.video_rendering, render.progress), modifier = Modifier.testTag("video_running"))
                                LinearProgressIndicator(progress = { render.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("video_cancel")) { Text(stringResource(R.string.video_cancel)) }
                            }
                            render.finished && render.resultUri != null -> {
                                Text(stringResource(R.string.video_done, render.durationMs / 1000, render.elapsedMs / 1000) + if (render.skipped > 0) " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.finish_video_skipped, render.skipped, render.skipped) else "", modifier = Modifier.testTag("video_done"))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { onOpen(render.resultUri) }, modifier = Modifier.weight(1f).testTag("video_open")) { Text(stringResource(R.string.video_open)) }
                                    OutlinedButton(onClick = { onShare(render.resultUri) }, modifier = Modifier.weight(1f).testTag("video_share")) { Text(stringResource(R.string.video_share)) }
                                }
                            }
                            render.failed -> Text(stringResource(R.string.video_failed, render.error ?: ""), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video_failed"))
                        }
                        if (!render.running) {
                            Button(onClick = onRender, modifier = Modifier.fillMaxWidth().testTag("video_render")) { Text(stringResource(R.string.video_render)) }
                            TextButton(onClick = onSavePlan, modifier = Modifier.testTag("video_save_plan")) { Text(stringResource(R.string.video_save_plan)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun sceneLabel(scene: VideoPlan.Scene): String = when (scene) {
    is VideoPlan.Title -> stringResource(R.string.scene_title) + " · " + scene.title
    is VideoPlan.MapRoute -> stringResource(R.string.scene_map) + " · " + scene.style
    is VideoPlan.Chapter -> stringResource(R.string.scene_chapter) + " · " + scene.title
    is VideoPlan.Photo -> stringResource(R.string.scene_photo) + (scene.caption?.let { " · $it" } ?: "")
    is VideoPlan.VideoClip -> stringResource(R.string.scene_clip)
    is VideoPlan.VoiceOverlay -> stringResource(R.string.scene_voice) + (scene.text?.let { " · $it" } ?: "")
    is VideoPlan.PlaceCard -> stringResource(R.string.scene_place) + " · " + scene.title
    is VideoPlan.Stats -> stringResource(R.string.scene_stats)
    is VideoPlan.Credits -> stringResource(R.string.scene_credits)
}
