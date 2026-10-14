package com.bitacoraviaje.core.videoplan

/**
 * What the planner needs, already loaded and pure (no database, no Android). The adapter
 * from the export model lives in core/export (`PlanInputs.of`), the one from Room in the app.
 */
data class PlanInput(
    val tripId: String,
    val title: String,
    val coverMediaId: String? = null,
    val segments: List<PlanSegment>,
    /** Credits lines beyond the data sources, e.g. the tile provider. */
    val extraCredits: List<String> = emptyList(),
)

data class PlanSegment(
    val id: String,
    val title: String,
    /** Local date `yyyy-MM-dd` of the start (for the chapter card). */
    val date: String,
    val startedUtcMs: Long,
    val endedUtcMs: Long?,
    val distanceM: Double? = null,
    val movingTimeMs: Long? = null,
    val elevationGainM: Double? = null,
    val maxAltM: Double? = null,
    val hasTrack: Boolean = true,
    val media: List<PlanMedia> = emptyList(),
    val voiceNotes: List<PlanVoice> = emptyList(),
    /** Highlight moments (TextNote HIGHLIGHT) as UTC ms. */
    val highlightsUtcMs: List<Long> = emptyList(),
    /** Places with an approved text (DISENO §11.4 `user_approved`), best ranked first. */
    val places: List<PlanPlace> = emptyList(),
    /** One line of weather for the chapter card, e.g. "Despejado, 8–17 °C" (backlog D3); null without data. */
    val weather: String? = null,
)

data class PlanMedia(
    val id: String,
    val isVideo: Boolean,
    val takenUtcMs: Long,
    val durationMs: Long? = null,
    val isFavorite: Boolean = false,
    val isExcluded: Boolean = false,
    val caption: String? = null,
    /** Perceptual hash for duplicate removal; null until the media pipeline computes it (Phase 5). */
    val phash: Long? = null,
    /** 0..1 sharpness score; null = unknown (treated as average). */
    val sharpness: Double? = null,
)

data class PlanVoice(val id: String, val startedUtcMs: Long, val durationMs: Long, val text: String?)

data class PlanPlace(val id: String, val name: String, val text: String, val sourceName: String, val license: String?, val url: String?)
