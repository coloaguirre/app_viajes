package com.bitacoraviaje.core.export

import java.io.Writer
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** ISO 8601 helpers: UTC with `Z` for machines, local with offset for people (DISENO §12.2). */
object Iso {
    private val utc = DateTimeFormatter.ISO_INSTANT

    fun utc(ms: Long): String = utc.format(Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    fun utcMillis(ms: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(ms))

    fun local(ms: Long, tzId: String): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned(ms, tzId).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    fun localDate(ms: Long, tzId: String): String = DateTimeFormatter.ISO_LOCAL_DATE.format(zoned(ms, tzId))

    fun localTime(ms: Long, tzId: String): String = DateTimeFormatter.ofPattern("HH:mm").format(zoned(ms, tzId))

    fun zoned(ms: Long, tzId: String): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(runCatching { ZoneId.of(tzId) }.getOrDefault(ZoneId.of("UTC")))
}

/** File and folder names: ASCII, hyphens, ISO date first so they sort (skill trip-export). */
object Names {
    fun slug(text: String, maxLen: Int = 40): String {
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val s = ascii.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return (if (s.isEmpty()) "viaje" else s).take(maxLen).trim('-')
    }

    fun packageFolder(tripTitle: String, exportedUtcMs: Long, tzId: String): String = "${slug(tripTitle)}_${Iso.localDate(exportedUtcMs, tzId)}"

    fun segmentFile(index: Int, startedUtcMs: Long, tzId: String, title: String?, ext: String): String =
        String.format(Locale.ROOT, "%02d_%s_%s.%s", index, Iso.localDate(startedUtcMs, tzId), slug(title ?: "etapa", 30).lowercase(Locale.ROOT), ext)

    fun mediaPath(kind: String, takenUtcMs: Long, tzId: String, displayName: String?, id: String): String {
        val base = displayName?.takeIf { it.isNotBlank() }?.let { n -> n.substringBeforeLast('.').let(::slug) + "." + n.substringAfterLast('.', "bin").lowercase(Locale.ROOT) } ?: "$id.bin"
        return "media/$kind/${Iso.localDate(takenUtcMs, tzId)}/$base"
    }

    fun audioPath(startedUtcMs: Long, tzId: String, id: String): String =
        "audio/${Iso.localDate(startedUtcMs, tzId)}/nota_${DateTimeFormatter.ofPattern("HHmmss").format(Iso.zoned(startedUtcMs, tzId))}_${id.take(8)}.m4a"
}

/** Minimal XML writer with correct escaping: never concatenate markup by hand (skill trip-export). */
class XmlOut(private val w: Writer) {
    private val stack = ArrayDeque<String>()

    fun prolog() {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    }

    fun open(name: String, vararg attrs: Pair<String, String?>) {
        indent()
        w.write("<$name")
        attrs.forEach { (k, v) -> if (v != null) w.write(" $k=\"${esc(v)}\"") }
        w.write(">\n")
        stack.addLast(name)
    }

    fun close() {
        val name = stack.removeLast()
        indent()
        w.write("</$name>\n")
    }

    fun leaf(name: String, text: String?, vararg attrs: Pair<String, String?>) {
        if (text == null) return
        indent()
        w.write("<$name")
        attrs.forEach { (k, v) -> if (v != null) w.write(" $k=\"${esc(v)}\"") }
        w.write(">${esc(text)}</$name>\n")
    }

    fun empty(name: String, vararg attrs: Pair<String, String?>) {
        indent()
        w.write("<$name")
        attrs.forEach { (k, v) -> if (v != null) w.write(" $k=\"${esc(v)}\"") }
        w.write("/>\n")
    }

    /** `<name><![CDATA[...]]></name>`; a `]]>` inside the text is split so the section stays valid. */
    fun cdata(name: String, text: String) {
        indent()
        w.write("<$name><![CDATA[${text.replace("]]>", "]]]]><![CDATA[>")}]]></$name>\n")
    }

    private fun indent() = w.write("  ".repeat(stack.size))

    companion object {
        fun esc(s: String): String = buildString(s.length + 8) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') append(' ') else append(c)
                }
            }
        }
    }
}

/** Numbers formatted for files: fixed decimals, dot, no locale (coordinates 7, elevation 1). */
object Num {
    fun coord(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

    fun ele(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    fun f1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    fun f2(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
}

/** HTML escaping for the diary and KML descriptions. */
fun htmlEsc(s: String): String = XmlOut.esc(s)
