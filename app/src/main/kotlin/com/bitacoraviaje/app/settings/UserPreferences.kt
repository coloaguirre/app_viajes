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

/** OSM service instances (PLAN 3.6): changeable in Settings when the public ones are busy or rate-limited. */
const val DEFAULT_NOMINATIM_URL: String = "https://nominatim.openstreetmap.org"
const val DEFAULT_OVERPASS_URL: String = "https://overpass-api.de/api/interpreter"
/** PLAN 4.3: model used to rewrite place texts when the user configured a Claude API key. */
const val DEFAULT_LLM_MODEL: String = "claude-sonnet-5"
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
    val nominatimUrl: String = DEFAULT_NOMINATIM_URL,
    val overpassUrl: String = DEFAULT_OVERPASS_URL,
    /** PLAN 4.1: look up places and texts on the internet when a segment ends. */
    val enrichPlaces: Boolean = true,
    /** DISENO §15: send coordinates rounded to ~1 km to Wikipedia/Nominatim for places. */
    val roundEnrichmentCoords: Boolean = false,
    val llmModel: String = DEFAULT_LLM_MODEL,
    /** DISENO §15.3 / PLAN 5.5: circles the exports must not reveal. */
    val privacyZones: List<com.bitacoraviaje.core.model.PrivacyZone> = emptyList(),
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
            nominatimUrl: String? = null,
            overpassUrl: String? = null,
            enrichPlaces: Boolean? = null,
            roundEnrichmentCoords: Boolean? = null,
            llmModel: String? = null,
            privacyZonesJson: String? = null,
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
            nominatimUrl = httpsOr(nominatimUrl, DEFAULT_NOMINATIM_URL) { onInvalid("nominatim_url", it) },
            overpassUrl = httpsOr(overpassUrl, DEFAULT_OVERPASS_URL) { onInvalid("overpass_url", it) },
            enrichPlaces = enrichPlaces ?: true,
            roundEnrichmentCoords = roundEnrichmentCoords ?: false,
            llmModel = llmModel?.trim()?.takeIf { it.matches(Regex("^[A-Za-z0-9._-]{3,64}$")) } ?: run { if (llmModel != null) onInvalid("llm_model", llmModel); DEFAULT_LLM_MODEL },
            privacyZones = privacyZonesJson?.let { raw -> PrivacyZones.parse(raw) ?: run { onInvalid("privacy_zones", raw.take(80)); emptyList() } } ?: emptyList(),
        )

        /** https only, except a loopback host (a local instance on the PC through the emulator's 10.0.2.2 or adb reverse). */
        private fun httpsOr(raw: String?, default: String, onInvalid: (String) -> Unit): String {
            val v = raw?.trim()?.trimEnd('/') ?: return default
            val host = v.substringAfter("://", "").substringBefore('/').substringBefore(':')
            val ok = (v.startsWith("https://") && v.length > 10) || (v.startsWith("http://") && host in LOOPBACK_HOSTS)
            return if (ok) v else { onInvalid(raw); default }
        }

        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

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
        val NOMINATIM_URL = stringPreferencesKey("nominatim_url")
        val OVERPASS_URL = stringPreferencesKey("overpass_url")
        val ENRICH_PLACES = androidx.datastore.preferences.core.booleanPreferencesKey("enrich_places")
        val ROUND_ENRICHMENT_COORDS = androidx.datastore.preferences.core.booleanPreferencesKey("round_enrichment_coords")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val PRIVACY_ZONES = stringPreferencesKey("privacy_zones")
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
            nominatimUrl = p[Keys.NOMINATIM_URL],
            overpassUrl = p[Keys.OVERPASS_URL],
            enrichPlaces = p[Keys.ENRICH_PLACES],
            roundEnrichmentCoords = p[Keys.ROUND_ENRICHMENT_COORDS],
            llmModel = p[Keys.LLM_MODEL],
            privacyZonesJson = p[Keys.PRIVACY_ZONES],
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

    suspend fun setNominatimUrl(url: String) = context.settingsStore.edit { it[Keys.NOMINATIM_URL] = url }

    suspend fun setOverpassUrl(url: String) = context.settingsStore.edit { it[Keys.OVERPASS_URL] = url }

    suspend fun setEnrichPlaces(value: Boolean) = context.settingsStore.edit { it[Keys.ENRICH_PLACES] = value }

    suspend fun setRoundEnrichmentCoords(value: Boolean) = context.settingsStore.edit { it[Keys.ROUND_ENRICHMENT_COORDS] = value }

    suspend fun setLlmModel(model: String) = context.settingsStore.edit { it[Keys.LLM_MODEL] = model.trim() }

    suspend fun setPrivacyZones(zones: List<com.bitacoraviaje.core.model.PrivacyZone>) = context.settingsStore.edit { it[Keys.PRIVACY_ZONES] = PrivacyZones.encode(zones) }

    /** Read-modify-write inside the DataStore transaction, so two quick taps cannot lose a zone (review M6). */
    suspend fun updatePrivacyZones(transform: (List<com.bitacoraviaje.core.model.PrivacyZone>) -> List<com.bitacoraviaje.core.model.PrivacyZone>) = context.settingsStore.edit { p ->
        val current = p[Keys.PRIVACY_ZONES]?.let { PrivacyZones.parse(it) } ?: emptyList()
        p[Keys.PRIVACY_ZONES] = PrivacyZones.encode(transform(current))
    }

    private companion object {
        const val TAG = "Bitacora"
    }
}

/** JSON codec of the zones list in DataStore (coordinates stay on the phone; only the export is affected). */
object PrivacyZones {
    @kotlinx.serialization.Serializable
    private data class Dto(val id: String, val name: String, val lat: Double, val lon: Double, val radius_m: Double)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val RADII_M: List<Int> = listOf(100, 250, 500, 1000)

    fun encode(zones: List<com.bitacoraviaje.core.model.PrivacyZone>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Dto.serializer()), zones.map { Dto(it.id, it.name, it.lat, it.lon, it.radiusM) })

    /** Null when the stored text is not a valid list (corrupt or newer): never a silent partial list. */
    fun parse(raw: String): List<com.bitacoraviaje.core.model.PrivacyZone>? = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Dto.serializer()), raw)
            .filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.radius_m in 10.0..50_000.0 }
            .map { com.bitacoraviaje.core.model.PrivacyZone(it.id, it.name, it.lat, it.lon, it.radius_m) }
    }.getOrNull()
}

