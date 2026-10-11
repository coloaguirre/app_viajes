---
name: location-tracking
description: Implement GPS trip recording in Bitácora de Viaje — foreground service of type location, FusedLocationProviderClient, sampling profiles, batching to Room, pause/resume, notification actions, crash/restart recovery, battery handling. Use for anything in the tracking package or the active-session flow.
---

# Registro del recorrido

Especificación: `docs/DISENO.md` §6. Los algoritmos (filtros, métricas, paradas) están en la skill `geo-algorithms`.

## Manifiesto
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<!-- NO declarar ACCESS_BACKGROUND_LOCATION en v1 -->

<service
    android:name=".tracking.TrackingService"
    android:exported="false"
    android:foregroundServiceType="location"/>
```

## Iniciar el servicio (solo con la app visible)
```kotlin
fun startTracking(context: Context, segmentId: String, profile: SamplingProfile) {
    val i = Intent(context, TrackingService::class.java)
        .setAction(TrackingService.ACTION_START)
        .putExtra(EXTRA_SEGMENT_ID, segmentId)
        .putExtra(EXTRA_PROFILE, profile.name)
    ContextCompat.startForegroundService(context, i)
}
```
Verificar **antes** que los permisos de ubicación (fina o aproximada) y de notificaciones están concedidos. Sin permiso de ubicación, `startForeground` con tipo `location` lanza `SecurityException` en Android 14+.

## Esqueleto del servicio
```kotlin
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var fused: FusedLocationProviderClient
    @Inject lateinit var repo: TrackRepository
    @Inject lateinit var state: TrackingStateHolder
    @Inject lateinit var notifications: TrackingNotificationFactory

    private val buffer = mutableListOf<TrackPoint>()
    private var lastFlushMs = 0L
    private val filter = TrackPointFilter()          // core:geo

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lifecycleScope.launch { result.locations.forEach { onLocation(it) } }
        }
        override fun onLocationAvailability(a: LocationAvailability) {
            state.update { it.copy(gpsAvailable = a.isLocationAvailable) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            null -> recoverFromRestart()   // reinicio por START_STICKY: intent == null
        }
        return START_STICKY
    }

    private fun start(intent: Intent) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notifications.build(state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        requestUpdates(SamplingProfile.valueOf(intent.getStringExtra(EXTRA_PROFILE)!!))
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(p: SamplingProfile) {
        val req = LocationRequest.Builder(p.priority, p.intervalMs)
            .setMinUpdateIntervalMillis(p.minIntervalMs)
            .setMinUpdateDistanceMeters(p.minDistanceM)
            .setMaxUpdateDelayMillis(p.maxDelayMs)
            .setWaitForAccurateLocation(p == SamplingProfile.TREKKING_HIGH)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private suspend fun onLocation(l: Location) {
        val point = l.toTrackPoint(segmentId)           // incluye isMock (API 31+) y alt MSL (API 34+)
        val verdict = filter.accept(point)               // core:geo
        buffer += point.copy(isFilteredOut = !verdict.accepted)
        if (verdict.accepted) state.update { it.withPoint(point, verdict.addedDistanceM) }
        if (buffer.size >= 10 || nowMs() - lastFlushMs > 30_000) flush()
        notifications.update(state.value)                // no más de 1 vez cada 5 s
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return
        repo.insertPoints(buffer.toList()); buffer.clear(); lastFlushMs = nowMs()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        runBlocking { flush() }   // único lugar donde se acepta runBlocking: no perder puntos
        super.onDestroy()
    }
}
```

## Perfiles (`core:model`)
Ver la tabla de `DISENO.md` §6.2. En `core:model` se usa un entero para la prioridad, que se mapea a `Priority.PRIORITY_HIGH_ACCURACY` / `PRIORITY_BALANCED_POWER_ACCURACY` en `:app`.

## Notificación
- Canal `tracking`, importancia **baja** (no suena), `setOngoing(true)`, `setOnlyAlertOnce(true)`, categoría `CATEGORY_SERVICE`.
- Contenido: "Trekking · 2 h 14 min · 8,3 km".
- Acciones (`PendingIntent` con `FLAG_IMMUTABLE`):
  - **Pausar/Reanudar** → `PendingIntent.getService` con la acción correspondiente.
  - **Nota de voz** → `PendingIntent.getActivity` hacia `VoiceNoteActivity` (no servicio: el micrófono requiere primer plano; ver skill `voice-journal`).
  - **Terminar** → abre la app en la pantalla "Terminar etapa" para confirmar (no termina directo).
- Al tocar la notificación se abre la sesión activa.
- Actualizar como máximo cada 5 s (evitar el rate-limit del sistema).

## Pausa y reanudación
- Pausa: `removeLocationUpdates`, `flush()`, registrar `segment_pause`, notificación "En pausa". **El servicio sigue en primer plano** (si se detiene, no se puede reiniciar desde la notificación en segundo plano).
- Reanudar: `requestUpdates`, cerrar `segment_pause`. **El primer punto después de reanudar no suma distancia** desde el último punto previo a la pausa: es un nuevo `<trkseg>`.

## Excursión anidada
Al iniciar una excursión con una etapa activa: pausar la etapa padre (con motivo `NESTED`) y crear la excursión con `parent_segment_id` apuntando a la etapa padre. Al terminar la excursión, ofrecer reanudar la etapa padre.

## Recuperación
- `recoverFromRestart()`: leer de la base la etapa con `status = ACTIVE`. Si existe, reanudar con su perfil; si no, `stopSelf()`.
- Al abrir la app: si hay una etapa `ACTIVE` y el servicio no corre (comprobarlo con un flag en el `TrackingStateHolder` que el servicio actualiza), mostrar un diálogo con **Reanudar** / **Cerrar etapa**.
- Después de un reinicio del teléfono **no** se inicia el servicio automáticamente: un `BroadcastReceiver` de `BOOT_COMPLETED` solo publica una notificación "Tocá para reanudar tu registro".

## Batería
- Registrar `ACTION_BATTERY_CHANGED` (sticky) en el servicio. Con nivel < 15 % y sin cargar, cambiar a `SAVER` y avisar.
- Pantalla de ayuda por fabricante (Xiaomi, Samsung, Huawei, OnePlus, Oppo) con instrucciones e intent a `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.

## Estado en vivo
```kotlin
data class LiveTrackingState(
    val segmentId: String? = null,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val startedUtcMs: Long = 0,
    val pausedAccumMs: Long = 0,
    val distanceM: Double = 0.0,
    val lastPoint: TrackPoint? = null,
    val currentSpeedMps: Double? = null,
    val gpsAvailable: Boolean = true,
    val profile: SamplingProfile = SamplingProfile.WALK,
)
```

## Pruebas
- Unitarias: perfiles → `LocationRequest` (en `:app` con Robolectric), lógica de buffer y flush con un reloj falso.
- E2E: emulador + `tools/gpx-replay/replay.py` (skill `android-testing`). Verificar distancia, cantidad de puntos y paradas.
- Matar el proceso (`adb shell am kill com.bitacoraviaje.app`) en medio del recorrido y verificar la recuperación.
- **Dispositivo real:** caminata de 1 h, pantalla apagada, medir batería.
