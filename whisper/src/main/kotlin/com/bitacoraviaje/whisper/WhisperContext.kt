package com.bitacoraviaje.whisper

import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** One transcribed segment: times relative to the start of the audio, confidence 0..1. */
data class WhisperSegment(val startMs: Long, val endMs: Long, val text: String, val confidence: Float, val noSpeechProb: Float)

data class WhisperResult(val segments: List<WhisperSegment>, val language: String?)

/**
 * A loaded whisper model. whisper.cpp contexts are not thread-safe: every call goes
 * through one single-thread dispatcher. Create with [load]; [close] frees the native memory.
 */
class WhisperContext private constructor(private var ptr: Long) : Closeable {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()

    /**
     * @param pcm16kMono float samples in -1..1 at 16 kHz
     * @param language ISO 639-1 ("es") or "auto"
     * @param vadModelPath Silero VAD ggml model; null disables VAD (tests only)
     */
    suspend fun transcribe(pcm16kMono: FloatArray, language: String, threads: Int = preferredThreads(), vadModelPath: String? = null): WhisperResult = withContext(dispatcher) {
        check(ptr != 0L) { "whisper context closed" }
        val rc = WhisperLib.fullTranscribe(ptr, threads, language, vadModelPath, pcm16kMono)
        if (rc != 0) throw WhisperException("whisper_full failed ($rc)")
        val n = WhisperLib.segmentCount(ptr)
        val segments = (0 until n).map { i ->
            WhisperSegment(WhisperLib.segmentStartMs(ptr, i), WhisperLib.segmentEndMs(ptr, i), WhisperLib.segmentText(ptr, i).trim(), WhisperLib.segmentConfidence(ptr, i), WhisperLib.segmentNoSpeechProb(ptr, i))
        }.filter { it.text.isNotBlank() }
        WhisperResult(segments, WhisperLib.detectedLanguage(ptr).ifBlank { null })
    }

    /** Serialised with [transcribe] on the same single thread: no free while a decode runs (review M14). */
    override fun close() {
        val p = ptr
        ptr = 0
        if (p != 0L) kotlinx.coroutines.runBlocking(dispatcher) { WhisperLib.freeContext(p) }
        dispatcher.close()
    }

    companion object {
        fun load(model: File): WhisperContext {
            val p = WhisperLib.initContext(model.absolutePath)
            if (p == 0L) throw WhisperException("cannot load model ${model.name}")
            Log.i("Bitacora", "whisper ${WhisperLib.version()} loaded ${model.name} (${model.length() / 1_048_576} MB)")
            return WhisperContext(p)
        }

        /** min(4, cores): more threads than big cores slows whisper down on phones (skill voice-journal). */
        fun preferredThreads(): Int = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

        fun systemInfo(): String = WhisperLib.systemInfo()
    }
}

class WhisperException(message: String) : Exception(message)

internal object WhisperLib {
    init {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        val fp16 = abi == "arm64-v8a" && runCatching { File("/proc/cpuinfo").readText().contains("fphp") }.getOrDefault(false)
        val lib = if (fp16) "whisper_v8fp16_va" else "whisper"
        Log.i("Bitacora", "whisper native lib=$lib abi=$abi")
        System.loadLibrary(lib)
    }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun fullTranscribe(ptr: Long, threads: Int, language: String?, vadModelPath: String?, audio: FloatArray): Int
    @JvmStatic external fun segmentCount(ptr: Long): Int
    @JvmStatic external fun segmentText(ptr: Long, index: Int): String
    @JvmStatic external fun segmentStartMs(ptr: Long, index: Int): Long
    @JvmStatic external fun segmentEndMs(ptr: Long, index: Int): Long
    @JvmStatic external fun segmentConfidence(ptr: Long, index: Int): Float
    @JvmStatic external fun segmentNoSpeechProb(ptr: Long, index: Int): Float
    @JvmStatic external fun detectedLanguage(ptr: Long): String
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun version(): String
}
