package com.bitacoraviaje.core.videoplan

enum class Aspect(val width: Int, val height: Int) {
    LANDSCAPE_16_9(1920, 1080),
    PORTRAIT_9_16(1080, 1920),
}

enum class VoiceMode { SUBTITLES, AUDIO, BOTH }

/** DISENO §13.2 user parameters. Everything the planner decides derives from these and the input. */
data class PlanParams(
    /** 60, 180, 300 or 600 (DISENO §13.2); any positive value works. */
    val targetDurationS: Int,
    val aspect: Aspect = Aspect.LANDSCAPE_16_9,
    val voice: VoiceMode = VoiceMode.SUBTITLES,
    val includePlaces: Boolean = true,
    val includeMap: Boolean = true,
    val includeVideos: Boolean = true,
    val musicFile: String? = null,
    /** Restrict to these segments (an "etapa" video or a date range); null = whole trip. */
    val segmentIds: Set<String>? = null,
) {
    companion object {
        val DURATIONS_S = listOf(60, 180, 300, 600)

        /** Adenda 1: the on-demand 1-minute mini video of a stage: title, photos with Ken Burns, static map, subtitles, credits. */
        fun quickOneMinute(segmentId: String, aspect: Aspect = Aspect.LANDSCAPE_16_9, musicFile: String? = null) =
            PlanParams(targetDurationS = 60, aspect = aspect, voice = VoiceMode.SUBTITLES, includePlaces = true, includeMap = true, includeVideos = false, musicFile = musicFile, segmentIds = setOf(segmentId))
    }
}
