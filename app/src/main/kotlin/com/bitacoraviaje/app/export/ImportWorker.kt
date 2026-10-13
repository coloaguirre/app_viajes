package com.bitacoraviaje.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.core.export.GpxReader
import com.bitacoraviaje.core.export.ImportException
import com.bitacoraviaje.core.export.Names
import com.bitacoraviaje.core.export.TripExport
import com.bitacoraviaje.core.export.TripJsonReader
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.InputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to do when the imported trip id already exists. */
enum class ImportConflict { ASK, REPLACE, COPY }

/**
 * Imports a package ZIP, a bare `trip.json` or an external GPX from a document URI
 * (DISENO §12.5). ZIP media land in `Pictures/BitacoraViaje/<viaje>/` through
 * MediaStore (API 29+), audio under the app's files; everything else goes to Room in
 * one transaction. A trip that already exists stops with [KEY_CONFLICT] unless the
 * caller chose to replace it or import a copy.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "sin archivo"))
        val conflict = ImportConflict.valueOf(inputData.getString(KEY_CONFLICT_POLICY) ?: ImportConflict.ASK.name)
        val name = displayName(uri)
        try {
            val kind = when {
                name.endsWith(".gpx", true) -> "gpx"
                name.endsWith(".json", true) -> "json"
                else -> "zip"
            }
            val warnings = ArrayList<String>()
            val tripId = when (kind) {
                "gpx" -> importGpx(uri, name, warnings)
                "json" -> importJson(open(uri).use { it.readBytes().toString(Charsets.UTF_8) }, conflict, emptyMap(), warnings) ?: return@withContext conflictResult(name)
                else -> importZip(uri, conflict, warnings) ?: return@withContext conflictResult(name)
            }
            Log.i(TAG, "import done kind=$kind trip=$tripId warnings=${warnings.size}")
            Result.success(workDataOf(KEY_TRIP_ID to tripId, KEY_WARNINGS to warnings.joinToString("\n")))
        } catch (e: ImportException) {
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "import failed: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun conflictResult(name: String) = Result.failure(workDataOf(KEY_CONFLICT to name))

    private fun open(uri: Uri): InputStream = appContext.contentResolver.openInputStream(uri) ?: throw ImportException("No se pudo abrir el archivo")

    private fun displayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }

    private suspend fun importGpx(uri: Uri, name: String, warnings: MutableList<String>): String {
        val r = open(uri).use { GpxReader.read(it, name, TimeZone.getDefault().id) }
        warnings += r.warnings
        persist(r.trip, replace = false)
        return r.trip.trip.id
    }

    /** Returns null when the trip exists and the policy is ASK. */
    private suspend fun importJson(text: String, conflict: ImportConflict, files: Map<String, String>, warnings: MutableList<String>): String? {
        val parsed = TripJsonReader.read(text, regenerateIds = conflict == ImportConflict.COPY)
        val exists = tracking.tripExists(parsed.trip.id)
        if (exists && conflict == ImportConflict.ASK) return null
        val withFiles = attachFiles(parsed, files, warnings)
        persist(withFiles, replace = exists && conflict == ImportConflict.REPLACE)
        return withFiles.trip.id
    }

    /**
     * Two passes over the ZIP: first `trip.json` (to know the trip and the conflict
     * answer), then the media and audio entries, copied as they stream by.
     */
    private suspend fun importZip(uri: Uri, conflict: ImportConflict, warnings: MutableList<String>): String? {
        var json: String? = null
        ZipInputStream(open(uri)).use { z ->
            generateSequence { z.nextEntry }.forEach { e -> if (e.name.substringAfter('/') == "trip.json" || e.name == "trip.json") json = z.readBytes().toString(Charsets.UTF_8) }
        }
        val text = json ?: throw ImportException("El ZIP no contiene trip.json")
        val parsed = TripJsonReader.read(text, regenerateIds = conflict == ImportConflict.COPY)
        val exists = tracking.tripExists(parsed.trip.id)
        if (exists && conflict == ImportConflict.ASK) return null
        val mediaByZipPath = parsed.mediaFiles.entries.associate { (id, path) -> path to id }
        val audioByZipPath = parsed.audioFiles.entries.associate { (id, path) -> path to id }
        val copied = HashMap<String, String>() // media id → content uri
        val audioCopied = HashSet<String>()
        val folder = Names.slug(parsed.trip.title)
        ZipInputStream(open(uri)).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                val rel = e.name.substringAfter('/')
                mediaByZipPath[rel]?.let { id ->
                    val item = parsed.allMedia.first { it.id == id }
                    saveMedia(z, item.displayName ?: rel.substringAfterLast('/'), item.mimeType ?: if (item.kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg", item.kind, folder)?.let { copied[id] = it.toString() }
                        ?: warnings.add("No se pudo guardar ${item.displayName ?: rel}")
                }
                audioByZipPath[rel]?.let { id ->
                    val note = parsed.allVoiceNotes.first { it.id == id }
                    val target = File(appContext.filesDir, note.audioPath)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { z.copyTo(it) }
                    audioCopied += id
                }
            }
        }
        val missingMedia = parsed.mediaFiles.keys - copied.keys
        if (missingMedia.isNotEmpty()) warnings += "${missingMedia.size} fotos o videos del paquete no se pudieron copiar."
        // The MediaStore id is what the linker uses to recognise a known file: without it the next
        // sweep would find the copies in Pictures/BitacoraViaje and link them again as new items.
        val withFiles = parsed.copy(
            segments = parsed.segments.map { se ->
                se.copy(media = se.media.map { m -> m.copy(contentUri = copied[m.id], mediaStoreId = copied[m.id]?.let { runCatching { android.content.ContentUris.parseId(it.toUri()) }.getOrNull() }) })
            },
        )
        persist(withFiles, replace = exists && conflict == ImportConflict.REPLACE)
        return withFiles.trip.id
    }

    private fun attachFiles(t: TripExport, files: Map<String, String>, warnings: MutableList<String>): TripExport {
        if (t.mediaFiles.isNotEmpty() && files.isEmpty()) warnings += "El trip.json referencia ${t.mediaFiles.size} archivos que no vienen con él; importá el ZIP completo para tener las fotos."
        return t
    }

    /** API 29+: MediaStore under Pictures/BitacoraViaje/<trip>/; older: skipped with a warning (would need legacy storage permission). */
    private fun saveMedia(ins: InputStream, name: String, mime: String, kind: MediaKind, folder: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = if (kind == MediaKind.VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dir = (if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/BitacoraViaje/$folder"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { ins.copyTo(it) } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private suspend fun persist(t: TripExport, replace: Boolean) {
        tracking.importTrip(
            trip = t.trip,
            segmentsToInsert = t.segments.map { it.segment },
            pointsBySegment = t.segments.associate { it.segment.id to it.points },
            pausesToInsert = t.segments.flatMap { it.pauses },
            stopsToInsert = t.segments.flatMap { it.stops },
            replace = replace,
        )
        media.upsertAll(t.allMedia)
        notes.addVoiceNotes(t.allVoiceNotes)
        notes.addTextNotes(t.allTextNotes)
    }

    companion object {
        private const val TAG = "Bitacora"
        const val WORK_NAME = "import"
        const val KEY_URI = "uri"
        const val KEY_CONFLICT_POLICY = "conflict_policy"
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_WARNINGS = "warnings"
        const val KEY_ERROR = "error"
        const val KEY_CONFLICT = "conflict"

        fun enqueue(context: Context, uri: Uri, conflict: ImportConflict = ImportConflict.ASK) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val req = OneTimeWorkRequestBuilder<ImportWorker>().setInputData(Data.Builder().putString(KEY_URI, uri.toString()).putString(KEY_CONFLICT_POLICY, conflict.name).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
