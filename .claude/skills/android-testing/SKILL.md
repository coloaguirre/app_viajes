---
name: android-testing
description: Testing strategy and tooling for Bitácora de Viaje — JVM unit tests for core modules, Room/Worker/Robolectric tests, Compose UI tests, end-to-end on the emulator with GPX replay via adb geo fix, synthetic photo fixtures with EXIF, injecting media and audio, screenshots and UI dumps for verification, and the manual real-device checklist. Use whenever writing tests, verifying a phase's acceptance criteria, or debugging on the emulator.
---

# Pruebas

Especificación: `docs/DISENO.md` §17. **Una fase no está terminada sin su evidencia de aceptación** (salida de pruebas + capturas).

## Niveles y comandos
| Nivel | Dónde | Comando |
|---|---|---|
| Unitarias JVM | `core/*/src/test` | `./gradlew :core:geo:test` (etc.) |
| Unitarias Android (Robolectric) | `app/src/test`, `data/src/test` | `./gradlew :app:testDebugUnitTest` |
| Room / migraciones | `data/src/androidTest` | `./gradlew :data:connectedDebugAndroidTest` |
| UI Compose | `app/src/androidTest` | `./gradlew :app:connectedDebugAndroidTest` |
| E2E en el emulador | scripts en `tools/` | ver abajo |
| Renderizador | `desktop-renderer/tests` | `pytest -q` |

Librerías: JUnit, Truth (o kotest-assertions), Turbine (Flows), MockK, kotlinx-coroutines-test (`runTest`, `StandardTestDispatcher`), Robolectric, `room-testing` (`MigrationTestHelper`), `work-testing` (`TestListenableWorkerBuilder`, `WorkManagerTestInitHelper`), Compose UI test (`createAndroidComposeRule`), MockWebServer.

## Reglas
- Inyectar `Clock` y `CoroutineDispatcher` para no depender del tiempo real.
- Nada de `Thread.sleep` en las pruebas.
- Cada bug corregido trae su prueba de regresión.
- Fixtures en `src/test/resources/` o `tools/sample-trips/`; nunca datos personales reales del usuario.

## Viajes de ejemplo (`tools/sample-trips/`)
Crear con un script (`tools/sample-trips/generate.py`) para que sean reproducibles:
- `trekking/track.gpx`: ida y vuelta de ~8 km con ~700 m de desnivel, un punto cada 3 s, ruido de 5 m, una parada de 15 min en la cima.
- `ruta-auto/track.gpx`: 120 km a ~90 km/h con una parada de 30 min (estación de servicio) y un túnel (hueco de 2 min sin puntos).
- `ciudad/track.gpx`: 3 h a pie con 3 paradas (café, museo, plaza).
- `ciudad/photos/`: 20 JPEG sintéticos (`make_photos.py`, Pillow + piexif) que cubren todos los casos de vinculación: con GPS y hora con offset; sin GPS; hora local sin offset; cámara con desfase de +1 h (`Make=TestCam`); una ráfaga de 5 casi idénticas (para pHash); una movida (desenfoque, para nitidez); una fuera del rango de la etapa.
- Resultado esperado para cada uno en `expected.json` (distancia, paradas, vinculación de cada foto).

## Reproducir un GPX en el emulador (`tools/gpx-replay/replay.py`)
```python
# uso: python replay.py track.gpx [--speed 20] [--serial emulator-5554]
# Lee los <trkpt>, y por cada uno ejecuta:  adb -s SERIAL emu geo fix LON LAT ALT
# esperando (t[i+1]-t[i])/speed segundos. Imprime el progreso.
```
- `geo fix` recibe **longitud primero**.
- `adb emu` habla con la consola del emulador (requiere el token de autenticación `~/.emulator_console_auth_token`; si falla, vaciar ese archivo).
- Con `--speed` alto, los perfiles con `minUpdateIntervalMillis` pueden descartar puntos: para las pruebas de distancia, usar un perfil de prueba sin mínimo de intervalo o un `speed` moderado (≤ 10).

## Inyectar fotos en el emulador
```bash
adb push tools/sample-trips/ciudad/photos/. /sdcard/DCIM/Camera/
# Forzar el escaneo de medios (según la versión de Android, una de estas):
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/IMG_0001.jpg
```
Verificar con `adb shell content query --uri content://media/external/images/media --projection _id:_display_name:datetaken`.
**Ojo:** `DATE_TAKEN` lo toma MediaStore del EXIF al escanear; si el EXIF no tiene hora, usa la del archivo. `adb push` conserva el mtime con `-a`.

## Audio en las pruebas
- Sin micrófono en el emulador: probar `VoiceRecorder` en un dispositivo real, y la transcripción con un archivo de prueba (`src/androidTest/assets/prueba_es.wav`, generado una vez con una voz sintética o grabado por el usuario).
- Para la UI: un `FakeVoiceRecorder` inyectado con Hilt (`@TestInstallIn`) que "graba" copiando el archivo de prueba.

## Permisos en las pruebas
- UI tests: `GrantPermissionRule.grant(...)`.
- E2E: `adb shell pm grant com.bitacoraviaje.app android.permission.ACCESS_FINE_LOCATION` (y los de medios, ubicación de medios, notificaciones, micrófono).
- Probar también el camino de **permiso denegado** (`pm revoke`).

## Verificación visual
```bash
adb exec-out screencap -p > /tmp/fase1_sesion_activa.png
```
Después, leer la imagen y describir lo que se ve: que el mapa muestra el recorrido, que las cifras son razonables, que no hay textos cortados. Guardar las capturas de aceptación en `docs/evidence/faseN/` (sin datos personales).
`adb shell uiautomator dump` sirve para encontrar las coordenadas de los botones y verificar textos.

## Escenarios E2E por fase
- **Fase 1:** iniciar trekking → reproducir `trekking/track.gpx` → pausar 1 min → reanudar → terminar. Esperado: distancia ±2 %, desnivel ±10 %, 1 parada, dos `<trkseg>`. Variante: `am kill` a mitad del recorrido → reabrir → Reanudar.
- **Fase 2:** sesión `ciudad` + fotos inyectadas en el momento correspondiente → terminar → revisión: cada foto con el `link_method` esperado según `expected.json`.
- **Fase 3:** exportar → `adb pull` del ZIP → validar en el host: GPX contra el XSD (`xmllint --schema gpx.xsd`), JSON contra el esquema, HTML que abre (verificación con un navegador headless opcional).
- **Fase 4:** `bitacora-render` sobre el export de la fase 3 → `ffprobe`.

## Checklist en dispositivo real (lo hace el usuario; Claude Code prepara la versión y la lista)
- [ ] Caminata de 1 h, pantalla apagada, teléfono en el bolsillo: recorrido continuo, batería consumida: ___ %.
- [ ] Viaje en auto de 30 min: velocidad y distancia razonables.
- [ ] 10 fotos con la cámara del sistema: aparecen en la línea de tiempo en el lugar correcto.
- [ ] Nota de voz desde la pantalla bloqueada (acción de la notificación).
- [ ] Nota de voz transcripta correctamente (Whisper).
- [ ] Reinicio del teléfono durante una sesión: aparece la notificación para reanudar.
- [ ] Exportación y apertura en la PC (GPX en Google Earth, KML en My Maps, HTML en el navegador).
