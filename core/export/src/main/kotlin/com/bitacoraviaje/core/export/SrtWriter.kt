package com.bitacoraviaje.core.export

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Voice notes as SRT subtitles (DISENO §12.3): one cue per note, timed from the start of
 * its segment; a note's transcript segments (if any) become separate cues.
 */
class SrtWriter(override val path: String = "subtitulos/notas.srt") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        var n = 0
        trip.segments.forEach { se ->
            val base = se.segment.startedUtcMs
            se.voiceNotes.sortedBy { it.startedUtcMs }.forEach { v ->
                val text = v.displayText?.takeIf { it.isNotBlank() } ?: return@forEach
                val start = (v.startedUtcMs - base).coerceAtLeast(0)
                val end = start + v.durationMs.coerceAtLeast(MIN_CUE_MS)
                n++
                w.write("$n\n${stamp(start)} --> ${stamp(end)}\n$text\n\n")
            }
        }
        w.flush()
    }

    companion object {
        const val MIN_CUE_MS = 1_500L

        fun stamp(ms: Long): String {
            val h = ms / 3_600_000
            val m = ms / 60_000 % 60
            val s = ms / 1_000 % 60
            return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms % 1_000)
        }
    }
}
