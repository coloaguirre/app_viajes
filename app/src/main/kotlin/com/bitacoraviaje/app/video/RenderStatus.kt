package com.bitacoraviaje.app.video

import androidx.work.WorkInfo

/** The mini video job as the finish screen shows it (PLAN 4.6). */
data class RenderStatus(
    val running: Boolean = false,
    val progress: Int = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val resultUri: String? = null,
    val durationMs: Long = 0,
    val elapsedMs: Long = 0,
    val error: String? = null,
) {
    companion object {
        fun from(info: WorkInfo?): RenderStatus {
            if (info == null) return RenderStatus()
            return when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> RenderStatus(running = true, progress = info.progress.getInt(RenderWorker.KEY_PROGRESS, 0))
                WorkInfo.State.SUCCEEDED -> RenderStatus(finished = true, resultUri = info.outputData.getString(RenderWorker.KEY_RESULT_URI), durationMs = info.outputData.getLong(RenderWorker.KEY_DURATION_MS, 0), elapsedMs = info.outputData.getLong(RenderWorker.KEY_ELAPSED_MS, 0))
                WorkInfo.State.FAILED -> RenderStatus(failed = true, error = info.outputData.getString(RenderWorker.KEY_ERROR))
                WorkInfo.State.CANCELLED -> RenderStatus()
            }
        }
    }
}
