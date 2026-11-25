# F1 — Corrección urgente: cierre del proceso al guardar puntos (carrera en `flush`)

Fecha: 2026-11-24, durante el viaje real iniciado el 2026-11-20. Rama `fix/flush-race`.

## Síntoma reportado
"No está registrando bien. Puse pausa y no puedo volver a habilitar." El usuario estaba
en la pantalla de inicio con tres accesos ("Registro en curso · Volver a la sesión", la
tarjeta del viaje y "Seguir registrando") y no sabía dónde mirar.

## Qué se leyó en el teléfono (Pixel 8 Pro, por depuración inalámbrica)
- `logcat` (búfer de cierres): **7 cierres forzados** del proceso entre el 20/11 y el 24/11,
  todos con la misma traza: `IndexOutOfBoundsException: toIndex = N` en
  `TrackingService.flush` (línea `buffer.subList(0, batch.size).clear()`), hilo
  `DefaultDispatcher-worker`, contexto `Dispatchers.Default.limitedParallelism(1)`.
  Horas: 20/11 10:32, 18:48, 21:54 · 21/11 09:40, 23:47 · 24/11 14:59, 17:37.
- Los dos cierres del 24/11 coinciden al segundo con una salida de reposo automático
  (`ExitRest`) registrada en `segment_pause`: al retomar el GPS llega una ráfaga de puntos y
  el `flush` por tamaño se solapa con el `flush` del ticker (o con el de "Pausar").
- Tras cada cierre, Android relanzó el servicio (`START_STICKY`) con demora: el del 17:37 volvió
  a las 18:08. Entre medio no se graba nada.
- `dumpsys activity services`: `createTime=-4d12h` (el registro del servicio existe desde el inicio
  del viaje, no hubo reinicio del teléfono); `restartTime=-2h20m`.

## Causa
`flush()` suspende dentro del `insertPoints`. El despachador de un hilo sigue ejecutando otras
corrutinas del pipeline mientras tanto (ticker, pausa, más puntos). Dos `flush` copiaban la
misma cabeza de la lista; el primero la borraba y el segundo hacía `subList(0, n)` sobre una
lista ya vacía. El comentario de concurrencia del servicio asumía exclusión mutua que el
despachador de un hilo no da entre puntos de suspensión.

## Corrección
`PointBuffer<T>` (app/tracking): lista + `Mutex`; `flush` es exclusivo, el que llega tarde
encuentra la lista vacía y vuelve. Se conserva I2 (el insert es `NonCancellable`, la lista se
vacía sólo tras el éxito). `TrackingService` usa el búfer en lugar de `ArrayList` + `lastFlushMs`.
Pruebas JVM (`PointBufferTest`): dos `flush` solapados escriben cada punto una vez y no lanzan;
un insert fallido conserva los puntos; búfer vacío es no-op.

## Estado de los datos del viaje (base `bitacora.db` copiada con `run-as`, sólo lectura)
- Viaje activo "Patagonia nov 2026": etapas 20/11 (7102 puntos), 21/11 (6744), 22/11 (**2 puntos**,
  00:00–00:05, estado PAUSED por reposo), y una **excursión a pie** iniciada el 24/11 09:41
  (1467 puntos, **1353 descartados por el filtro de caminata**: velocidades de 20–28 m/s con
  precisión de 2–7 m; recorrido real ≈560 km en auto, Camarones → Puerto San Julián).
- **22/11 y 23/11 sin registro**: el servicio murió el 22/11 00:05 sin traza de cierre y nadie lo
  relanzó hasta la excursión del 24/11. No recuperable desde la app.
- No hay pausas de usuario (`kind=USER`) en todo el viaje: el "Pausar" del 24/11 cayó en el
  cierre de las 17:37 antes de persistirse.

## Instalación
- APK de `fix/flush-race` instalado con `adb install -r` el 2026-11-24 20:41 (hora del teléfono),
  datos conservados. El usuario tocó "Reanudar" en el diálogo de recuperación y cambió el transporte
  a Auto. Verificado: `tracking started segment=a82edfd2… profile=WALK` 20:42:34,
  `transport changed to CAR profile=VEHICLE` 20:42:52, pedido de ubicación activo cada 10 s.

## Pendientes que deja (anotados en PLAN.md)
- D6 Inicio con una sesión abierta: un solo acceso claro ("Ver grabación en curso"), ocultar
  "Seguir registrando"; nota de voz visible desde el inicio.
- D7 Recuperación: una etapa en reposo automático se restaura como *pausa manual* (exige dos
  toques); debe restaurarse como reposo. Y la pantalla de sesión debe relanzar el servicio si la
  etapa está abierta sin servicio (deuda de D4).
- D8 Reprocesar una etapa con otro transporte (volver a pasar el filtro sobre los puntos guardados):
  aplicar a la excursión del 24/11 para recuperar los 1353 puntos descartados.
- D9 Vigía del servicio: comprobación periódica (WorkManager) que relance el servicio si hay
  etapa abierta sin servicio, y avise; el hueco del 22–23/11 no habría pasado inadvertido.
