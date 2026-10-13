package com.bitacoraviaje.app.voice.models

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Where models live (`filesDir/models/`) and how they are verified. Pure file logic, testable on the JVM. */
class ModelStore(private val dir: File) {
    fun file(model: WhisperModel): File = File(dir, model.fileName)

    fun partFile(model: WhisperModel): File = File(dir, model.fileName + ".part")

    fun vadFile(): File = File(dir, WhisperModel.VAD_FILE)

    fun vadPartFile(): File = File(dir, WhisperModel.VAD_FILE + ".part")

    fun isInstalled(model: WhisperModel): Boolean = file(model).let { it.isFile && it.length() == model.sizeBytes }

    fun isVadInstalled(): Boolean = vadFile().let { it.isFile && it.length() == WhisperModel.VAD_SIZE }

    fun installed(): List<WhisperModel> = WhisperModel.entries.filter(::isInstalled)

    fun delete(model: WhisperModel) {
        file(model).delete()
        partFile(model).delete()
    }

    fun ensureDir() {
        dir.mkdirs()
    }

    companion object {
        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Verified part file becomes the final file; a bad hash deletes the download so the next run starts clean. */
        fun commitVerified(part: File, target: File, expectedSha256: String): Boolean {
            if (!part.isFile) return false
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                return false
            }
            target.delete()
            return part.renameTo(target)
        }
    }
}
