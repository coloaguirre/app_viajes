package com.bitacoraviaje.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bitacoraviaje.core.model.RecordingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Units { METRIC, IMPERIAL }

data class UserSettings(
    val units: Units = Units.METRIC,
    val recordingMode: RecordingMode = RecordingMode.FULL,
    val transcriptionLanguage: String = "es-AR",
)

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
        UserSettings(
            units = p[Keys.UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.METRIC,
            recordingMode = p[Keys.RECORDING_MODE]?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
                ?: RecordingMode.FULL,
            transcriptionLanguage = p[Keys.TRANSCRIPTION_LANGUAGE] ?: "es-AR",
        )
    }

    suspend fun setUnits(units: Units) = context.settingsStore.edit { it[Keys.UNITS] = units.name }

    suspend fun setRecordingMode(mode: RecordingMode) =
        context.settingsStore.edit { it[Keys.RECORDING_MODE] = mode.name }

    suspend fun setTranscriptionLanguage(tag: String) =
        context.settingsStore.edit { it[Keys.TRANSCRIPTION_LANGUAGE] = tag }
}
