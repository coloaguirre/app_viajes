package com.bitacoraviaje.app.video

import android.graphics.Matrix
import com.bitacoraviaje.core.videoplan.VideoPlan

/**
 * Ken Burns as a time-dependent matrix in normalised device coordinates, equivalent to the
 * desktop renderer's `zoompan` (kenburns.py): the plan's `[x, y, zoom]` use x/y in 0–0.15 as
 * the position of the visible window inside the zoomed image (0 = left/top edge, 0.15 =
 * right/bottom edge). Pure, so the timing bug of review B1 (composition time vs item time)
 * is covered by a unit test.
 */
object KenBurnsMath {
    const val PAN_RANGE = 0.15

    /** Progress 0..1 of an item that starts at [startUs] in the composition and lasts [durationUs]. */
    fun progress(presentationTimeUs: Long, startUs: Long, durationUs: Long): Double =
        if (durationUs <= 0) 1.0 else ((presentationTimeUs - startUs).toDouble() / durationUs).coerceIn(0.0, 1.0)

    /** Scale and NDC translation at progress [t] (review I1: `tx = (z−1)(1 − 2x/0.15)`, `ty = (z−1)(2y/0.15 − 1)`, y up). */
    fun transform(kb: VideoPlan.KenBurns, t: Double): Triple<Float, Float, Float> {
        val (x0, y0, z0) = kb.from
        val (x1, y1, z1) = kb.to
        val z = (z0 + (z1 - z0) * t).coerceAtLeast(1.0)
        val x = x0 + (x1 - x0) * t
        val y = y0 + (y1 - y0) * t
        val tx = (z - 1) * (1 - 2 * (x / PAN_RANGE).coerceIn(0.0, 1.0))
        val ty = (z - 1) * (2 * (y / PAN_RANGE).coerceIn(0.0, 1.0) - 1)
        return Triple(z.toFloat(), tx.toFloat(), ty.toFloat())
    }

    fun matrix(kb: VideoPlan.KenBurns, startUs: Long, durationUs: Long, presentationTimeUs: Long): Matrix {
        val (z, tx, ty) = transform(kb, progress(presentationTimeUs, startUs, durationUs))
        return Matrix().apply { postScale(z, z); postTranslate(tx, ty) }
    }
}
