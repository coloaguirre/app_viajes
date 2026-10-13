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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Turns a [VideoPlan] into a Media3 [Composition] (DISENO §13.6, skill trip-video §4).
 * Every scene becomes a frame-sized JPEG "still" (cards drawn with Canvas; photos scaled to
 * cover the frame, vertical ones over their own blurred copy like the desktop renderer,
 * review I5) so the sequence has one format and no Presentation effect is needed. Photos get a
 * Ken Burns [MatrixTransformation] timed from the item's start inside the composition
 * (review B1) and a subtitle [BitmapOverlay] the size of its panel (review M8). Music is a
 * Fase 5 feature (full engine); the preset never asks for it.
 */
@UnstableApi
class MiniVideoComposer(
    private val context: Context,
    private val workDir: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
) {
    data class Built(val composition: Composition, val durationMs: Long, val skipped: List<String>)

    fun build(plan: VideoPlan, input: PlanInput, media: Map<String, TripMedia>, voices: Map<String, VoiceNote>, points: List<TrackPoint>, mapBase: RouteSnapshotter.RouteBase?): Built {
        workDir.mkdirs()
        val items = ArrayList<EditedMediaItem>()
        val skipped = ArrayList<String>()
        var startUs = 0L
        val segment = input.segments.firstOrNull()
        val mapAttribution = "© OpenStreetMap contributors"
        for ((i, scene) in plan.scenes.withIndex()) {
            val durationMs = scene.durationMs
            val item: EditedMediaItem? = when (scene) {
                is VideoPlan.Title -> {
                    val cover = media[scene.background_media ?: ""]?.let(::decode)
                    image(card("title_$i", Cards.title(width, height, scene.title, scene.subtitle, cover)), durationMs).also { cover?.recycle() }
                }
                is VideoPlan.Chapter -> image(card("chapter_$i", Cards.chapter(width, height, scene.title, scene.date)), durationMs)
                is VideoPlan.PlaceCard -> image(card("place_$i", Cards.place(width, height, scene.title, scene.text, scene.source)), durationMs)
                is VideoPlan.Stats -> image(
                    card(
                        "stats_$i",
                        Cards.stats(
                            width, height, segment?.title ?: context.getString(com.bitacoraviaje.app.R.string.video_stats_title),
                            listOf(
                                context.getString(com.bitacoraviaje.app.R.string.video_stat_distance) to Cards.fmtKm(scene.distance_m),
                                context.getString(com.bitacoraviaje.app.R.string.video_stat_moving) to Cards.fmtDur(scene.moving_time_ms),
                                context.getString(com.bitacoraviaje.app.R.string.video_stat_gain) to Cards.fmtM(scene.elevation_gain_m),
                                context.getString(com.bitacoraviaje.app.R.string.video_stat_max_alt) to Cards.fmtM(scene.max_alt_m),
                                context.getString(com.bitacoraviaje.app.R.string.video_stat_photos) to (scene.photos?.toString() ?: "—"),
                            ),
                        ),
                    ),
                    durationMs,
                )
                is VideoPlan.Credits -> {
                    // The tile provider is credited only when its map was actually used (review M1).
                    val lines = if (mapBase == null) scene.lines.filterNot { it == MiniVideoPlanner.TILES_CREDIT } else scene.lines
                    image(card("credits_$i", Cards.credits(width, height, lines)), durationMs)
                }
                is VideoPlan.MapRoute -> image(card("map_$i", Cards.route(width, height, points, mapBase?.bitmap, mapAttribution, mapBase?.projected)), durationMs)
                is VideoPlan.VoiceOverlay -> image(
                    card("voice_$i", Cards.route(width, height, points, mapBase?.bitmap, mapAttribution, mapBase?.projected).also { b -> scene.text?.let { overlayOn(b, Cards.caption(width, height, it)) } }),
                    durationMs,
                )
                is VideoPlan.Photo -> {
                    val m = media[scene.media]
                    val still = m?.let { still("photo_$i", it) }
                    if (still == null) { skipped += scene.media; null } else {
                        val subtitle = scene.subtitle_from_voice?.let { voices[it]?.displayText } ?: scene.caption
                        photo(still, durationMs, scene.kenburns, subtitle, startUs)
                    }
                }
                is VideoPlan.VideoClip -> {
                    val m = media[scene.media]
                    val uri = m?.contentUri?.toUri()
                    if (uri == null) { skipped += scene.media; null } else clip(uri, m.mimeType, scene.trim_start_ms, scene.trim_end_ms)
                }
            }
            if (item != null) { items += item; startUs += durationMs * 1000 }
        }
        require(items.isNotEmpty()) { context.getString(com.bitacoraviaje.app.R.string.video_error_no_scenes) }
        val video = EditedMediaItemSequence.Builder(items).build()
        return Built(Composition.Builder(listOf(video)).build(), startUs / 1000, skipped)
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
            // Cheap blur: draw a tiny downscale back up.
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
            // Anchor the panel's bottom-centre to the frame's bottom-centre, slightly up (NDC: y up).
            val settings = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.88f).setOverlayFrameAnchor(0f, -1f).build()
            effects += OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(panel, settings)))
        }
        return EditedMediaItem.Builder(MediaItem.Builder().setUri(still).setMimeType("image/jpeg").setImageDurationMs(durationMs).build())
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), effects))
            .build()
    }

    private fun clip(uri: Uri, mimeType: String?, startMs: Long, endMs: Long): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri).also { b -> mimeType?.let(b::setMimeType) }
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build(),
        ).setEffects(Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForWidthAndHeight(width, height, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT)))).build()

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
