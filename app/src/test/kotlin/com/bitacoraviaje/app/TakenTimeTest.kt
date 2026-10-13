package com.bitacoraviaje.app

import android.net.Uri
import com.bitacoraviaje.app.media.ExifData
import com.bitacoraviaje.app.media.MediaCandidate
import com.bitacoraviaje.app.media.MediaLinker
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TakenTimeSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DISENO §7.5: capture-time priority, segment-zone conversion and video end-time adjustment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TakenTimeTest {
    private val t = Instant.parse("2026-10-12T18:49:28Z").toEpochMilli()
    private val seg = Segment("s", "t", null, SegmentType.STAGE, null, null, null, t - 3_600_000, t + 3_600_000, "America/Argentina/Buenos_Aires", "WALK", RecordingMode.FULL, false, SegmentStatus.FINISHED)

    private fun cand(kind: MediaKind = MediaKind.PHOTO, exif: ExifData? = null, taken: Long? = null, added: Long? = null, modified: Long? = null, duration: Long? = null) =
        MediaCandidate(1, Uri.parse("content://media/1"), kind, "x", "image/jpeg", 1, 1, 1, duration, 0, taken, added, modified, "DCIM/Camera/", exif)

    @Test
    fun `exif with offset wins and is exact`() {
        val (ms, src) = MediaLinker.resolveTakenTime(cand(exif = ExifData("2026:10:12 15:49:28", "-03:00", "997", null, null, null, null, null, null), taken = 5), listOf(seg), t)
        assertEquals(TakenTimeSource.EXIF_WITH_OFFSET, src)
        assertEquals(t + 997, ms)
    }

    @Test
    fun `exif local uses the segment zone, not the phone zone`() {
        val (ms, src) = MediaLinker.resolveTakenTime(cand(exif = ExifData("2026:10:12 15:49:28", null, null, null, null, null, null, null, null), taken = t), listOf(seg), t)
        assertEquals(TakenTimeSource.EXIF_LOCAL, src)
        assertEquals(t, ms)
    }

    @Test
    fun `mediastore then file mtime as fallbacks`() {
        assertEquals(TakenTimeSource.MEDIASTORE to t, MediaLinker.resolveTakenTime(cand(taken = t), listOf(seg), t).let { it.second to it.first })
        assertEquals(TakenTimeSource.FILE_MTIME to t, MediaLinker.resolveTakenTime(cand(modified = t / 1000), listOf(seg), t).let { it.second to it.first })
    }

    @Test
    fun `video creation time is the end of the clip`() {
        val (ms, src) = MediaLinker.resolveTakenTime(cand(kind = MediaKind.VIDEO, exif = ExifData("2026:10:12 18:49:28", "+00:00", null, null, null, null, null, null, null), duration = 6_000), listOf(seg), t)
        assertEquals(TakenTimeSource.EXIF_WITH_OFFSET, src)
        assertEquals(t - 6_000, ms)
    }
}
