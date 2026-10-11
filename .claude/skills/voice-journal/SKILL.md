---
name: voice-journal
description: Voice notes for Bitácora de Viaje — recording with MediaRecorder, launching recording from the notification/lock screen/Quick Settings via a translucent activity, attaching location, and transcription through a pluggable Transcriber (whisper.cpp on-device via NDK, Android SpeechRecognizer, optional cloud), plus text notes and markers. Use for the voice package, VoiceNoteActivity, TranscribeWorker or whisper integration.
---

# Bitácora por voz

Especificación: `docs/DISENO.md` §8.

## Permisos
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
```
Se pide **al tocar la primera nota de voz**, con una explicación previa.

## Grabación
```kotlin
class VoiceRecorder @Inject constructor(@ApplicationContext private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    fun start(out: File) {
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)   // menos procesamiento, mejor para STT
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1); setAudioSamplingRate(16_000); setAudioEncodingBitRate(32_000)
            setMaxDuration(5 * 60_000)
            setOutputFile(out); prepare(); start()
        }
    }
    fun stop(): Boolean = try { recorder?.stop(); true } catch (e: RuntimeException) { false /* nota demasiado corta */ }
        finally { recorder?.release(); recorder = null }
}
```
- Archivos en `filesDir/audio/<tripId>/<voiceNoteId>.m4a`.
- Si `stop()` falla (menos de ~0,5 s), descartar la nota sin error visible.
- Verificar el espacio libre antes de grabar (mínimo 20 MB).
- Al empezar: tomar `TrackingStateHolder.lastPoint`; si tiene más de 30 s, pedir `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` con un tiempo máximo de 5 s.
- Pedir el foco de audio (`AudioFocusRequest` transitorio) para pausar la música mientras se graba.
- Vibración corta al empezar y doble al terminar.

## Grabar desde la notificación o la pantalla bloqueada
Un servicio iniciado en segundo plano **no puede usar el micrófono** (restricción *while-in-use* desde Android 11). Solución:
```xml
<activity
    android:name=".voice.VoiceNoteActivity"
    android:theme="@style/Theme.Bitacora.Translucent"
    android:excludeFromRecents="true"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:launchMode="singleTask"
    android:exported="false"/>
```
- Muestra una burbuja con un medidor de nivel, un temporizador y los botones **Listo** / **Cancelar**. Graba al abrirse.
- Se abre desde: la acción de la notificación (`PendingIntent.getActivity`), un `TileService` (Configuración rápida; en API 34+ usar `startActivityAndCollapse(PendingIntent)`), el widget y el acceso directo de la app.
- Al terminar: guardar en la base, encolar la transcripción y cerrarse (`finishAndRemoveTask()`).

Alternativa sin actividad (no recomendada en v1): agregar `microphone` a `foregroundServiceType` y `FOREGROUND_SERVICE_MICROPHONE`; solo funciona si el servicio pasó a primer plano mientras la app estaba visible.

## Interfaz de transcripción
```kotlin
interface Transcriber {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun transcribe(audio: File, languageTag: String): TranscriptResult
}
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)
data class TranscriptResult(val text: String, val segments: List<TranscriptSegment>, val language: String?)
```
Selección: el motor elegido en Ajustes; si no está disponible, el siguiente en el orden `whisper-local` → `android-speech` → `cloud` (solo si el usuario lo habilitó).

## TranscribeWorker
- `@HiltWorker`, nombre único `transcribe-<id>`, `ExistingWorkPolicy.KEEP`.
- Restricciones para Whisper local: `setRequiresBatteryNotLow(true)`; opcional `setRequiresCharging` (Ajustes: "Transcribir solo cargando").
- Para la nube: `NetworkType.CONNECTED`.
- Estados: `PENDING → RUNNING → DONE | FAILED`; reintento con backoff exponencial (máximo 3); error visible con un botón "Reintentar".

## whisper.cpp en el dispositivo (fase 3)
1. Agregar whisper.cpp como submódulo de git en `third_party/whisper.cpp` (fijar un tag de release).
2. Basarse en el ejemplo oficial para Android del repositorio (`examples/whisper.android`). Revisar su estructura vigente: incluye la lib JNI y el `CMakeLists.txt`.
3. Módulo `:whisper` (Android library) con `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }`, ABIs `arm64-v8a` (y `x86_64` para el emulador).
4. Compilar con optimización (`-O3`) incluso en debug; en debug sin optimizar, Whisper es inutilizablemente lento.
5. API Kotlin mínima:
   ```kotlin
   class WhisperContext private constructor(private val ptr: Long) : Closeable {
       companion object { fun load(modelPath: String): WhisperContext }
       fun transcribe(pcm16kMonoFloat: FloatArray, lang: String, threads: Int): List<TranscriptSegment>
       override fun close()
   }
   ```
6. **Decodificar M4A → PCM float 16 kHz mono** con `MediaExtractor` + `MediaCodec`. Si la tasa de muestreo no es 16 kHz, resamplear (lineal alcanza para voz).
7. Modelos (formato ggml): **`base`** por defecto (~150 MB), `small` opcional (~470 MB). **No incluirlos en el APK**: descarga desde Ajustes a `filesDir/models/` con progreso, verificación de SHA-256 y reanudación. Verificar las URLs y los hashes oficiales al implementar.
8. Hilos: `min(4, Runtime.availableProcessors())`.
9. Idioma `es` fijo por defecto (más rápido y preciso que la detección automática); opción "auto".
10. Prueba: un WAV de prueba en español en `src/androidTest/assets/` y verificar que el texto contiene las palabras clave esperadas.

## AndroidSpeechTranscriber (vista previa en vivo)
- `SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)` (API 31+) → `createOnDeviceSpeechRecognizer`; si no, `createSpeechRecognizer`.
- `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` con `EXTRA_LANGUAGE = "es-AR"`, `EXTRA_PARTIAL_RESULTS = true` y `EXTRA_PREFER_OFFLINE = true`.
- Limitación: escucha el micrófono **en vivo**; no transcribe archivos (verificar en la versión de Android de destino si hay fuente de audio externa). Puede competir con `MediaRecorder` por el micrófono: **no usar los dos a la vez** salvo que se verifique en el dispositivo. Útil como modo "dictado rápido" sin conservar el audio.

## Transcripción en la nube (opcional)
- Implementación configurable con la clave del usuario (guardada cifrada; skill `play-policy-privacy`).
- Enviar solo el audio, sin coordenadas ni metadatos.
- Avisar claramente en Ajustes que el audio sale del teléfono.

## Edición
- Pantalla de detalle: reproductor (ExoPlayer de Media3 o `MediaPlayer`), texto editable y mapa con el punto.
- Guardar las correcciones en `transcript_edited`; mostrar siempre `transcript_edited ?: transcript`.

## Notas de texto, marcadores y destacados
- **Marcar:** diálogo con texto opcional; guarda `text_note(kind = MARKER)` con la posición actual.
- **Destacar:** un toque; `kind = HIGHLIGHT`, con vibración y un snackbar "Momento destacado". El planificador del video los prioriza.
