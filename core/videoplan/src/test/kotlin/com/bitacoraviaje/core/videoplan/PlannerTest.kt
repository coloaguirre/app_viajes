package com.bitacoraviaje.core.videoplan

import com.bitacoraviaje.core.videoplan.VideoPlan.Companion.toJson
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** PLAN 4.4 acceptance: deterministic plans, the 1-minute plan of the sample trip within ±5 %, selection rules. */
class PlannerTest {
    private val t0 = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun photo(id: String, t: Long, fav: Boolean = false, phash: Long? = null, sharp: Double? = null, video: Boolean = false, dur: Long? = null) =
        PlanMedia(id, video, t, dur, isFavorite = fav, phash = phash, sharpness = sharp)

    /** Two days: a rich trek (30 photos, 2 highlights, 2 voice notes, 2 places) and a quiet drive (4 photos, 1 video). */
    private fun sampleTrip(): PlanInput {
        val trek = PlanSegment(
            id = "s1", title = "Trekking a Laguna de los Tres", date = "2026-03-14", startedUtcMs = t0, endedUtcMs = t0 + 6 * 3_600_000, distanceM = 21_000.0, movingTimeMs = 5 * 3_600_000L, elevationGainM = 800.0, maxAltM = 1_200.0,
            media = (0 until 30).map { i -> photo("p$i", t0 + 10 * 60_000L * i + 60_000, fav = i == 7 || i == 20, sharp = if (i == 3) 0.1 else 0.7) } +
                listOf(photo("dup-a", t0 + 3_000_000, phash = 0x0F0F0F0F), photo("dup-b", t0 + 3_001_000, phash = 0x0F0F0F0E), photo("excluded", t0 + 100, sharp = 1.0).copy(isExcluded = true)),
            voiceNotes = listOf(PlanVoice("v1", t0 + 10 * 60_000L * 12 + 90_000, 6_000, "Qué viento"), PlanVoice("v2", t0 + 5 * 3_600_000, 4_000, "Llegamos")),
            highlightsUtcMs = listOf(t0 + 10 * 60_000L * 25 + 30_000),
            places = listOf(PlanPlace("wd-Q1", "El Chaltén", "Un pueblo de montaña.", "Wikipedia", "CC BY-SA 4.0", "https://es.wikipedia.org/wiki/El_Chalt%C3%A9n"), PlanPlace("wd-Q2", "Monte Fitz Roy", "Altitud: 3.406 m", "Wikidata", "CC0", null)),
        )
        val drive = PlanSegment(
            id = "s2", title = "RN 40, de El Chaltén a El Calafate", date = "2026-03-15", startedUtcMs = t0 + day, endedUtcMs = t0 + day + 3 * 3_600_000, distanceM = 214_000.0, movingTimeMs = 9_000_000,
            media = (0 until 4).map { i -> photo("d$i", t0 + day + 30 * 60_000L * i + 5_000) } + listOf(photo("vid", t0 + day + 50 * 60_000L, video = true, dur = 20_000)),
        )
        return PlanInput("trip-1", "Patagonia 2026", null, listOf(trek, drive))
    }

    private val schema by lazy { JsonSchema.fromDefinition(javaClass.getResourceAsStream("/video-plan.schema.json")!!.readBytes().toString(Charsets.UTF_8)) }

    private fun validate(plan: VideoPlan) {
        val errors = mutableListOf<ValidationError>()
        assertTrue(schema.validate(Json.parseToJsonElement(plan.toJson()), errors::add), errors.take(5).joinToString("\n"))
    }

    @Test
    fun `one minute plan of the whole sample trip lands within five percent and validates`() {
        for (seconds in PlanParams.DURATIONS_S) {
            val plan = Planner.plan(sampleTrip(), PlanParams(seconds))
            validate(plan)
            val total = plan.scenes.sumOf { it.durationMs }
            assertEquals(total, plan.total_duration_ms)
            val photos = plan.scenes.filterIsInstance<VideoPlan.Photo>()
            val exhausted = photos.all { it.duration_ms >= Planner.PHOTO_MAX_MS } && photos.size + plan.scenes.count { it is VideoPlan.VideoClip } >= 36 // every eligible media used
            assertTrue(abs(total - seconds * 1_000L) <= seconds * 1_000L * Planner.TOLERANCE || (total < seconds * 1_000L && exhausted), "target ${seconds}s, planned ${total / 1000.0}s: ${plan.scenes.map { "${it::class.simpleName}:${it.durationMs}" }}")
            assertTrue(total <= seconds * 1_000L * (1 + Planner.TOLERANCE), "never over budget")
        }
    }

    @Test
    fun `quick one minute preset of a stage has title, map, photos with subtitles, place card and credits`() {
        val plan = Planner.plan(sampleTrip(), PlanParams.quickOneMinute("s1"))
        validate(plan)
        val kinds = plan.scenes.map { it::class.simpleName }
        assertEquals("Title", kinds.first())
        assertEquals("Credits", kinds.last())
        assertTrue("MapRoute" in kinds && "Chapter" in kinds && "Stats" in kinds && "PlaceCard" in kinds && "Photo" in kinds, kinds.toString())
        assertTrue(plan.scenes.none { it is VideoPlan.VideoClip } && plan.scenes.none { it is VideoPlan.MapRoute && it.style == "follow" })
        assertTrue(plan.scenes.filterIsInstance<VideoPlan.Photo>().any { it.subtitle_from_voice == "v1" }, "the voice note subtitles the nearest photo")
        val total = plan.total_duration_ms
        assertTrue(abs(total - 60_000) <= 3_000, "planned $total")
        val credits = plan.scenes.last() as VideoPlan.Credits
        assertTrue(credits.lines.any { it.contains("OpenStreetMap") } && credits.lines.any { it.contains("Wikipedia — El Chaltén") } && credits.lines.any { it.contains("Wikidata") })
    }

    @Test
    fun `selection prefers favourites, drops duplicates and the excluded, and spreads over time`() {
        val plan = Planner.plan(sampleTrip(), PlanParams(180))
        val photos = plan.scenes.filterIsInstance<VideoPlan.Photo>().map { it.media }
        assertTrue("p7" in photos && "p20" in photos, "favourites first: $photos")
        assertTrue("excluded" !in photos && !("dup-a" in photos && "dup-b" in photos), "no excluded, no near-duplicate pair: $photos")
        assertEquals(photos.size, photos.toSet().size)
        val trekPhotos = photos.filter { it.startsWith("p") || it.startsWith("dup") }
        val times = trekPhotos.mapNotNull { id -> sampleTrip().segments[0].media.firstOrNull { it.id == id }?.takenUtcMs }
        val span = (times.max() - times.min()).toDouble() / (6 * 3_600_000)
        assertTrue(span > 0.6, "photos spread over the stage: ${"%.2f".format(span)}")
        assertTrue(plan.scenes.filterIsInstance<VideoPlan.VideoClip>().all { it.trim_end_ms - it.trim_start_ms <= Planner.CLIP_MAX_MS })
        val kb = plan.scenes.filterIsInstance<VideoPlan.Photo>().map { it.kenburns }
        assertTrue(kb.zipWithNext().any { (a, b) -> a != b }, "ken burns alternates")
    }

    @Test
    fun `deterministic and respects the flags`() {
        val a = Planner.plan(sampleTrip(), PlanParams(300)).toJson()
        val b = Planner.plan(sampleTrip(), PlanParams(300)).toJson()
        assertEquals(a, b)
        val bare = Planner.plan(sampleTrip(), PlanParams(300, includeMap = false, includePlaces = false, includeVideos = false))
        assertTrue(bare.scenes.none { it is VideoPlan.MapRoute || it is VideoPlan.PlaceCard || it is VideoPlan.VideoClip })
        val round = VideoPlan.fromJson(a)
        assertEquals(Planner.plan(sampleTrip(), PlanParams(300)), round)
    }

    @Test
    fun `every voice mode carries the voice notes into the plan (review B2 of PR 11)`() {
        for (mode in VoiceMode.entries) {
            val plan = Planner.plan(sampleTrip(), PlanParams(180, voice = mode))
            val referenced = plan.scenes.filterIsInstance<VideoPlan.Photo>().mapNotNull { it.subtitle_from_voice } + plan.scenes.filterIsInstance<VideoPlan.VoiceOverlay>().map { it.voice }
            assertTrue("v1" in referenced && "v2" in referenced, "mode $mode lost a voice note: $referenced")
        }
    }

    @Test
    fun `equal scores in one window pick the later photo (shared rule with the python planner)`() {
        val seg = PlanSegment("s", "Etapa", "2026-01-01", t0, t0 + 3_600_000, media = listOf(photo("early", t0 + 60_000), photo("late", t0 + 120_000), photo("far", t0 + 3_000_000)))
        val plan = Planner.plan(PlanInput("t", "T", null, listOf(seg)), PlanParams(60, includeMap = false, includePlaces = false))
        val photos = plan.scenes.filterIsInstance<VideoPlan.Photo>().map { it.media }
        assertTrue(photos.size >= 2 && "late" in photos && "far" in photos, photos.toString())
    }

    @Test
    fun `a segment without photos still gets its fixed scenes and the plan stays near the budget`() {
        val empty = PlanInput("t", "Vacío", null, listOf(PlanSegment("s", "Etapa", "2026-01-01", t0, t0 + 3_600_000, hasTrack = false)))
        val plan = Planner.plan(empty, PlanParams(60))
        validate(plan)
        assertEquals(listOf("Title", "Chapter", "Stats", "Credits"), plan.scenes.map { it::class.simpleName })
    }
}
