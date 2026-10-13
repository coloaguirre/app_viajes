package com.bitacoraviaje.app.voice

/** What to do with the requested transcription language given what the on-device engine reports. */
sealed interface LanguageDecision {
    /** Recognize with this installed tag (the requested one, or another variant of the same language). */
    data class Use(val tag: String) : LanguageDecision

    /** A pack of that language is being downloaded: try again later. */
    data object Pending : LanguageDecision

    /** No pack installed, but this tag can be downloaded on device. */
    data class Download(val tag: String) : LanguageDecision

    /** The engine cannot do this language on device at all. */
    data object Unsupported : LanguageDecision
}

/** Pure selection over the three tag lists of `RecognitionSupport` (review I3): testable without Android. */
object OnDeviceLanguages {
    fun pick(requested: String, installed: List<String>, pending: List<String>, supported: List<String>): LanguageDecision {
        val language = requested.substringBefore('-').lowercase()
        fun sameLanguage(tag: String) = tag.substringBefore('-').lowercase() == language
        installed.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return LanguageDecision.Use(it) }
        installed.filter(::sameLanguage).sortedWith(variantOrder(language)).firstOrNull()?.let { return LanguageDecision.Use(it) }
        if (pending.any(::sameLanguage)) return LanguageDecision.Pending
        val downloadable = supported.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: supported.filter(::sameLanguage).sortedWith(variantOrder(language)).firstOrNull()
        return if (downloadable != null) LanguageDecision.Download(downloadable) else LanguageDecision.Unsupported
    }

    /**
     * Deterministic choice among variants of one language: the closest to the app's users
     * first (Latin American Spanish before European), then alphabetical for anything else.
     */
    private val preferredVariants = mapOf("es" to listOf("es-419", "es-US", "es-MX", "es-AR"), "pt" to listOf("pt-BR"))

    private fun variantOrder(language: String): Comparator<String> {
        val prefs = preferredVariants[language].orEmpty()
        return compareBy<String>({ prefs.indexOfFirst { p -> p.equals(it, ignoreCase = true) }.let { i -> if (i < 0) prefs.size else i } }, { it.lowercase() })
    }
}

/** The language pack is downloading or was just requested: the note stays pending and the worker waits. */
class LanguagePackPendingException(message: String) : TranscriptionException(message)
