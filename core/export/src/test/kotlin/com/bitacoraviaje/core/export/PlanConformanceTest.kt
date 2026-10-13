package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.videoplan.Aspect
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.Planner
import com.bitacoraviaje.core.videoplan.VideoPlan.Companion.toJson
import com.bitacoraviaje.core.videoplan.VoiceMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformance cases shared with the Python planner (skill trip-video §2): `tools/plan-fixtures/<case>/`
 * holds `trip.json`, `params.json` and the expected `plan.json`. Regenerate on purpose with
 * `./gradlew :core:export:test --tests '*PlanConformanceTest*' -DupdatePlanFixtures=true`.
 */
class PlanConformanceTest {
    private val cases = listOf(
        "sample-60s" to PlanParams(60),
        "sample-180s-portrait" to PlanParams(180, aspect = Aspect.PORTRAIT_9_16, voice = VoiceMode.BOTH),
        "sample-quick-seg1" to PlanParams.quickOneMinute("seg-1"),
    )

    private fun paramsJson(p: PlanParams): String = """{"target_duration_s": ${p.targetDurationS}, "aspect": "${if (p.aspect == Aspect.LANDSCAPE_16_9) "16:9" else "9:16"}", "voice": "${p.voice.name.lowercase()}", "include_places": ${p.includePlaces}, "include_map": ${p.includeMap}, "include_videos": ${p.includeVideos}, "music_file": ${p.musicFile?.let { "\"$it\"" } ?: "null"}, "segment_ids": ${p.segmentIds?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"}}"""

    @Test
    fun `every fixture case matches the planner output`() {
        val update = System.getProperty("updatePlanFixtures") == "true"
        val root = File(System.getProperty("planFixturesDir") ?: "../../tools/plan-fixtures")
        val trip = Fixture.trip()
        val tripJson = Fixture.render(TripJsonWriter(), trip)
        for ((name, params) in cases) {
            val plan = Planner.plan(PlanInputs.of(TripJsonReader.read(tripJson)), params).toJson()
            if (update) {
                val dir = File(root, name).apply { mkdirs() }
                File(dir, "trip.json").writeText(tripJson)
                File(dir, "params.json").writeText(paramsJson(params) + "\n")
                File(dir, "plan.json").writeText(plan + "\n")
            }
            val expected = if (update) File(root, "$name/plan.json").readText() else javaClass.getResourceAsStream("/$name/plan.json")?.readBytes()?.toString(Charsets.UTF_8)
            assertTrue(expected != null, "missing fixture $name: run with -DupdatePlanFixtures=true")
            assertEquals(expected.trim(), plan.trim(), "case $name drifted from tools/plan-fixtures")
        }
    }

    @Test
    fun `the export fixture gives a one minute plan within tolerance with the approved place only`() {
        val plan = Planner.plan(PlanInputs.of(Fixture.trip()), PlanParams(60))
        assertTrue(kotlin.math.abs(plan.total_duration_ms - 60_000) <= 3_000, "total ${plan.total_duration_ms}")
        val cards = plan.scenes.filterIsInstance<com.bitacoraviaje.core.videoplan.VideoPlan.PlaceCard>()
        assertEquals(listOf("Wikidata (CC0)"), cards.map { it.source }, "only the approved Wikidata text of the fixture enters the video")
    }
}
