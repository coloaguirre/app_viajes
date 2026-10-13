@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bitacoraviaje.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.media3.effect.BitmapOverlay
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import com.bitacoraviaje.core.model.TrackPoint

/**
 * The animated map as a Media3 overlay (DISENO §13.4 on the device): the base map is an
 * ordinary image item and this overlay draws the route grown up to the frame's presentation
 * time (ease-in-out). Media3 asks for every frame with its exact timestamp, so the clip
 * lasts exactly what the plan says — unlike a Surface-fed MediaCodec clip, whose frames are
 * stamped with the wall clock (first attempt of PLAN 5.1, measured 15 % short, then 20 % long).
 */
class RouteOverlay(
    private val width: Int,
    private val height: Int,
    private val points: List<TrackPoint>,
    private val projected: List<Pair<Float, Float>>?,
    private val startUs: Long,
    private val durationUs: Long,
) : BitmapOverlay() {
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var lastProgress = -1.0
    private val settings = StaticOverlaySettings.Builder().build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = if (durationUs <= 0) 1.0 else ((presentationTimeUs - startUs).toDouble() / durationUs).coerceIn(0.0, 1.0)
        val progress = Cards.ease(t)
        if (progress != lastProgress) {
            // Drawing on the same bitmap bumps its generation id, which is how Media3 knows to re-upload the texture.
            bitmap.eraseColor(Color.TRANSPARENT)
            Cards.drawRoutePath(canvas, width, height, points, projected, progress, baseSize = width to height)
            lastProgress = progress
        }
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun release() {
        super.release()
        bitmap.recycle()
    }
}
