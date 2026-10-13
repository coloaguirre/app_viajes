package com.bitacoraviaje.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.bitacoraviaje.core.model.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Cards drawn with Canvas for the on-device video (same look as desktop-renderer/cards.py):
 * title, chapter, place, stats, credits, caption panel and a static route map. Pure drawing,
 * testable with Robolectric; no Media3 here.
 */
object Cards {
    val NAVY = Color.rgb(31, 56, 100)
    val INK = Color.rgb(27, 27, 27)
    val PAPER = Color.rgb(247, 244, 238)
    val ACCENT = Color.rgb(244, 185, 66)
    private val GROUND = Color.rgb(226, 232, 226)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private fun layout(text: String, p: TextPaint, width: Int, maxLines: Int = Int.MAX_VALUE, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, width)).setAlignment(align).setLineSpacing(0f, 1.15f).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()

    private fun bitmap(w: Int, h: Int, bg: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(bg)
        return b to c
    }

    private fun shadowed(c: Canvas, l: StaticLayout, x: Float, y: Float) {
        c.save(); c.translate(x + 3, y + 3); l.paint.color = Color.argb(160, 0, 0, 0); l.draw(c); c.restore()
        l.paint.color = PAPER
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    /** Title over the (darkened, scaled) cover photo when given, else the navy ground. */
    fun title(w: Int, h: Int, title: String, subtitle: String?, cover: Bitmap?): Bitmap {
        val (b, c) = bitmap(w, h, NAVY)
        cover?.let { drawCover(c, it, w, h, darken = 0.6f) }
        val big = paint(h * 0.09f, PAPER, bold = true)
        val l = layout(title, big, (w * 0.84).toInt(), maxLines = 3, align = Layout.Alignment.ALIGN_CENTER)
        val y = h * 0.40f - l.height / 2f
        shadowed(c, l, w * 0.08f, y)
        subtitle?.let {
            val small = layout(it, paint(h * 0.04f, ACCENT), (w * 0.84).toInt(), 1, Layout.Alignment.ALIGN_CENTER)
            c.save(); c.translate(w * 0.08f, y + l.height + h * 0.03f); small.draw(c); c.restore()
        }
        return b
    }

    fun chapter(w: Int, h: Int, title: String, date: String?): Bitmap {
        val (b, c) = bitmap(w, h, PAPER)
        c.drawRect(0f, h * 0.44f, w * 0.03f, h * 0.56f, Paint().apply { color = ACCENT })
        val l = layout(title, paint(h * 0.07f, NAVY, bold = true), (w * 0.8).toInt(), 3)
        c.save(); c.translate(w * 0.08f, h * 0.42f); l.draw(c); c.restore()
        date?.let { c.drawText(it, w * 0.08f, h * 0.42f + l.height + h * 0.05f, paint(h * 0.035f, INK)) }
        return b
    }

    fun place(w: Int, h: Int, title: String, text: String, source: String): Bitmap {
        val (b, c) = bitmap(w, h, NAVY)
        val margin = w * 0.06f
        val box = RectF(margin, h * 0.18f, w - margin, h * 0.88f)
        c.drawRoundRect(box, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 20, 24, 36) })
        val x = margin + w * 0.03f
        var y = box.top + h * 0.03f
        val head = layout(title, paint(h * 0.06f, PAPER, bold = true), (box.width() - w * 0.06f).toInt(), 2)
        c.save(); c.translate(x, y); head.draw(c); c.restore()
        y += head.height + h * 0.02f
        val bodyPaint = paint(h * 0.036f, PAPER)
        val maxLines = max(2, ((box.bottom - y - h * 0.08f) / (bodyPaint.textSize * 1.35f)).toInt())
        val body = layout(text, bodyPaint, (box.width() - w * 0.06f).toInt(), maxLines)
        c.save(); c.translate(x, y); body.draw(c); c.restore()
        c.drawText("Fuente: $source", x, box.bottom - h * 0.03f, paint(h * 0.024f, ACCENT))
        return b
    }

    fun stats(w: Int, h: Int, title: String, rows: List<Pair<String, String>>): Bitmap {
        val (b, c) = bitmap(w, h, NAVY)
        c.drawText(title, w * 0.08f, h * 0.18f, paint(h * 0.055f, PAPER, bold = true))
        val cols = if (w >= h) 3 else 2
        val cellW = (w * 0.84f) / cols
        val value = paint(h * 0.07f, ACCENT, bold = true)
        val label = paint(h * 0.034f, PAPER)
        rows.forEachIndexed { i, (lab, v) ->
            val cx = w * 0.08f + (i % cols) * cellW
            val cy = h * 0.40f + (i / cols) * h * 0.28f
            c.drawText(v, cx, cy, value)
            c.drawText(lab, cx, cy + value.textSize * 0.8f, label)
        }
        return b
    }

    fun credits(w: Int, h: Int, lines: List<String>): Bitmap {
        val (b, c) = bitmap(w, h, INK)
        c.drawText("Créditos", w * 0.08f, h * 0.15f, paint(h * 0.05f, PAPER, bold = true))
        var y = h * 0.22f
        val p = paint(h * 0.026f, PAPER)
        for (line in lines) {
            val l = layout(line, p, (w * 0.84).toInt(), 2)
            c.save(); c.translate(w * 0.08f, y); l.draw(c); c.restore()
            y += l.height + p.textSize * 0.6f
            if (y > h * 0.92f) break
        }
        return b
    }

    /** Transparent overlay with a caption/subtitle panel at the bottom (BitmapOverlay over a photo). */
    fun caption(w: Int, h: Int, text: String): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = paint(h * 0.034f, PAPER)
        val l = layout(text, p, (w * 0.8).toInt(), 3)
        val blockH = l.height + h * 0.03f
        val y0 = h - blockH - h * 0.06f
        c.drawRoundRect(RectF(w * 0.06f, y0, w - w * 0.06f, y0 + blockH), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 20, 24, 36) })
        c.save(); c.translate(w * 0.09f, y0 + h * 0.015f); l.draw(c); c.restore()
        return b
    }

    /** Only the subtitle panel (not a full transparent frame, review M8): overlay it anchored at the bottom. */
    fun captionPanel(w: Int, h: Int, text: String): Bitmap {
        val p = paint(h * 0.034f, PAPER)
        val l = layout(text, p, (w * 0.8).toInt(), 3)
        val pw = (w * 0.88f).toInt()
        val ph = (l.height + h * 0.03f).toInt()
        val b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, pw.toFloat(), ph.toFloat()), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 20, 24, 36) })
        c.save(); c.translate(w * 0.03f, h * 0.015f); l.draw(c); c.restore()
        return b
    }

    /**
     * Static map of the stage: the route over [base] (a MapLibre snapshot, with the points already
     * projected by its camera, review I2) or over a plain ground with an equirectangular fit.
     */
    fun route(w: Int, h: Int, points: List<TrackPoint>, base: Bitmap?, attribution: String, projected: List<Pair<Float, Float>>? = null, progress: Double = 1.0): Bitmap {
        val (b, c) = bitmap(w, h, GROUND)
        drawRoute(c, w, h, points, base, attribution, projected, progress)
        return b
    }

    /** Draws the (partial, up to [progress] of the time span, eased by the caller) route on [c]; used for stills and animated frames. */
    fun drawRoute(c: Canvas, w: Int, h: Int, points: List<TrackPoint>, base: Bitmap?, attribution: String, projected: List<Pair<Float, Float>>?, progress: Double) {
        if (base != null) drawCover(c, base, w, h, darken = 1f) else c.drawColor(GROUND)
        val pixels: List<Pair<Float, Float>> = if (base != null && projected != null && projected.size == points.size) {
            // Snapshot pixels → frame pixels (the snapshot may have been clamped to MAX_SIDE, drawCover scales it to cover).
            val scale = max(w.toFloat() / base.width, h.toFloat() / base.height)
            val offX = (w - base.width * scale) / 2; val offY = (h - base.height * scale) / 2
            projected.map { (x, y) -> (offX + x * scale) to (offY + y * scale) }
        } else if (points.size >= 2) {
            val lat0 = points.sumOf { it.lat } / points.size
            val kx = cos(Math.toRadians(lat0))
            val xs = points.map { it.lon * kx }
            val ys = points.map { -it.lat }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val spanX = max(1e-9, maxX - minX); val spanY = max(1e-9, maxY - minY)
            val scale = min((w * 0.8) / spanX, (h * 0.8) / spanY)
            val ox = (w - spanX * scale) / 2; val oy = (h - spanY * scale) / 2
            points.indices.map { i -> (ox + (xs[i] - minX) * scale).toFloat() to (oy + (ys[i] - minY) * scale).toFloat() }
        } else {
            emptyList()
        }
        // Time-interpolated prefix of the track (DISENO §13.4): the tip moves smoothly between fixes.
        val drawn: List<Pair<Float, Float>> = if (pixels.size >= 2 && progress < 1.0) {
            val t0 = points.first().timeUtcMs; val t1 = points.last().timeUtcMs
            val t = t0 + ((t1 - t0) * progress.coerceIn(0.0, 1.0)).toLong()
            val n = points.indexOfFirst { it.timeUtcMs > t }.let { if (it < 0) points.size else it }
            val prefix = pixels.take(max(1, n)).toMutableList()
            if (n in 1 until points.size) {
                val pa = points[n - 1]; val pb = points[n]
                val f = ((t - pa.timeUtcMs).toDouble() / max(1L, pb.timeUtcMs - pa.timeUtcMs)).coerceIn(0.0, 1.0).toFloat()
                val (ax, ay) = pixels[n - 1]; val (bx, by) = pixels[n]
                prefix += (ax + (bx - ax) * f) to (ay + (by - ay) * f)
            }
            prefix
        } else {
            pixels
        }
        if (drawn.size >= 2) {
            val path = android.graphics.Path()
            drawn.forEachIndexed { i, (px, py) -> if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            c.drawPath(path, stroke.apply { color = Color.WHITE; strokeWidth = h * 0.012f })
            c.drawPath(path, stroke.apply { color = NAVY; strokeWidth = h * 0.007f })
            val (ex, ey) = drawn.last()
            c.drawCircle(ex, ey, h * 0.014f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(ex, ey, h * 0.01f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 63, 31) })
        }
        // A MapLibre snapshot already carries its own attribution; only the plain ground needs ours.
        if (base == null) {
            val p = paint(max(10f, h / 60f), Color.DKGRAY)
            val tw = p.measureText(attribution)
            c.drawRect(w - tw - 18f, h - p.textSize - 18f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.WHITE })
            c.drawText(attribution, w - tw - 9f, h - 12f, p)
        }
    }

    /** Ease-in-out for the map animation (same curve as desktop `_ease`). */
    fun ease(t: Double): Double = 0.5 - 0.5 * kotlin.math.cos(Math.PI * t.coerceIn(0.0, 1.0))

    /** Scales a bitmap to cover the frame (centre crop); [darken] multiplies the colours (1 = unchanged). */
    private fun drawCover(c: Canvas, src: Bitmap, w: Int, h: Int, darken: Float) {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (darken < 1f) {
            val m = android.graphics.ColorMatrix().apply { setScale(darken, darken, darken, 1f) }
            p.colorFilter = android.graphics.ColorMatrixColorFilter(m)
        }
        c.drawBitmap(src, null, dst, p)
    }

    fun fmtKm(m: Double?): String = when { m == null -> "—"; m >= 1000 -> String.format(java.util.Locale.forLanguageTag("es-AR"), "%.1f km", m / 1000); else -> "${m.toInt()} m" }
    fun fmtDur(ms: Long?): String { if (ms == null) return "—"; val h = ms / 3_600_000; val m = ms / 60_000 % 60; return if (h > 0) "$h h ${"%02d".format(m)} min" else "$m min" }
    fun fmtM(v: Double?): String = v?.let { String.format(java.util.Locale.forLanguageTag("es-AR"), "%,d m", Math.round(it)) } ?: "—"
}
