package com.bitacoraviaje.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.bitacoraviaje.core.model.MediaItem as TripMedia
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.videoplan.PlanInput
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.core.videoplan.VoiceMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Turns a [VideoPlan] into a Media3 [Composition] (DISENO §13.6, skill trip-video §4) — the one
 * engine behind the 1-minute stage video (PLAN 4.6) and the full trip video (PLAN 5.1).
 * Every scene becomes a frame-sized JPEG "still" (cards drawn with Canvas; photos scaled to
 * cover the frame, vertical ones over their own blurred copy like the desktop renderer) so the
 * video sequence has one format. Photos get a Ken Burns [MatrixTransformation] timed from the
 * item's start inside the composition and a subtitle [BitmapOverlay]. With [Options.animateMap]
 * the map scenes are the base map under a [RouteOverlay] that grows the route per frame. Voice
 * notes (mode audio/both) go in a parallel audio sequence with gaps at their scene offsets;
 * the user's music loops in a third sequence scaled by [Options.musicVolume].
 */
@UnstableApi
class MiniVideoComposer(
    private val context: Context,
    private val workDir: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val options: Options = Options(),
) {
    data class Options(
        val voice: VoiceMode = VoiceMode.SUBTITLES,
        val animateMap: Boolean = false,
        val musicUri: Uri? = null,
        val musicVolume: Float = 0.4f,
    )

    /**
     * What the engine needs per segment: the track and a way to get its map. The snapshot is fetched
     * only when a map scene needs it and recycled right after the still is written (review I3).
     */
    class SegmentMedia(val points: List<TrackPoint>, private val mapProvider: () -> RouteSnapshotter.RouteBase?) {
        constructor(points: List<TrackPoint>, mapBase: RouteSnapshotter.RouteBase?) : this(points, { mapBase })

        fun map(): RouteSnapshotter.RouteBase? = mapProvider()
    }

    /**
     * [composition] is the single-pass build (video + audio). [videoOnly] and [audioSequences] let the
     * worker render in two passes: the silent video first, then the audio muxed over it (see RenderWorker).
     */
    data class Built(
        val composition: Composition,
        val durationMs: Long,
        val skipped: List<String>,
        val hasAudio: Boolean,
        val voicesDropped: Int,
        val warnings: List<String>,
        val videoOnly: Composition,
        val audioSequences: List<EditedMediaItemSequence>,
    )

    /** Stage video: one segment. */
    fun build(plan: VideoPlan, input: PlanInput, media: Map<String, TripMedia>, voices: Map<String, VoiceNote>, points: List<TrackPoint>, mapBase: RouteSnapshotter.RouteBase?): Built =
        build(plan, input, media, voices, input.segments.associate { it.id to SegmentMedia(points, mapBase) })

    /** [isActive] is polled per scene so a cancelled worker stops drawing instead of finishing the whole build (review I1). */
    fun build(plan: VideoPlan, input: PlanInput, media: Map<String, TripMedia>, voices: Map<String, VoiceNote>, segments: Map<String, SegmentMedia>, isActive: () -> Boolean = { true }): Built {
        workDir.mkdirs()
        val items = ArrayList<EditedMediaItem>()
        val skipped = ArrayList<String>()
        val warnings = ArrayList<String>()
        val voiceCues = ArrayList<Triple<Long, Long, VoiceNote>>() // offsetUs, maxDurationUs, note
        var startUs = 0L
        var anyClipAudio = false
        var anyMap = false
        val titles = input.segments.associate { it.id to it.title }
        val mapAttribution = "© OpenStreetMap contributors"
        val credits = plan.scenes.filterIsInstance<VideoPlan.Credits>()
        for ((i, scene) in plan.scenes.withIndex()) {
            if (!isActive()) throw kotlinx.coroutines.CancellationException("render cancelled")
            val durationMs = scene.durationMs
            val item: EditedMediaItem? = when (scene) {
                is VideoPlan.Title -> {
                    val cover = media[scene.background_media ?: ""]?.let(::decode)
                    image(card("title_$i", Cards.title(width, height, scene.title, scene.subtitle, cover)), durationMs).also { cover?.recycle() }
                }
                is VideoPlan.Chapter -> image(card("chapter_$i", Cards.chapter(width, height, scene.title, scene.date)), durationMs)
                is VideoPlan.PlaceCard -> image(card("place_$i", Cards.place(width, height, scene.title, scene.text, scene.source)), durationMs)
                is VideoPlan.Stats -> image(card("stats_$i", statsCard(scene, titles[scene.segment ?: ""])), durationMs)
                is VideoPlan.Credits -> {
                    // The tile provider is credited only when its map was actually used (review M1): credits come last, after every map scene.
                    val lines = if (!anyMap) scene.lines.filterNot { it == MiniVideoPlanner.TILES_CREDIT } else scene.lines
                    image(card("credits_$i", Cards.credits(width, height, lines)), durationMs)
                }
                is VideoPlan.MapRoute -> mapScene(i, scene, segments, mapAttribution, startUs).also { (_, usedMap) -> anyMap = anyMap || usedMap }.first
                is VideoPlan.VoiceOverlay -> {
                    val sm = segments[scene.segment]
                    val base = sm?.map()
                    anyMap = anyMap || base != null
                    val still = Cards.route(width, height, sm?.points.orEmpty(), base?.bitmap, mapAttribution, base?.projected)
                    base?.bitmap?.recycle()
                    if (options.voice != VoiceMode.AUDIO) scene.text?.let { overlayOn(still, Cards.caption(width, height, it)) }
                    if (options.voice != VoiceMode.SUBTITLES) voices[scene.voice]?.let { voiceCues += Triple(startUs, durationMs * 1000, it) }
                    image(card("voice_$i", still), durationMs)
                }
                is VideoPlan.Photo -> {
                    val m = media[scene.media]
                    val still = m?.let { still("photo_$i", it) }
                    if (still == null) { skipped += scene.media; null } else {
                        val voice = scene.subtitle_from_voice?.let { voices[it] }
                        if (voice != null && options.voice != VoiceMode.SUBTITLES) voiceCues += Triple(startUs, durationMs * 1000, voice)
                        val subtitle = if (options.voice == VoiceMode.AUDIO) scene.caption else (voice?.displayText ?: scene.caption)
                        photo(still, durationMs, scene.kenburns, subtitle, startUs)
                    }
                }
                is VideoPlan.VideoClip -> {
                    val m = media[scene.media]
                    val uri = m?.contentUri?.toUri()
                    if (uri == null) { skipped += scene.media; null } else { anyClipAudio = anyClipAudio || scene.keep_audio; clip(uri, m.mimeType, scene.trim_start_ms, scene.trim_end_ms, scene.keep_audio) }
                }
            }
            if (item != null) { items += item; startUs += durationMs * 1000 }
        }
        require(items.isNotEmpty()) { context.getString(com.bitacoraviaje.app.R.string.video_error_no_scenes) }
        // Music that cannot be opened degrades to "no music" with a warning instead of failing the export (review M4).
        val musicUri = options.musicUri?.takeIf { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { } != null }.getOrDefault(false) }
        if (options.musicUri != null && musicUri == null) warnings += context.getString(com.bitacoraviaje.app.R.string.video_warn_music_missing)
        val wantAudio = anyClipAudio || voiceCues.isNotEmpty() || musicUri != null
        // Declaring both tracks fills the items without audio with silence, so clips, voices and music can mix.
        val videoBuilder = if (wantAudio) EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) else EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        items.forEach { videoBuilder.addItem(it) }
        val sequences = arrayListOf(videoBuilder.build())
        var voicesDropped = 0
        if (voiceCues.isNotEmpty()) {
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var cursor = 0L
            var added = 0
            for ((offsetUs, maxUs, note) in voiceCues.sortedBy { it.first }) {
                val f = File(context.filesDir, note.audioPath)
                if (!f.exists() || offsetUs < cursor) { voicesDropped++; continue } // missing file or overlapping the previous note (review I5)
                if (offsetUs > cursor) b.addGap(offsetUs - cursor)
                // The stored duration is wall-clock time and runs ~100 ms past the audio actually encoded; a clip
                // end beyond the file's end stalls the audio pipeline and the muxer aborts with 7002 (Pixel, audio
                // mode). Clip to what the file really holds (review of the field report, 2026-10-13).
                val fileMs = mediaDurationMs(f) ?: note.durationMs
                val lengthMs = minOf(note.durationMs, maxUs / 1000, fileMs)
                val item = MediaItem.Builder().setUri(Uri.fromFile(f))
                if (lengthMs < fileMs) item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(lengthMs).build())
                b.addItem(EditedMediaItem.Builder(item.build()).setRemoveVideo(true).build())
                cursor = offsetUs + lengthMs * 1000
                added++
            }
            // The voice track must span the whole video: when it ended before the last scene (no music to keep
            // the audio track alive), the muxer refused to write video more than ~0.5 s ahead of the audio and
            // aborted with ERROR_CODE_MUXING_TIMEOUT (7002) on the Pixel in "audio original" mode.
            if (added > 0 && cursor < startUs) b.addGap(startUs - cursor)
            if (added > 0) sequences += b.build()
        }
        musicUri?.let { uri ->
            // Constant-power downmix of 1–6 channels to stereo, scaled by the user's volume (review M5).
            val gain = ChannelMixingAudioProcessor().apply {
                for (ch in 1..6) runCatching { putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(ch, 2).scaleBy(options.musicVolume)) }
            }
            val music = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setRemoveVideo(true).setEffects(Effects(listOf(gain), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(music).setIsLooping(true).build()
        }
        // With voices or music the primary (image) sequence must carry a real silent audio track from the first
        // frame: on the Pixel 8 Pro the muxer otherwise never received a sample and aborted with 7002, while the
        // emulator's slower pipeline happened to resolve the voice track's leading gap in time.
        val composition = Composition.Builder(sequences).experimentalSetForceAudioTrack(wantAudio).build()
        val videoOnly = Composition.Builder(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).also { b -> items.forEach { b.addItem(it) } }.build()).build()
        return Built(composition, startUs / 1000, skipped, wantAudio, voicesDropped, warnings, videoOnly, sequences.drop(1))
    }

    /** Real duration of an audio file (null when it cannot be read, e.g. a placeholder in tests). */
    private fun mediaDurationMs(f: File): Long? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun statsCard(scene: VideoPlan.Stats, segmentTitle: String?): Bitmap = Cards.stats(
        width, height, segmentTitle ?: context.getString(com.bitacoraviaje.app.R.string.video_stats_title),
        listOf(
            context.getString(com.bitacoraviaje.app.R.string.video_stat_distance) to Cards.fmtKm(scene.distance_m),
            context.getString(com.bitacoraviaje.app.R.string.video_stat_moving) to Cards.fmtDur(scene.moving_time_ms),
            context.getString(com.bitacoraviaje.app.R.string.video_stat_gain) to Cards.fmtM(scene.elevation_gain_m),
            context.getString(com.bitacoraviaje.app.R.string.video_stat_max_alt) to Cards.fmtM(scene.max_alt_m),
            context.getString(com.bitacoraviaje.app.R.string.video_stat_photos) to (scene.photos?.toString() ?: "—"),
        ),
    )

    /**
     * Static route card, or (full video) the base map with the routes growing in a [RouteOverlay] timed from
     * the item start. Each segment keeps its own line (review I7); one snapshot covers one segment, so an
     * overview of several segments is drawn on the plain ground. Returns the item and whether a map was used.
     */
    private fun mapScene(i: Int, scene: VideoPlan.MapRoute, segments: Map<String, SegmentMedia>, attribution: String, startUs: Long): Pair<EditedMediaItem, Boolean> {
        val parts = scene.segments.mapNotNull { segments[it] }
        val base = if (parts.size == 1) parts[0].map() else null
        val tracks = parts.map { it.points }.filter { it.size >= 2 }
        val baseSize = base?.let { it.bitmap.width to it.bitmap.height }
        val projectedTracks: List<List<Pair<Float, Float>>> = if (base != null) listOf(Cards.routePixels(width, height, tracks.firstOrNull().orEmpty(), base.projected, baseSize?.first, baseSize?.second)) else Cards.fitTracks(width, height, tracks)
        if (!options.animateMap || tracks.isEmpty()) {
            val still = Cards.routeBase(width, height, base?.bitmap, attribution)
            Canvas(still).let { c -> tracks.forEachIndexed { k, t -> Cards.drawRoutePath(c, width, height, t, projectedTracks.getOrNull(k), 1.0, baseSize = width to height, colorIndex = k) } }
            base?.bitmap?.recycle()
            return image(card("map_$i", still), scene.duration_ms) to (base != null)
        }
        val still = card("map_$i", Cards.routeBase(width, height, base?.bitmap, attribution))
        base?.bitmap?.recycle()
        val overlay = RouteOverlay(width, height, tracks, projectedTracks, startUs, scene.duration_ms * 1000)
        return EditedMediaItem.Builder(MediaItem.Builder().setUri(still).setMimeType("image/jpeg").setImageDurationMs(scene.duration_ms).build())
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build() to (base != null)
    }

    private fun card(name: String, bitmap: Bitmap): Uri {
        val f = File(workDir, "$name.jpg")
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return Uri.fromFile(f)
    }

    private fun overlayOn(base: Bitmap, overlay: Bitmap) {
        Canvas(base).drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()
    }

    /** A photo as a frame-sized still: cover-fit, or a vertical photo centred on its blurred, darkened copy (review I5). */
    private fun still(name: String, m: TripMedia): Uri? {
        val src = decode(m) ?: return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val frameRatio = width.toFloat() / height
        val ratio = src.width.toFloat() / src.height
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (ratio < frameRatio - 0.2f) {
            val bg = cover(src, width, height)
            val darken = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.5f, 0.5f, 0.5f, 1f) }) }
            val tiny = Bitmap.createScaledBitmap(bg, max(8, width / 40), max(8, height / 40), true)
            c.drawBitmap(tiny, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), darken)
            tiny.recycle(); bg.recycle()
            val scale = height.toFloat() / src.height
            val dw = src.width * scale
            c.drawBitmap(src, null, RectF((width - dw) / 2, 0f, (width + dw) / 2, height.toFloat()), paint)
        } else {
            val scale = max(width.toFloat() / src.width, height.toFloat() / src.height)
            val dw = src.width * scale; val dh = src.height * scale
            c.drawBitmap(src, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), paint)
        }
        src.recycle()
        return card(name, out)
    }

    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun image(uri: Uri, durationMs: Long): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setMimeType("image/jpeg").setImageDurationMs(durationMs).build()).setFrameRate(fps).build()

    /** Ken Burns timed from [startUs] (composition time, review B1) over a frame-sized still; subtitle panel anchored at the bottom. */
    private fun photo(still: Uri, durationMs: Long, kb: VideoPlan.KenBurns, caption: String?, startUs: Long): EditedMediaItem {
        val durationUs = durationMs * 1000
        val kenBurns = MatrixTransformation { presentationTimeUs -> KenBurnsMath.matrix(kb, startUs, durationUs, presentationTimeUs) }
        val effects = ArrayList<androidx.media3.common.Effect>()
        effects += kenBurns
        caption?.takeIf { it.isNotBlank() }?.let {
            val panel = Cards.captionPanel(width, height, it)
            val settings = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.88f).setOverlayFrameAnchor(0f, -1f).build()
            effects += OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(panel, settings)))
        }
        return EditedMediaItem.Builder(MediaItem.Builder().setUri(still).setMimeType("image/jpeg").setImageDurationMs(durationMs).build())
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), effects))
            .build()
    }

    private fun clip(uri: Uri, mimeType: String?, startMs: Long, endMs: Long, keepAudio: Boolean): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri).also { b -> mimeType?.let(b::setMimeType) }
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build(),
        ).setRemoveAudio(!keepAudio).setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)))).build()

    /** Decodes a photo near the frame size, honouring EXIF orientation; the intermediate bitmap is recycled (review N2). */
    private fun decode(m: TripMedia): Bitmap? {
        val uri = m.contentUri?.toUri() ?: return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val sample = maxOf(1, minOf(opts.outWidth / width, opts.outHeight / height))
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { bmp.recycle() }
        }.getOrNull()
    }
}
