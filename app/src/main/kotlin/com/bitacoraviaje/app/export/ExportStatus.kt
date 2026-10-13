package com.bitacoraviaje.app.export

import androidx.work.WorkInfo

/** Pure projection of the export WorkInfo for the dialog. */
data class ExportStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val resultUri: String? = null,
    val folder: String? = null,
    val mediaWritten: Int = 0,
    val mediaTotal: Int = 0,
    val audioWritten: Int = 0,
    val audioTotal: Int = 0,
    val missing: Int = 0,
    val privacyPoints: Int = 0,
    val privacyStops: Int = 0,
    val privacyMedia: Int = 0,
    val error: String? = null,
) {
    companion object {
        fun from(info: WorkInfo?): ExportStatus {
            if (info == null) return ExportStatus()
            return when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> ExportStatus(
                    running = true, done = info.progress.getInt(ExportWorker.KEY_PROGRESS_DONE, 0), total = info.progress.getInt(ExportWorker.KEY_PROGRESS_TOTAL, 0),
                )
                WorkInfo.State.SUCCEEDED -> ExportStatus(
                    finished = true, resultUri = info.outputData.getString(ExportWorker.KEY_RESULT_URI), folder = info.outputData.getString(ExportWorker.KEY_RESULT_FOLDER),
                    mediaWritten = info.outputData.getInt(ExportWorker.KEY_MEDIA_WRITTEN, 0), mediaTotal = info.outputData.getInt(ExportWorker.KEY_MEDIA_TOTAL, 0),
                    audioWritten = info.outputData.getInt(ExportWorker.KEY_AUDIO_WRITTEN, 0), audioTotal = info.outputData.getInt(ExportWorker.KEY_AUDIO_TOTAL, 0),
                    privacyPoints = info.outputData.getInt(ExportWorker.KEY_PRIVACY_POINTS, 0), privacyStops = info.outputData.getInt(ExportWorker.KEY_PRIVACY_STOPS, 0), privacyMedia = info.outputData.getInt(ExportWorker.KEY_PRIVACY_MEDIA, 0),
                    missing = info.outputData.getInt(ExportWorker.KEY_MISSING, 0),
                )
                WorkInfo.State.FAILED -> ExportStatus(finished = true, failed = true, error = info.outputData.getString(ExportWorker.KEY_ERROR))
                WorkInfo.State.CANCELLED -> ExportStatus()
            }
        }
    }
}
