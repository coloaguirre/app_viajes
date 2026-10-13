package com.bitacoraviaje.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
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
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a [VideoPlan] into a Media3 [Composition] (DISENO §13.6, skill trip-video §4):
 * cards are drawn to JPEG files and enter as image items; photos get a Ken Burns
 * [MatrixTransformation] and a caption/subtitle [BitmapOverlay]; an optional music
 * sequence loops under the video. Every item is scaled to the output size with
 * [Presentation], so the sequence has one format.
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

    fun build(plan: VideoPlan, input: PlanInput, media: Map<String, TripMedia>, voices: Map<String, VoiceNote>, points: List<TrackPoint>, mapBase: Bitmap?): Built {
        workDir.mkdirs()
        val items = ArrayList<EditedMediaItem>()
        val skipped = ArrayList<String>()
        var total = 0L
        val segment = input.segments.firstOrNull()
        var kenIndex = 0
        for ((i, scene) in plan.scenes.withIndex()) {
            val item: EditedMediaItem? = when (scene) {
                is VideoPlan.Title -> image(card("title_$i", Cards.title(width, height, scene.title, scene.subtitle, media[scene.background_media ?: ""]?.let(::decode))), scene.duration_ms)
                is VideoPlan.Chapter -> image(card("chapter_$i", Cards.chapter(width, height, scene.title, scene.date)), scene.duration_ms)
                is VideoPlan.PlaceCard -> image(card("place_$i", Cards.place(width, height, scene.title, scene.text, scene.source)), scene.duration_ms)
                is VideoPlan.Stats -> image(
                    card(
                        "stats_$i",
                        Cards.stats(
                            width, height, segment?.title ?: "Estadísticas",
                            listOf("distancia" to Cards.fmtKm(scene.distance_m), "en movimiento" to Cards.fmtDur(scene.moving_time_ms), "desnivel +" to Cards.fmtM(scene.elevation_gain_m), "altura máx." to Cards.fmtM(scene.max_alt_m), "fotos" to (scene.photos?.toString() ?: "—")),
                        ),
                    ),
                    scene.duration_ms,
                )
                is VideoPlan.Credits -> image(card("credits_$i", Cards.credits(width, height, scene.lines)), scene.duration_ms)
                is VideoPlan.MapRoute -> image(card("map_$i", Cards.route(width, height, points, mapBase, if (mapBase != null) MiniVideoPlanner.TILES_CREDIT.removePrefix("Mapa: ") else "© OpenStreetMap contributors")), scene.duration_ms)
                is VideoPlan.VoiceOverlay -> image(card("voice_$i", Cards.route(width, height, points, mapBase, "© OpenStreetMap contributors").also { b -> scene.text?.let { overlayOn(b, Cards.caption(width, height, it)) } }), scene.duration_ms)
                is VideoPlan.Photo -> {
                    val m = media[scene.media]
                    val uri = m?.contentUri?.toUri()
                    if (uri == null) { skipped += scene.media; null } else {
                        val subtitle = scene.subtitle_from_voice?.let { voices[it]?.displayText } ?: scene.caption
                        photo(uri, scene.duration_ms, scene.kenburns, subtitle, kenIndex++)
                    }
                }
                is VideoPlan.VideoClip -> {
                    val uri = media[scene.media]?.contentUri?.toUri()
                    if (uri == null) { skipped += scene.media; null } else clip(uri, scene.trim_start_ms, scene.trim_end_ms)
                }
            }
            if (item != null) { items += item; total += scene.durationMs }
        }
        require(items.isNotEmpty()) { "plan sin escenas renderizables" }
        val video = EditedMediaItemSequence.Builder(items).build()
        val sequences = ArrayList<EditedMediaItemSequence>().apply { add(video) }
        plan.music?.let { music ->
            val f = File(music.file)
            if (f.exists()) sequences += EditedMediaItemSequence.Builder(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setRemoveVideo(true).build()).setIsLooping(true).build()
        }
        return Built(Composition.Builder(sequences).build(), total, skipped)
    }

    private fun card(name: String, bitmap: Bitmap): Uri {
        val f = File(workDir, "$name.jpg")
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return Uri.fromFile(f)
    }

    private fun overlayOn(base: Bitmap, overlay: Bitmap) {
        android.graphics.Canvas(base).drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()
    }

    private fun presentation() = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

    private fun image(uri: Uri, durationMs: Long): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(durationMs).build())
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), listOf(presentation())))
            .build()

    /** Ken Burns: scale/translate interpolated over the item's duration (plan values are fractions of the frame; zoom 1.0–1.15). */
    private fun photo(uri: Uri, durationMs: Long, kb: VideoPlan.KenBurns, caption: String?, index: Int): EditedMediaItem {
        val durationUs = durationMs * 1000
        val (x0, y0, z0) = kb.from
        val (x1, y1, z1) = kb.to
        val kenBurns = MatrixTransformation { presentationTimeUs ->
            val t = (presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)
            val z = (z0 + (z1 - z0) * t).toFloat()
            // Pan: the plan's x/y are offsets of the visible window as a fraction of the frame; NDC spans 2 units.
            val tx = (-(x0 + (x1 - x0) * t) * 2 / 0.15 * (z - 1).coerceAtLeast(0f)).toFloat()
            val ty = ((y0 + (y1 - y0) * t) * 2 / 0.15 * (z - 1).coerceAtLeast(0f)).toFloat()
            Matrix().apply { postScale(z, z); postTranslate(tx.coerceIn(-(z - 1), z - 1), ty.coerceIn(-(z - 1), z - 1)) }
        }
        val effects = ArrayList<androidx.media3.common.Effect>()
        effects += presentation()
        effects += kenBurns
        caption?.takeIf { it.isNotBlank() }?.let {
            val overlay = BitmapOverlay.createStaticBitmapOverlay(Cards.caption(width, height, it), StaticOverlaySettings.Builder().build())
            effects += OverlayEffect(listOf(overlay))
        }
        return EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(durationMs).build())
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), effects))
            .build()
    }

    private fun clip(uri: Uri, startMs: Long, endMs: Long): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build(),
        ).setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)))).build()

    /** Decodes a photo for a card background at a modest size, honouring EXIF orientation. */
    private fun decode(m: TripMedia): Bitmap? {
        val uri = m.contentUri?.toUri() ?: return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val sample = maxOf(1, minOf(opts.outWidth / width, opts.outHeight / height))
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }.getOrNull()
    }
}
