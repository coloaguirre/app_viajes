package com.bitacoraviaje.core.videoplan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Deterministic planner (DISENO §13.3, skill trip-video §2). Same input and parameters →
 * same plan; no randomness. Budget in milliseconds:
 *  1. fixed scenes: title 5 s, overview map 7 s (only when the plan spans several segments),
 *     credits 6 s, and per segment chapter 2.5 s + map 5 s + stats 4 s;
 *  2. the rest split across segments by richness `1·photos + 2·highlights + 1.5·voice + 2·places`;
 *  3. within a segment: approved place cards (5 s) first, then photos chosen by
 *     favourite > near a highlight > with a voice note > sharpness, one per time window
 *     (temporal dispersion), no duplicates (pHash), video clips capped at 6 s;
 *  4. voice notes become subtitles of the nearest photo (±3 min) or a short overlay;
 *  5. fit: drop the lowest-scored photos when over budget, stretch photos (≤ 5 s) when under.
 */
object Planner {
    const val TITLE_MS = 5_000L
    const val OVERVIEW_MAP_MS = 7_000L
    const val CREDITS_MS = 6_000L
    const val CHAPTER_MS = 2_500L
    const val SEGMENT_MAP_MS = 5_000L
    const val STATS_MS = 4_000L
    const val PHOTO_MS = 3_500L
    const val PHOTO_SHORT_MS = 2_500L
    const val PHOTO_MAX_MS = 5_000L
    const val PLACE_CARD_MS = 5_000L
    const val PLACE_CARD_MAX_MS = 8_000L
    const val MAP_MAX_MS = 12_000L
    const val CLIP_MAX_MS = 6_000L
    const val CLIP_MIN_MS = 2_000L
    const val VOICE_OVERLAY_MAX_MS = 8_000L
    const val HIGHLIGHT_WINDOW_MS = 2 * 60_000L
    const val VOICE_WINDOW_MS = 3 * 60_000L
    const val MANY_PHOTOS = 12
    const val TOLERANCE = 0.05
    const val PHASH_DUPLICATE_BITS = 6

    private data class Candidate(val media: PlanMedia, val score: Double, val voice: PlanVoice?)

    fun plan(input: PlanInput, params: PlanParams): VideoPlan {
        require(params.targetDurationS > 0) { "duration must be positive" }
        val segments = input.segments.filter { params.segmentIds == null || it.id in params.segmentIds }.sortedBy { it.startedUtcMs }
        val budget = params.targetDurationS * 1_000L
        val multi = segments.size > 1
        val output = VideoPlan.Output(params.aspect.width, params.aspect.height)
        val music = params.musicFile?.let { VideoPlan.Music(it, volume = params.musicVolume) }

        // 1. Fixed scenes.
        var fixed = TITLE_MS + CREDITS_MS
        if (params.includeMap && multi) fixed += OVERVIEW_MAP_MS
        for (s in segments) fixed += CHAPTER_MS + STATS_MS + (if (params.includeMap && s.hasTrack) SEGMENT_MAP_MS else 0L)
        val flexible = max(0L, budget - fixed)

        // 2. Richness split.
        val eligible = segments.associate { s -> s.id to eligibleMedia(s, params) }
        val richness = segments.associate { s ->
            val photos = eligible.getValue(s.id).size
            s.id to (1.0 * photos + 2.0 * s.highlightsUtcMs.size + 1.5 * s.voiceNotes.size + 2.0 * (if (params.includePlaces) s.places.size else 0))
        }
        val totalRichness = richness.values.sum()
        val share = segments.associate { s ->
            s.id to if (totalRichness <= 0.0) flexible / max(1, segments.size) else (flexible * richness.getValue(s.id) / totalRichness).roundToLong()
        }

        // 3. Per-segment content.
        val scenes = ArrayList<VideoPlan.Scene>()
        val cover = input.coverMediaId ?: segments.flatMap { eligible.getValue(it.id) }.firstOrNull { it.isFavorite }?.id ?: segments.flatMap { eligible.getValue(it.id) }.firstOrNull()?.id
        scenes += VideoPlan.Title(TITLE_MS, input.title, subtitleOf(segments), cover)
        if (params.includeMap && multi) scenes += VideoPlan.MapRoute(OVERVIEW_MAP_MS, segments.filter { it.hasTrack }.map { it.id }, "overview")
        val photoScenes = ArrayList<Pair<VideoPlan.Photo, Double>>() // for fitting
        var kenIndex = 0
        for (s in segments) {
            scenes += VideoPlan.Chapter(CHAPTER_MS, s.title, s.date, s.id)
            if (params.includeMap && s.hasTrack) scenes += VideoPlan.MapRoute(SEGMENT_MAP_MS, listOf(s.id), if (multi) "follow" else "overview")
            var remaining = share.getValue(s.id)
            if (params.includePlaces) {
                for (p in s.places) {
                    if (remaining < PLACE_CARD_MS) break
                    scenes += VideoPlan.PlaceCard(PLACE_CARD_MS, p.id, p.name, p.text, p.sourceName + (p.license?.let { " ($it)" } ?: ""), p.url)
                    remaining -= PLACE_CARD_MS
                }
            }
            val candidates = score(s, eligible.getValue(s.id))
            val photoMs = if (candidates.size > MANY_PHOTOS) PHOTO_SHORT_MS else PHOTO_MS
            val slots = (remaining / photoMs).toInt()
            val chosen = pick(candidates, slots, s)
            val usedVoices = HashSet<String>()
            for (c in chosen) {
                if (c.media.isVideo) {
                    val d = c.media.durationMs ?: CLIP_MAX_MS
                    val len = min(CLIP_MAX_MS, max(CLIP_MIN_MS, d))
                    val start = max(0L, (d - len) / 2)
                    scenes += VideoPlan.VideoClip(c.media.id, start, start + len, keep_audio = true, caption = c.media.caption) // clip audio always kept; VoiceMode governs voice notes
                } else {
                    // The voice reference is set in every mode: the engine decides subtitle vs audio (review B2 of PR #11).
                    val voiceId = c.voice?.takeIf { usedVoices.add(it.id) }?.id
                    val photo = VideoPlan.Photo(photoMs, c.media.id, kenBurns(kenIndex++), c.media.caption, voiceId)
                    scenes += photo
                    photoScenes += photo to c.score
                }
            }
            // 4. Voice notes without a photo nearby: a short overlay on the map, budget permitting (in audio mode the
            //    overlay plays the note; a note without transcript still enters when the audio is wanted).
            run {
                val spent = chosen.size * photoMs
                var left = remaining - spent
                for (v in s.voiceNotes.sortedBy { it.startedUtcMs }) {
                    if (v.id in usedVoices) continue
                    if (v.text.isNullOrBlank() && params.voice == VoiceMode.SUBTITLES) continue
                    val d = min(VOICE_OVERLAY_MAX_MS, max(3_000L, v.durationMs))
                    if (left < d) break
                    scenes += VideoPlan.VoiceOverlay(d, v.id, s.id, v.text)
                    left -= d
                }
            }
            scenes += VideoPlan.Stats(STATS_MS, s.id, s.distanceM, s.movingTimeMs, s.elevationGainM, s.maxAltM, photos = chosen.count { !it.media.isVideo })
        }
        scenes += VideoPlan.Credits(CREDITS_MS, credits(input, segments, params))

        // 5. Fit to the budget (±5 %).
        val fitted = fit(scenes, photoScenes, budget)
        return VideoPlan(output = output, music = music, scenes = fitted)
    }

    private fun eligibleMedia(s: PlanSegment, params: PlanParams): List<PlanMedia> {
        val base = s.media.filter { !it.isExcluded && (params.includeVideos || !it.isVideo) }.sortedBy { it.takenUtcMs }
        // Duplicates by perceptual hash: the first of a near-identical pair stays (burst shots).
        val kept = ArrayList<PlanMedia>()
        for (m in base) {
            val dup = m.phash != null && kept.any { k -> k.phash != null && java.lang.Long.bitCount(k.phash xor m.phash) <= PHASH_DUPLICATE_BITS }
            if (!dup) kept += m
        }
        return kept
    }

    private fun score(s: PlanSegment, media: List<PlanMedia>): List<Candidate> = media.map { m ->
        val nearHighlight = s.highlightsUtcMs.any { abs(it - m.takenUtcMs) <= HIGHLIGHT_WINDOW_MS }
        val voice = s.voiceNotes.filter { !it.text.isNullOrBlank() && abs(it.startedUtcMs - m.takenUtcMs) <= VOICE_WINDOW_MS }.minByOrNull { abs(it.startedUtcMs - m.takenUtcMs) }
        val score = (if (m.isFavorite) 3.0 else 0.0) + (if (nearHighlight) 2.0 else 0.0) + (if (voice != null) 1.5 else 0.0) + (m.sharpness ?: 0.5)
        Candidate(m, score, voice)
    }

    /** One best candidate per time window first (dispersion), then the best remaining by score; chronological output. */
    private fun pick(candidates: List<Candidate>, slots: Int, s: PlanSegment): List<Candidate> {
        if (slots <= 0 || candidates.isEmpty()) return emptyList()
        val n = min(slots, candidates.size)
        val start = s.startedUtcMs
        val end = max(s.endedUtcMs ?: candidates.last().media.takenUtcMs, start + 1)
        val windowMs = max(1L, (end - start) / n)
        val chosen = LinkedHashSet<Candidate>()
        for (w in 0 until n) {
            val from = start + w * windowMs
            val to = if (w == n - 1) Long.MAX_VALUE else from + windowMs
            // Ties (the norm while sharpness is unknown) go to the later photo; planner.py mirrors this (review I1).
            candidates.filter { it.media.takenUtcMs >= from && it.media.takenUtcMs < to && it !in chosen }.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.media.takenUtcMs })?.let { chosen += it }
        }
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.media.takenUtcMs })) {
            if (chosen.size >= n) break
            chosen += c
        }
        return chosen.sortedBy { it.media.takenUtcMs }
    }

    /** Alternating moves: zoom in, zoom out, pan right, pan left; zoom between 1.0 and 1.15. */
    fun kenBurns(i: Int): VideoPlan.KenBurns = when (i % 4) {
        0 -> VideoPlan.KenBurns(listOf(0.0, 0.0, 1.0), listOf(0.05, 0.05, 1.15))
        1 -> VideoPlan.KenBurns(listOf(0.05, 0.05, 1.15), listOf(0.0, 0.0, 1.0))
        2 -> VideoPlan.KenBurns(listOf(0.0, 0.02, 1.1), listOf(0.1, 0.02, 1.1))
        else -> VideoPlan.KenBurns(listOf(0.1, 0.02, 1.1), listOf(0.0, 0.02, 1.1))
    }

    private fun fit(scenes: List<VideoPlan.Scene>, photos: List<Pair<VideoPlan.Photo, Double>>, budget: Long): List<VideoPlan.Scene> {
        val out = ArrayList(scenes)
        fun total() = out.sumOf { it.durationMs }
        // Over budget: drop the lowest-scored photos (never the fixed scenes).
        val byScore = photos.sortedWith(compareBy<Pair<VideoPlan.Photo, Double>> { it.second }.thenByDescending { it.first.media })
        var i = 0
        while (total() > budget * (1 + TOLERANCE) && i < byScore.size) {
            out.remove(byScore[i].first)
            i++
        }
        // Under budget: stretch the photos evenly (≤ PHOTO_MAX_MS), then the maps and cards; a trip with little
        // content legitimately yields a shorter video rather than padding it with repeats.
        fun stretch(select: (VideoPlan.Scene) -> Long?, apply: (VideoPlan.Scene, Long) -> VideoPlan.Scene) {
            // Several passes: each one adds the smallest remaining room, so scenes with different starting lengths all reach their cap.
            repeat(8) {
                val room = out.mapIndexedNotNull { idx, sc -> select(sc)?.let { idx to it } }.filter { it.second > 0 }
                val deficit = budget - total()
                if (deficit <= 0 || room.isEmpty()) return
                val extra = min(room.minOf { it.second }, deficit / room.size)
                if (extra <= 0) return
                for ((idx, _) in room) out[idx] = apply(out[idx], extra)
            }
        }
        stretch({ (it as? VideoPlan.Photo)?.let { p -> PHOTO_MAX_MS - p.duration_ms } }) { sc, e -> (sc as VideoPlan.Photo).copy(duration_ms = sc.duration_ms + e) }
        stretch({ (it as? VideoPlan.MapRoute)?.let { m -> MAP_MAX_MS - m.duration_ms } }) { sc, e -> (sc as VideoPlan.MapRoute).copy(duration_ms = sc.duration_ms + e) }
        stretch({ (it as? VideoPlan.PlaceCard)?.let { c -> PLACE_CARD_MAX_MS - c.duration_ms } }) { sc, e -> (sc as VideoPlan.PlaceCard).copy(duration_ms = sc.duration_ms + e) }
        return out
    }

    private fun subtitleOf(segments: List<PlanSegment>): String? {
        val first = segments.firstOrNull()?.date ?: return null
        val last = segments.last().date
        return if (first == last) first else "$first — $last"
    }

    private fun credits(input: PlanInput, segments: List<PlanSegment>, params: PlanParams): List<String> = buildList {
        add("Datos del mapa © colaboradores de OpenStreetMap (ODbL)")
        if (params.includePlaces) {
            val wiki = segments.flatMap { it.places }.filter { it.sourceName == "Wikipedia" }.distinctBy { it.url ?: it.name }
            wiki.forEach { add("Texto: Wikipedia — ${it.name} (${it.license ?: "CC BY-SA 4.0"})${it.url?.let { u -> " $u" } ?: ""}") }
            if (segments.flatMap { it.places }.any { it.sourceName == "Wikidata" }) add("Datos: Wikidata (CC0)")
        }
        addAll(input.extraCredits)
        add("Hecho con Bitácora de Viaje")
    }
}
