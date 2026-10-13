package com.bitacoraviaje.app.enrichment

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the model may use: the Wikipedia extract and literal Wikidata facts. Nothing else leaves the phone. */
data class RewriteRequest(val placeName: String, val sourceText: String, val facts: List<String> = emptyList())

/** DISENO §11.4: optional rewrite in diary tone, restricted to the source text. Null means "no usable rewrite". */
interface LlmRewriter {
    suspend fun rewrite(request: RewriteRequest, apiKey: String, model: String): String?
}

/**
 * Pure checks on a rewrite (skill place-enrichment): every number in the result must
 * appear in the source, the length is capped, and the sentinel `SIN_DATOS` is a refusal.
 */
object RewriteGuard {
    const val SENTINEL = "SIN_DATOS"
    const val MAX_WORDS = 70

    /** Thousands groups normalised (`3.406`, `3,406`, `3 406` → `3406`); decimals keep a dot (`2,5` → `2.5`). */
    fun numbers(text: String): Set<String> = Regex("\\d{1,3}(?:[.,\\u00a0 ]\\d{3})+(?!\\d)|\\d+(?:[.,]\\d+)?").findAll(text).map { m ->
        val v = m.value
        if (Regex("^\\d{1,3}(?:[.,\\u00a0 ]\\d{3})+$").matches(v)) v.replace(Regex("[.,\\u00a0 ]"), "") else v.replace(',', '.')
    }.toSet()

    fun isFaithful(result: String, source: String, facts: List<String> = emptyList()): Boolean {
        val allowed = numbers(source + " " + facts.joinToString(" "))
        return numbers(result).all { it in allowed } // exact after normalisation: "198" is not "1985" (review M1)
    }

    fun clean(raw: String): String? {
        val t = raw.trim().trim('"', '«', '»').trim()
        if (t.isEmpty() || t.startsWith(SENTINEL)) return null
        if (t.split(Regex("\\s+")).size > MAX_WORDS) return null
        return t
    }

    /** Validates a model answer against its sources; null when it must be discarded. */
    fun accept(raw: String, request: RewriteRequest): String? {
        val t = clean(raw) ?: return null
        return if (isFaithful(t, request.sourceText, request.facts)) t else null
    }

    fun prompt(request: RewriteRequest): String = buildString {
        appendLine("TEXTO FUENTE sobre «${request.placeName}»:")
        appendLine(request.sourceText.trim())
        if (request.facts.isNotEmpty()) {
            appendLine()
            appendLine("DATOS:")
            request.facts.forEach { appendLine("- $it") }
        }
    }

    const val SYSTEM = "Sos el redactor de una bitácora de viaje. Reescribí el TEXTO FUENTE en español rioplatense neutro, en 2 o 3 oraciones (máximo 60 palabras), en tono cálido. " +
        "Usá EXCLUSIVAMENTE la información del texto fuente y de los DATOS; no agregues datos, fechas, nombres ni cifras que no estén ahí. " +
        "Respondé solo con el texto reescrito, sin título ni comentarios. Si el texto fuente no tiene nada interesante, respondé exactamente: $SENTINEL"
}

/**
 * Claude Messages API client. Own OkHttp client (no cache, no shared interceptors): the key
 * travels only in `x-api-key`, and the body holds the Wikipedia text and facts, never
 * coordinates, photos or notes. [baseUrl] is overridable for tests.
 */
class ClaudeRewriter @Inject constructor() : LlmRewriter {
    var baseUrl: String = DEFAULT_BASE_URL
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun rewrite(request: RewriteRequest, apiKey: String, model: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "max_tokens" to JsonPrimitive(300),
                "temperature" to JsonPrimitive(0.3),
                "system" to JsonPrimitive(RewriteGuard.SYSTEM),
                "messages" to JsonArray(listOf(JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(RewriteGuard.prompt(request)))))),
            ),
        )
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/messages")
            .header("x-api-key", apiKey).header("anthropic-version", API_VERSION).header("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (resp.code == 401 || resp.code == 403) throw LlmAuthException("clave rechazada (HTTP ${resp.code})")
            if (!resp.isSuccessful) throw IOException("LLM HTTP ${resp.code}: ${errorMessage(text) ?: text.take(120)}")
            val answer = parseText(text) ?: return@use null
            RewriteGuard.accept(answer, request)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseText(body: String): String? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val parts = root["content"]?.jsonArray ?: return null
            val text = parts.mapNotNull { p -> p.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "text" }?.get("text")?.jsonPrimitive?.content }.joinToString("\n")
            return text.ifBlank { null }
        }

        fun errorMessage(body: String): String? = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
    }
}

class LlmAuthException(message: String) : IOException(message)
