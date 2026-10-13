package com.bitacoraviaje.app.voice.models

/**
 * Downloadable whisper.cpp models (DISENO §8.3): never shipped in the APK. Hashes are the
 * SHA-256 of the files served by Hugging Face on 2026-10-13 (base and VAD computed on the
 * downloaded files; small from the LFS etag). A mismatch after download is rejected.
 */
enum class WhisperModel(val id: String, val fileName: String, val url: String, val sha256: String, val sizeBytes: Long, val labelEs: String) {
    BASE(
        "base", "ggml-base.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", 147_951_465L, "base (148 MB, rápido)",
    ),
    SMALL(
        "small", "ggml-small.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", 487_601_967L, "small (488 MB, más preciso)",
    ),
    ;

    companion object {
        /** Silero VAD for whisper.cpp ≥ 1.7.6; mandatory so silence is skipped instead of hallucinated (PLAN 3.4). */
        const val VAD_FILE = "ggml-silero-v5.1.2.bin"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
        const val VAD_SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
        const val VAD_SIZE = 885_098L

        fun byId(id: String?): WhisperModel = entries.firstOrNull { it.id == id } ?: BASE
    }
}
