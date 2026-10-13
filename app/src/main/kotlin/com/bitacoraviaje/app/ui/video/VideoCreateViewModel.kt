@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bitacoraviaje.app.ui.video

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.work.WorkManager
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.app.video.RenderStatus
import com.bitacoraviaje.app.video.RenderWorker
import com.bitacoraviaje.app.video.TripVideoPlanner
import com.bitacoraviaje.app.video.VideoParams
import com.bitacoraviaje.app.video.VideoPlanStore
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.core.videoplan.VideoPlan.Companion.toJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VideoCreateUiState(
    val params: VideoParams = VideoParams(),
    val plan: VideoPlan? = null,
    val planning: Boolean = false,
    val message: String? = null,
)

/** DISENO §14 pantalla 11 / PLAN 5.1: parameters → editable plan → render in the foreground worker. */
@HiltViewModel
class VideoCreateViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val planner: TripVideoPlanner,
    private val store: VideoPlanStore,
) : ViewModel() {
    val tripId: String = savedState.toRoute<Route.VideoCreate>().tripId
    private val _state = MutableStateFlow(VideoCreateUiState())
    val state: StateFlow<VideoCreateUiState> = _state.asStateFlow()

    val render: StateFlow<RenderStatus> = WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(RenderWorker.tripWorkName(tripId))
        .map { infos -> RenderStatus.from(infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RenderStatus())

    init {
        store.load(tripId)?.let { (p, plan) -> _state.value = VideoCreateUiState(params = p, plan = plan) }
    }

    fun setParams(update: (VideoParams) -> VideoParams) {
        _state.value = _state.value.copy(params = update(_state.value.params), plan = null)
    }

    fun setMusic(uri: Uri?) {
        uri?.let { runCatching { appContext.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        setParams { it.copy(musicUri = uri?.toString()) }
    }

    fun generatePlan() {
        if (_state.value.planning) return
        _state.value = _state.value.copy(planning = true, message = null)
        viewModelScope.launch {
            val params = _state.value.params
            val result = runCatching { planner.plan(tripId, params.toPlanParams()) }.getOrNull()
            val plan = result?.second
            if (plan != null) store.save(tripId, params, plan)
            _state.value = _state.value.copy(planning = false, plan = plan, message = if (plan == null) appContext.getString(com.bitacoraviaje.app.R.string.video_error_no_segment) else null)
        }
    }

    /** The plan the user sees is the plan that renders: edits are persisted at once. */
    private fun updatePlan(scenes: List<VideoPlan.Scene>) {
        val plan = _state.value.plan ?: return
        val updated = plan.copy(scenes = scenes, total_duration_ms = scenes.sumOf { it.durationMs })
        store.save(tripId, _state.value.params, updated)
        _state.value = _state.value.copy(plan = updated)
    }

    fun removeScene(index: Int) = _state.value.plan?.scenes?.let { s -> if (index in s.indices) updatePlan(s.filterIndexed { i, _ -> i != index }) }

    fun moveScene(index: Int, delta: Int) = _state.value.plan?.scenes?.let { s ->
        val j = index + delta
        if (index in s.indices && j in s.indices) updatePlan(s.toMutableList().also { l -> l[index] = l[j].also { l[j] = l[index] } })
    }

    fun renderVideo() {
        if (_state.value.plan == null) return
        RenderWorker.enqueueTrip(appContext, tripId)
    }

    fun cancelRender() = RenderWorker.cancelTrip(appContext, tripId)

    /** `video_plan.json` for the desktop renderer: `bitacora-render render export/ plan.json`. */
    fun savePlanTo(uri: Uri) {
        val plan = _state.value.plan ?: return
        viewModelScope.launch {
            val ok = runCatching { appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(plan.toJson().toByteArray()) } }.isSuccess
            _state.value = _state.value.copy(message = appContext.getString(if (ok) com.bitacoraviaje.app.R.string.video_plan_saved else com.bitacoraviaje.app.R.string.finish_video_open_failed))
        }
    }
}
