@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bitacoraviaje.app.video

import android.content.Context
import android.graphics.Canvas
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

    /** Records the frames it was asked for and writes a tiny placeholder file instead of running MediaCodec. */
    private class FakeEncoder : ClipEncoder {
        val calls = ArrayList<Pair<Int, Int>>() // frames, fps
        override fun encode(out: File, width: Int, height: Int, fps: Int, frames: Int, draw: (Int, Canvas) -> Unit) {
            calls += frames to fps
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            repeat(frames) { draw(it, Canvas(bmp)) } // exercises the partial-route drawing for every frame
            out.writeBytes(ByteArray(16))
        }
    }

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
        val enc = FakeEncoder()
        val work = File(ctx.cacheDir, "engine-test").apply { mkdirs() }
        val options = MiniVideoComposer.Options(voice = VoiceMode.BOTH, animateMap = true, musicUri = android.net.Uri.fromFile(music), musicVolume = 0.3f)
        val built = MiniVideoComposer(ctx, work, 384, 216, options = options, encoder = enc).build(plan, inp, emptyMap(), voices, mapOf("s1" to MiniVideoComposer.SegmentMedia(pts, null), "s2" to MiniVideoComposer.SegmentMedia(pts.take(10), null)))
        val maps = plan.scenes.filterIsInstance<VideoPlan.MapRoute>()
        assertEquals(maps.size, enc.calls.size, "one clip per map scene")
        assertEquals(maps.map { (it.duration_ms * 30 / 1000).toInt() }, enc.calls.map { it.first }, "frames = duration × fps")
        assertTrue(built.hasAudio)
        val seqs = built.composition.sequences
        assertTrue(seqs.size >= 2 && seqs.last().isLooping, "the music sequence loops: ${seqs.size}")
        // Photos have no content uri in this test → skipped and counted; cards and maps remain.
        assertEquals(plan.scenes.count { it is VideoPlan.Photo || it is VideoPlan.VideoClip }, built.skipped.size)
        assertEquals(plan.scenes.size - built.skipped.size, seqs.first().editedMediaItems.size)
        assertTrue(work.listFiles()!!.any { it.name.startsWith("map_") && it.extension == "mp4" })
        audioFile.delete(); music.delete()
    }

    @Test
    fun `subtitles mode has no audio sequences and a portrait plan keeps its output size`() {
        val (inp, pts) = input()
        val plan = Planner.plan(inp, PlanParams(60, aspect = Aspect.PORTRAIT_9_16, includeVideos = false))
        assertEquals(1080 to 1920, plan.output.width to plan.output.height)
        val work = File(ctx.cacheDir, "engine-test-2").apply { mkdirs() }
        val built = MiniVideoComposer(ctx, work, 216, 384, options = MiniVideoComposer.Options(), encoder = FakeEncoder()).build(plan, inp, emptyMap(), emptyMap(), mapOf("s1" to MiniVideoComposer.SegmentMedia(pts, null), "s2" to MiniVideoComposer.SegmentMedia(pts, null)))
        assertEquals(1, built.composition.sequences.size)
        assertTrue(!built.hasAudio)
        assertTrue(built.composition.sequences.first().editedMediaItems.none { it.mediaItem.localConfiguration?.mimeType == "video/mp4" }, "static maps without animateMap")
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
