package com.bitacoraviaje.core.videoplan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The engine-independent plan (DISENO §13.3, skill trip-video §1). Both renderers (desktop
 * Python/FFmpeg and Media3 on the phone) consume exactly this JSON; `docs/video-plan.schema.json`
 * is its schema. References (`media`, `segments`, `place`, `voice`) are ids of `trip.json`.
 */
@Serializable
data class VideoPlan(
    val schema_version: Int = SCHEMA_VERSION,
    val output: Output,
    val music: Music? = null,
    val scenes: List<Scene>,
    /** Total planned duration, the sum of the scenes; redundant but handy for the renderers and the UI. */
    val total_duration_ms: Long = scenes.sumOf { it.durationMs },
) {
    @Serializable
    data class Output(val width: Int, val height: Int, val fps: Int = 30, val format: String = "mp4-h264-aac")

    @Serializable
    data class Music(val file: String, val volume: Double = 0.4, val duck_under_voice: Boolean = true)

    @Serializable
    data class KenBurns(val from: List<Double>, val to: List<Double>)

    @Serializable
    sealed interface Scene {
        val durationMs: Long
    }

    @Serializable
    @SerialName("title")
    data class Title(val duration_ms: Long, val title: String, val subtitle: String? = null, val background_media: String? = null) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("map_route")
    data class MapRoute(val duration_ms: Long, val segments: List<String>, val style: String) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("chapter")
    data class Chapter(val duration_ms: Long, val title: String, val date: String? = null, val segment: String) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("photo")
    data class Photo(val duration_ms: Long, val media: String, val kenburns: KenBurns, val caption: String? = null, val subtitle_from_voice: String? = null) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("video_clip")
    data class VideoClip(val media: String, val trim_start_ms: Long, val trim_end_ms: Long, val keep_audio: Boolean = true, val caption: String? = null) : Scene {
        override val durationMs get() = trim_end_ms - trim_start_ms
    }

    @Serializable
    @SerialName("voice_overlay")
    data class VoiceOverlay(val duration_ms: Long, val voice: String, val segment: String, val text: String? = null) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("place_card")
    data class PlaceCard(val duration_ms: Long, val place: String, val title: String, val text: String, val source: String, val url: String? = null) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("stats")
    data class Stats(val duration_ms: Long, val segment: String? = null, val distance_m: Double? = null, val moving_time_ms: Long? = null, val elevation_gain_m: Double? = null, val max_alt_m: Double? = null, val photos: Int? = null, val days: Int? = null, val places: Int? = null) : Scene {
        override val durationMs get() = duration_ms
    }

    @Serializable
    @SerialName("credits")
    data class Credits(val duration_ms: Long, val lines: List<String>) : Scene {
        override val durationMs get() = duration_ms
    }

    companion object {
        const val SCHEMA_VERSION = 1

        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        fun VideoPlan.toJson(): String = json.encodeToString(serializer(), this)

        fun fromJson(text: String): VideoPlan = json.decodeFromString(serializer(), text)
    }
}
