package com.bitacoraviaje.core.export

/**
 * Ids coming from files end up as path components (staging dir, backup file, audio
 * path). Only a conservative character set is accepted, never `.` or `..` (review B1).
 */
object SafeId {
    private val pattern = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun isSafe(id: String): Boolean = pattern.matches(id) && id != "." && id != ".."

    fun require(id: String, what: String = "id"): String {
        if (!isSafe(id)) throw ImportException("$what inválido: \"${id.take(40)}\"")
        return id
    }
}
