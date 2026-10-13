package com.bitacoraviaje.app.video

import android.media.MediaMetadataRetriever
import java.io.File

/** Duration of a media file as its container reports it (null when unreadable); one helper for recorder, composer and worker. */
object MediaDurations {
    fun of(file: File): Long? {
        val r = MediaMetadataRetriever() // AutoCloseable only from API 29: release by hand (minSdk 26)
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
