package com.bitacoraviaje.app.video

import android.content.Context
import com.bitacoraviaje.core.videoplan.Aspect
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.core.videoplan.VideoPlan.Companion.toJson
import com.bitacoraviaje.core.videoplan.VoiceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The parameters of a trip video as the user set them (DISENO §13.2), persisted next to the plan. */
@Serializable
data class VideoParams(
    val durationS: Int = 180,
    val aspect: String = "16:9",
    val voice: String = "subtitles",
    val includePlaces: Boolean = true,
    val includeMap: Boolean = true,
    val includeVideos: Boolean = true,
    val musicUri: String? = null,
    /** Display name of the music file: what `video_plan.json` carries for the desktop renderer (review I6). */
    val musicName: String? = null,
    val musicVolume: Double = 0.4,
    val segmentIds: List<String>? = null,
) {
    fun toPlanParams(): PlanParams = PlanParams(
        targetDurationS = durationS,
        aspect = if (aspect == "9:16") Aspect.PORTRAIT_9_16 else Aspect.LANDSCAPE_16_9,
        voice = VoiceMode.entries.firstOrNull { it.name.equals(voice, ignoreCase = true) } ?: VoiceMode.SUBTITLES,
        includePlaces = includePlaces, includeMap = includeMap, includeVideos = includeVideos,
        musicFile = if (musicUri != null) (musicName ?: "musica") else null, musicVolume = musicVolume, segmentIds = segmentIds?.toSet(),
    )
}

/** `files/videoplans/<tripId>.json`: the last plan (editable) and its parameters; `video_plan.json` is also what the desktop renderer reads. */
@Singleton
class VideoPlanStore @Inject constructor(@ApplicationContext private val context: Context) {
    /** Parameters survive without a plan (review M2): editing them invalidates the plan, not the choices. */
    @Serializable
    data class Saved(val params: VideoParams, val plan: String? = null)

    data class Loaded(val params: VideoParams, val plan: VideoPlan?)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir get() = File(context.filesDir, "videoplans")

    fun file(tripId: String) = File(dir, "${com.bitacoraviaje.core.export.SafeId.require(tripId, "trip id")}.json")

    fun save(tripId: String, params: VideoParams, plan: VideoPlan?) {
        val target = file(tripId) // validates the id before anything touches the disk (review M3)
        dir.mkdirs()
        val tmp = File(dir, "${target.nameWithoutExtension}.tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), Saved(params, plan?.toJson())))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun load(tripId: String): Loaded? {
        val f = file(tripId)
        if (!f.isFile) return null
        return runCatching { val s = json.decodeFromString(Saved.serializer(), f.readText()); Loaded(s.params, s.plan?.let { VideoPlan.fromJson(it) }) }.getOrNull()
    }

    fun clear(tripId: String) { file(tripId).delete() }
}
