package com.bitacoraviaje.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.core.model.Units
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.settings.UserSettings
import com.bitacoraviaje.app.voice.models.ModelDownloadWorker
import com.bitacoraviaje.app.voice.models.ModelStore
import com.bitacoraviaje.app.voice.models.WhisperModel
import kotlinx.coroutines.flow.map
import com.bitacoraviaje.core.model.RecordingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Download state of one whisper model as the settings screen shows it. */
data class ModelState(val model: WhisperModel, val installed: Boolean, val downloading: Boolean, val doneBytes: Long, val error: String?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val prefs: UserPreferences,
    private val secrets: com.bitacoraviaje.app.settings.Secrets,
    private val stateHolder: com.bitacoraviaje.app.tracking.TrackingStateHolder,
    private val tracking: com.bitacoraviaje.data.repository.TrackingRepository,
    private val offlineMaps: com.bitacoraviaje.app.map.OfflineMaps,
) : ViewModel() {
    /** PLAN 5.4: offline map regions on the phone (null while loading). */
    val offlineRegions: kotlinx.coroutines.flow.StateFlow<List<com.bitacoraviaje.app.map.OfflineRegionInfo>?> = offlineMaps.regions
    val offlineAvailable: Boolean get() = offlineMaps.isAvailable

    init {
        viewModelScope.launch { runCatching { offlineMaps.refresh() } }
    }

    fun deleteOfflineRegion(id: Long) = viewModelScope.launch { offlineMaps.delete(id) }

    /** PLAN 5.5: privacy zones. The position comes from the live session or the last recorded point; never from a network lookup. */
    val privacyMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun addPrivacyZoneHere(name: String, radiusM: Int) = viewModelScope.launch {
        val live = stateHolder.state.value.lastPoint
        val point = live ?: tracking.segmentsOfLatestTrip().firstNotNullOfOrNull { s -> tracking.lastValidPoint(s.id) }
        if (point == null) { privacyMessage.value = appContext.getString(com.bitacoraviaje.app.R.string.settings_privacy_no_location); return@launch }
        addPrivacyZone(name, point.lat, point.lon, radiusM)
    }

    fun addPrivacyZone(name: String, lat: Double, lon: Double, radiusM: Int) = viewModelScope.launch {
        val zone = com.bitacoraviaje.core.model.PrivacyZone(java.util.UUID.randomUUID().toString(), name.ifBlank { appContext.getString(com.bitacoraviaje.app.R.string.settings_privacy) }, lat, lon, radiusM.toDouble())
        prefs.updatePrivacyZones { it + zone }
        privacyMessage.value = null
    }

    fun removePrivacyZone(id: String) = viewModelScope.launch { prefs.updatePrivacyZones { zones -> zones.filter { it.id != id } } }

    val hasLlmKey: StateFlow<Boolean> = secrets.has(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY)

    /** Keystore + file I/O off the main thread (review M9). */
    fun setLlmKey(key: String?) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { secrets.set(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY, key) }

    fun setLlmModel(model: String) = viewModelScope.launch { prefs.setLlmModel(model) }

    val settings: StateFlow<UserSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val store = ModelStore(java.io.File(appContext.filesDir, "models"))
    private val refresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** One entry per model; installed state re-read whenever a download job changes. */
    val models: StateFlow<List<ModelState>> = kotlinx.coroutines.flow.combine(
        WhisperModel.entries.map { m -> androidx.work.WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workName(m)) } + listOf(refresh.map { emptyList<androidx.work.WorkInfo>() }),
    ) { lists ->
        WhisperModel.entries.mapIndexed { i, m ->
            val info = lists[i].firstOrNull()
            val running = info != null && (info.state == androidx.work.WorkInfo.State.RUNNING || info.state == androidx.work.WorkInfo.State.ENQUEUED)
            ModelState(
                model = m, installed = store.isInstalled(m) && store.isVadInstalled(), downloading = running,
                doneBytes = info?.progress?.getLong(ModelDownloadWorker.KEY_DONE, 0L) ?: 0L,
                error = info?.takeIf { it.state == androidx.work.WorkInfo.State.FAILED }?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WhisperModel.entries.map { ModelState(it, store.isInstalled(it) && store.isVadInstalled(), false, 0L, null) })

    fun downloadModel(model: WhisperModel) = ModelDownloadWorker.enqueue(appContext, model)

    fun cancelDownload(model: WhisperModel) = ModelDownloadWorker.cancel(appContext, model)

    fun deleteModel(model: WhisperModel) {
        store.delete(model)
        refresh.value++
    }

    fun setTranscriptionModel(model: WhisperModel) = viewModelScope.launch { prefs.setTranscriptionModel(model.id) }

    fun setTranscribeOnlyCharging(value: Boolean) = viewModelScope.launch { prefs.setTranscribeOnlyCharging(value) }

    fun setTranscriptionEngine(id: String) = viewModelScope.launch { prefs.setTranscriptionEngine(id) }

    fun setNominatimUrl(url: String) = viewModelScope.launch { prefs.setNominatimUrl(url) }

    /** Backups the app wrote (or Android restored) under files/backup, newest first (review I4). */
    val backups: StateFlow<List<java.io.File>> = refresh.map {
        com.bitacoraviaje.app.export.BackupFiles.dir(appContext).listFiles { f -> f.isFile && (f.name.endsWith(".json.gz") || f.name.endsWith(".json")) }?.sortedByDescending { it.lastModified() }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Restores a backup through the same import path as an external file; the trip id exists → the Home dialog asks. */
    fun restoreBackup(file: java.io.File) = com.bitacoraviaje.app.export.ImportWorker.enqueue(appContext, android.net.Uri.fromFile(file))

    fun refreshBackups() { refresh.value++ }

    fun setOverpassUrl(url: String) = viewModelScope.launch { prefs.setOverpassUrl(url) }

    fun setEnrichPlaces(value: Boolean) = viewModelScope.launch { prefs.setEnrichPlaces(value) }

    fun setRoundEnrichmentCoords(value: Boolean) = viewModelScope.launch { prefs.setRoundEnrichmentCoords(value) }

    fun setUnits(units: Units) = viewModelScope.launch { prefs.setUnits(units) }

    fun setRecordingMode(mode: RecordingMode) = viewModelScope.launch { prefs.setRecordingMode(mode) }

    fun setTranscriptionLanguage(tag: String) = viewModelScope.launch { prefs.setTranscriptionLanguage(tag) }

    fun setRestAfterMin(minutes: Int) = viewModelScope.launch { prefs.setRestAfterMin(minutes) }
}
