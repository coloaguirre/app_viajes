package com.bitacoraviaje.app.export

import androidx.work.WorkInfo

/** Pure projection of the import WorkInfo for the Home dialog. */
data class ImportStatus(
    val running: Boolean = false,
    val tripId: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    /** Name of the file whose trip already exists: the UI must ask replace / copy. */
    val conflictFile: String? = null,
) {
    val idle: Boolean get() = !running && tripId == null && error == null && conflictFile == null

    companion object {
        fun from(info: WorkInfo?): ImportStatus {
            if (info == null) return ImportStatus()
            return when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> ImportStatus(running = true)
                WorkInfo.State.SUCCEEDED -> ImportStatus(tripId = info.outputData.getString(ImportWorker.KEY_TRIP_ID), warnings = info.outputData.getString(ImportWorker.KEY_WARNINGS).orEmpty().lines().filter { it.isNotBlank() })
                WorkInfo.State.FAILED -> ImportStatus(error = info.outputData.getString(ImportWorker.KEY_ERROR), conflictFile = info.outputData.getString(ImportWorker.KEY_CONFLICT))
                WorkInfo.State.CANCELLED -> ImportStatus()
            }
        }
    }
}
