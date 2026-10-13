// JNI bridge for Bitácora de Viaje: load a ggml model, transcribe 16 kHz mono float PCM
// with optional Silero VAD, return segments with timestamps and a confidence estimate.
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include "whisper.h"

#define TAG "Bitacora"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_initContext(JNIEnv *env, jclass clazz, jstring model_path) {
    UNUSED(clazz);
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == NULL) LOGW("whisper: cannot load model %s", path);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_freeContext(JNIEnv *env, jclass clazz, jlong ptr) {
    UNUSED(env); UNUSED(clazz);
    if (ptr != 0) whisper_free((struct whisper_context *) ptr);
}

/** Returns 0 on success, whisper_full's error code otherwise. */
JNIEXPORT jint JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_fullTranscribe(JNIEnv *env, jclass clazz, jlong ptr, jint n_threads,
                                                          jstring language, jstring vad_model_path, jfloatArray audio) {
    UNUSED(clazz);
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx == NULL) return -100;
    if (audio == NULL) return -101;
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (samples == NULL) return -102; // OOM
    const jsize n_samples = (*env)->GetArrayLength(env, audio);
    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    const char *vad = vad_model_path ? (*env)->GetStringUTFChars(env, vad_model_path, NULL) : NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.n_threads = n_threads;
    if (lang != NULL && strcmp(lang, "auto") == 0) {
        params.language = NULL;
        params.detect_language = true;
    } else {
        params.language = lang;
        params.detect_language = false;
    }
    if (vad != NULL) {
        // Mandatory VAD (PLAN 3.4): silence is skipped instead of hallucinated into text.
        params.vad = true;
        params.vad_model_path = vad;
        params.vad_params = whisper_vad_default_params();
        params.vad_params.threshold = 0.5f;
        params.vad_params.min_speech_duration_ms = 250;
        params.vad_params.min_silence_duration_ms = 100;
        params.vad_params.speech_pad_ms = 30;
    }
    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, samples, n_samples);
    if (rc != 0) LOGW("whisper_full failed rc=%d", rc);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (vad) (*env)->ReleaseStringUTFChars(env, vad_model_path, vad);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentCount(JNIEnv *env, jclass clazz, jlong ptr) {
    UNUSED(env); UNUSED(clazz);
    if (ptr == 0) return 0;
    return whisper_full_n_segments((struct whisper_context *) ptr);
}

JNIEXPORT jstring JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentText(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_full_get_segment_text((struct whisper_context *) ptr, i));
}

JNIEXPORT jlong JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentStartMs(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_get_segment_t0((struct whisper_context *) ptr, i) * 10; // centiseconds → ms
}

JNIEXPORT jlong JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentEndMs(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_get_segment_t1((struct whisper_context *) ptr, i) * 10;
}

/** Mean token probability of the segment (0..1); the per-segment confidence of PLAN 3.4. */
JNIEXPORT jfloat JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentConfidence(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    UNUSED(env); UNUSED(clazz);
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    int n = whisper_full_n_tokens(ctx, i);
    if (n <= 0) return 0.0f;
    double sum = 0.0;
    int counted = 0;
    for (int t = 0; t < n; t++) {
        whisper_token_data td = whisper_full_get_token_data(ctx, i, t);
        if (td.id >= whisper_token_eot(ctx)) continue; // skip special tokens
        sum += td.p;
        counted++;
    }
    return counted > 0 ? (jfloat) (sum / counted) : 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_segmentNoSpeechProb(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_get_segment_no_speech_prob((struct whisper_context *) ptr, i);
}

JNIEXPORT jstring JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_detectedLanguage(JNIEnv *env, jclass clazz, jlong ptr) {
    UNUSED(clazz);
    int id = whisper_full_lang_id((struct whisper_context *) ptr);
    const char *s = id >= 0 ? whisper_lang_str(id) : "";
    return (*env)->NewStringUTF(env, s);
}

JNIEXPORT jstring JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_systemInfo(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_bitacoraviaje_whisper_WhisperLib_version(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_version());
}
