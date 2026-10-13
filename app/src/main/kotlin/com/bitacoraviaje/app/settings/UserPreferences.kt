package com.bitacoraviaje.app.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Units
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DEFAULT_TRANSCRIPTION_LANGUAGE: String = "es-AR"
const val DEFAULT_REST_AFTER_MIN: Int = 5
const val DEFAULT_TRANSCRIPTION_MODEL: String = "base"

/** "auto" = best engine available (system on-device recognizer, then whisper); "whisper"; "system". */
const val DEFAULT_TRANSCRIPTION_ENGINE: String = "auto"
val TRANSCRIPTION_ENGINES: List<String> = listOf("auto", "whisper", "system")
val REST_AFTER_OPTIONS_MIN: List<Int> = listOf(3, 5, 10, 15)

data class UserSettings(
    val units: Units = Units.METRIC,
    val recordingMode: RecordingMode = RecordingMode.FULL,
    val transcriptionLanguage: String = DEFAULT_TRANSCRIPTION_LANGUAGE,
    /** Minutes still before the automatic rest state kicks in (adenda 1). */
    val restAfterMin: Int = DEFAULT_REST_AFTER_MIN,
    /** whisper model id ("base" | "small"), PLAN 3.4. */
    val transcriptionModel: String = DEFAULT_TRANSCRIPTION_MODEL,
    val transcribeOnlyCharging: Boolean = false,
    val transcriptionEngine: String = DEFAULT_TRANSCRIPTION_ENGINE,
) {
    companion object {
        /**
         * Pure mapping from persisted strings to settings. A null value means "never
         * set"; an unknown value means the store is corrupt or from a newer version and
         * falls back to the default, reported through [onInvalid] so it is never silent.
         */
        fun from(
            units: String?,
            recordingMode: String?,
            transcriptionLanguage: String?,
            restAfterMin: Int? = null,
            transcriptionModel: String? = null,
            transcribeOnlyCharging: Boolean? = null,
            transcriptionEngine: String? = null,
            onInvalid: (key: String, value: String) -> Unit = { _, _ -> },
        ): UserSettings = UserSettings(
            units = parseEnum<Units>(units, Units.METRIC) { onInvalid("units", it) },
            recordingMode = parseEnum<RecordingMode>(recordingMode, RecordingMode.FULL) { onInvalid("recording_mode", it) },
            transcriptionLanguage = transcriptionLanguage?.takeIf { it.isNotBlank() } ?: DEFAULT_TRANSCRIPTION_LANGUAGE,
            restAfterMin = when {
                restAfterMin == null -> DEFAULT_REST_AFTER_MIN
                restAfterMin in 1..120 -> restAfterMin
                else -> { onInvalid("rest_after_min", restAfterMin.toString()); DEFAULT_REST_AFTER_MIN }
            },
            transcriptionModel = when (transcriptionModel) {
                null -> DEFAULT_TRANSCRIPTION_MODEL
                "base", "small" -> transcriptionModel
                else -> { onInvalid("transcription_model", transcriptionModel); DEFAULT_TRANSCRIPTION_MODEL }
            },
            transcribeOnlyCharging = transcribeOnlyCharging ?: false,
            transcriptionEngine = when (transcriptionEngine) {
                null -> DEFAULT_TRANSCRIPTION_ENGINE
                in TRANSCRIPTION_ENGINES -> transcriptionEngine
                else -> { onInvalid("transcription_engine", transcriptionEngine); DEFAULT_TRANSCRIPTION_ENGINE }
            },
        )

        private inline fun <reified E : Enum<E>> parseEnum(raw: String?, default: E, onInvalid: (String) -> Unit): E {
            if (raw == null) return default
            return enumValues<E>().firstOrNull { it.name == raw } ?: run { onInvalid(raw); default }
        }
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/** Plain preferences. Secrets (API keys) do NOT live here: they go to the encrypted store (Phase 4). */
@Singleton
class UserPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val UNITS = stringPreferencesKey("units")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        val REST_AFTER_MIN = intPreferencesKey("rest_after_min")
        val TRANSCRIPTION_MODEL = stringPreferencesKey("transcription_model")
        val TRANSCRIBE_ONLY_CHARGING = androidx.datastore.preferences.core.booleanPreferencesKey("transcribe_only_charging")
        val TRANSCRIPTION_ENGINE = stringPreferencesKey("transcription_engine")
    }

    val settings: Flow<UserSettings> = context.settingsStore.data.map { p ->
        UserSettings.from(
            units = p[Keys.UNITS],
            recordingMode = p[Keys.RECORDING_MODE],
            transcriptionLanguage = p[Keys.TRANSCRIPTION_LANGUAGE],
            restAfterMin = p[Keys.REST_AFTER_MIN],
            onInvalid = { key, value -> Log.w(TAG, "Ignoring invalid stored setting $key=$value, using default") },
            transcriptionModel = p[Keys.TRANSCRIPTION_MODEL],
            transcribeOnlyCharging = p[Keys.TRANSCRIBE_ONLY_CHARGING],
            transcriptionEngine = p[Keys.TRANSCRIPTION_ENGINE],
        )
    }

    suspend fun setUnits(units: Units) = context.settingsStore.edit { it[Keys.UNITS] = units.name }

    suspend fun setRecordingMode(mode: RecordingMode) =
        context.settingsStore.edit { it[Keys.RECORDING_MODE] = mode.name }

    suspend fun setTranscriptionLanguage(tag: String) =
        context.settingsStore.edit { it[Keys.TRANSCRIPTION_LANGUAGE] = tag }

    suspend fun setRestAfterMin(minutes: Int) = context.settingsStore.edit { it[Keys.REST_AFTER_MIN] = minutes }

    suspend fun setTranscriptionModel(id: String) = context.settingsStore.edit { it[Keys.TRANSCRIPTION_MODEL] = id }

    suspend fun setTranscribeOnlyCharging(value: Boolean) = context.settingsStore.edit { it[Keys.TRANSCRIBE_ONLY_CHARGING] = value }

    suspend fun setTranscriptionEngine(id: String) = context.settingsStore.edit { it[Keys.TRANSCRIPTION_ENGINE] = id }

    private companion object {
        const val TAG = "Bitacora"
    }
}
