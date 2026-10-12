package com.bitacoraviaje.app.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

data class UserSettings(
    val units: Units = Units.METRIC,
    val recordingMode: RecordingMode = RecordingMode.FULL,
    val transcriptionLanguage: String = DEFAULT_TRANSCRIPTION_LANGUAGE,
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
            onInvalid: (key: String, value: String) -> Unit = { _, _ -> },
        ): UserSettings = UserSettings(
            units = parseEnum<Units>(units, Units.METRIC) { onInvalid("units", it) },
            recordingMode = parseEnum<RecordingMode>(recordingMode, RecordingMode.FULL) { onInvalid("recording_mode", it) },
            transcriptionLanguage = transcriptionLanguage?.takeIf { it.isNotBlank() } ?: DEFAULT_TRANSCRIPTION_LANGUAGE,
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
    }

    val settings: Flow<UserSettings> = context.settingsStore.data.map { p ->
        UserSettings.from(
            units = p[Keys.UNITS],
            recordingMode = p[Keys.RECORDING_MODE],
            transcriptionLanguage = p[Keys.TRANSCRIPTION_LANGUAGE],
            onInvalid = { key, value -> Log.w(TAG, "Ignoring invalid stored setting $key=$value, using default") },
        )
    }

    suspend fun setUnits(units: Units) = context.settingsStore.edit { it[Keys.UNITS] = units.name }

    suspend fun setRecordingMode(mode: RecordingMode) =
        context.settingsStore.edit { it[Keys.RECORDING_MODE] = mode.name }

    suspend fun setTranscriptionLanguage(tag: String) =
        context.settingsStore.edit { it[Keys.TRANSCRIPTION_LANGUAGE] = tag }

    private companion object {
        const val TAG = "Bitacora"
    }
}
