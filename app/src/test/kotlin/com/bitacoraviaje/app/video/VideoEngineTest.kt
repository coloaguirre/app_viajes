@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bitacoraviaje.app.video

import android.content.Context
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.videoplan.Aspect
import com.bitacoraviaje.core.videoplan.PlanInput
import com.bitacoraviaje.core.videoplan.PlanMedia
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.PlanSegment
import com.bitacoraviaje.core.videoplan.PlanVoice
import com.bitacoraviaje.core.videoplan.Planner
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.core.videoplan.VoiceMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PLAN 5.1: the full engine — animated map clips, voice audio with gaps, looping music — as a Composition (the export needs a device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoEngineTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val t0 = 1_760_000_000_000L

    private fun input(): Pair<PlanInput, List<TrackPoint>> {
        val pts = (0 until 60).map { i -> TrackPoint(segmentId = "s1", timeUtcMs = t0 + i * 60_000L, lat = -49.33 + i * 1e-4, lon = -72.9 + i * 1e-4) }
        val media = (0 until 6).map { i -> PlanMedia("m$i", false, t0 + i * 600_000L, isFavorite = i == 2) } + PlanMedia("clip", true, t0 + 1_500_000, 12_000)
        val s1 = PlanSegment("s1", "Etapa 1", "2026-03-14", t0, t0 + 3_600_000, distanceM = 5_000.0, media = media, voiceNotes = listOf(PlanVoice("v1", t0 + 1_230_000, 4_000, "Qué viento")))
        val s2 = PlanSegment("s2", "Etapa 2", "2026-03-15", t0 + 86_400_000, t0 + 86_400_000 + 1_800_000, media = (0 until 3).map { i -> PlanMedia("n$i", false, t0 + 86_400_000 + i * 300_000L) })
        return PlanInput("t", "Patagonia", null, listOf(s1, s2)) to pts
    }

    @Test
    fun `full plan becomes a composition with animated map clips, voices with gaps and looping music`() {
        val (inp, pts) = input()
        val plan = Planner.plan(inp, PlanParams(180, voice = VoiceMode.BOTH))
        assertTrue(plan.scenes.count { it is VideoPlan.MapRoute } >= 2, "overview + per-segment maps")
        val audioFile = File(ctx.filesDir, "audio/t/v1.m4a").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64)) }
        val music = File(ctx.cacheDir, "music.mp3").apply { writeBytes(ByteArray(64)) }
        val voices = mapOf("v1" to VoiceNote("v1", "s1", t0 + 1_230_000, 4_000, audioPath = "audio/t/v1.m4a", transcript = "Qué viento", transcriptStatus = com.bitacoraviaje.core.model.TranscriptStatus.DONE))
        val work = File(ctx.cacheDir, "engine-test").apply { mkdirs() }
        val options = MiniVideoComposer.Options(voice = VoiceMode.BOTH, animateMap = true, musicUri = android.net.Uri.fromFile(music), musicVolume = 0.3f)
        val built = MiniVideoComposer(ctx, work, 384, 216, options = options).build(plan, inp, emptyMap(), voices, mapOf("s1" to MiniVideoComposer.SegmentMedia(pts, null), "s2" to MiniVideoComposer.SegmentMedia(pts.take(10), null)))
        val maps = plan.scenes.filterIsInstance<VideoPlan.MapRoute>()
        val items = built.composition.sequences.first().editedMediaItems
        // Animated maps: an image item under an OverlayEffect whose RouteOverlay grows the route with the frame time.
        val mapItems = items.filter { it.mediaItem.localConfiguration?.uri.toString().contains("/map_") }
        assertEquals(maps.size, mapItems.size)
        assertTrue(mapItems.all { it.effects.videoEffects.any { e -> e is androidx.media3.effect.OverlayEffect } }, "every map item carries the route overlay")
        val overlay = RouteOverlay(384, 216, pts, null, 1_000_000, 4_000_000)
        val b0 = overlay.getBitmap(1_000_000).copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val b1 = overlay.getBitmap(5_000_000)
        fun inked(b: android.graphics.Bitmap): Int { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px.count { it ushr 24 > 0 } }
        assertTrue(inked(b1) > inked(b0) * 3, "the route grows between the item start and its end: ${inked(b0)} → ${inked(b1)}")
        assertTrue(built.hasAudio)
        val seqs = built.composition.sequences
        assertTrue(seqs.size >= 2 && seqs.last().isLooping, "the music sequence loops: ${seqs.size}")
        // Photos have no content uri in this test → skipped and counted; cards and maps remain.
        assertEquals(plan.scenes.count { it is VideoPlan.Photo || it is VideoPlan.VideoClip }, built.skipped.size)
        assertEquals(plan.scenes.size - built.skipped.size, seqs.first().editedMediaItems.size)
        assertTrue(work.listFiles()!!.any { it.name.startsWith("map_") && it.extension == "jpg" }, "the base map is a still")
        audioFile.delete(); music.delete()
    }

    @Test
    fun `subtitles mode has no audio sequences and a portrait plan keeps its output size`() {
        val (inp, pts) = input()
        val plan = Planner.plan(inp, PlanParams(60, aspect = Aspect.PORTRAIT_9_16, includeVideos = false))
        assertEquals(1080 to 1920, plan.output.width to plan.output.height)
        val work = File(ctx.cacheDir, "engine-test-2").apply { mkdirs() }
        val built = MiniVideoComposer(ctx, work, 216, 384, options = MiniVideoComposer.Options()).build(plan, inp, emptyMap(), emptyMap(), mapOf("s1" to MiniVideoComposer.SegmentMedia(pts, null), "s2" to MiniVideoComposer.SegmentMedia(pts, null)))
        assertEquals(1, built.composition.sequences.size)
        assertTrue(!built.hasAudio)
        assertTrue(built.composition.sequences.first().editedMediaItems.none { it.effects.videoEffects.any { e -> e is androidx.media3.effect.OverlayEffect } }, "static maps without animateMap")
    }

    @Test
    fun `plan store round trips params and edits`() {
        val store = VideoPlanStore(ctx)
        val (inp, _) = input()
        val params = VideoParams(durationS = 300, aspect = "9:16", voice = "both", musicUri = "content://x/1", musicVolume = 0.25)
        val plan = Planner.plan(inp, params.toPlanParams())
        store.save("trip-a", params, plan)
        val (p2, plan2) = store.load("trip-a")!!
        assertEquals(params, p2)
        assertEquals(plan, plan2)
        assertEquals(VoiceMode.BOTH, p2.toPlanParams().voice)
        assertEquals(Aspect.PORTRAIT_9_16, p2.toPlanParams().aspect)
        store.clear("trip-a")
        assertEquals(null, store.load("trip-a"))
        assertTrue(runCatching { store.file("../x") }.isFailure, "ids are path components")
    }
}
