# Registro de avance

> Este archivo lo mantiene Claude Code. Actualizarlo al terminar cada tarea relevante.

## Estado actual
- Fase: **1 — Recorrido continuo, en curso** (1.1 a 1.5 cerrados 2026-10-12; siguiente 1.6 UI de la fase con mapa — requiere clave de MapTiler).
- Última actualización: 2026-10-12

## Fases
| Fase | Estado | Evidencia de aceptación |
|---|---|---|
| 0 — Esqueleto | ✅ Cerrada 2026-10-12 | `docs/notes/evidencia/fase-0/`: 0.1-0.3-emulador.md (+capturas), 0.2-build-test-lint.md, 0.4-ci.md (PR #1 verde) |
| 1 — Recorrido | 🟨 En curso | 1.1: `docs/notes/evidencia/fase-1/1.1-room.md` · 1.2: `1.2-core-geo.md` · 1.3: `1.3-tracking.md` · 1.4: `1.4-continuidad.md` · 1.5: `1.5-recuperacion.md` · Requiere clave de MapTiler del usuario antes del ítem 1.6 |
| 2 — Fotos, voz y línea de tiempo | ⬜ Pendiente | |
| 3 — Exportación y transcripción local | ⬜ Pendiente | |
| 4 — Enriquecimiento y video de escritorio | ⬜ Pendiente | |
| 5 — Video en el dispositivo y extras | ⬜ Pendiente | |
| 6 — Pulido y publicación | ⬜ Pendiente | |

## Decisiones tomadas
| Fecha | Decisión | Motivo |
|---|---|---|
| 2026-10-11 | Nivel de calidad 2 (ver PLAN.md) | Perder un recorrido es irreversible |
| 2026-10-11 | D1: `com.bitacoraviaje.app`, nombre "Bitácora de Viaje" | Default de la spec, confirmado |
| 2026-10-11 | D2: MapLibre + MapTiler en v1; Google Maps futuro | Clave gratuita, mapas offline, uso en video |
| 2026-10-11 | D3: transcripción solo local; modelo se elige midiendo | Privacidad y uso sin red |
| 2026-10-11 | D4: uso personal (sideload); Fase 6 en pausa | Decisión del usuario |
| 2026-10-11 | Registro continuo del viaje con reposo automático y corte diario | Adenda 1 del usuario |
| 2026-10-11 | Modo Full / Económico elegido por el usuario | Adenda 1 del usuario |
| 2026-10-11 | Minivideo de 1 min en el teléfono adelantado a Fase 4 | Adenda 1 del usuario |
| 2026-10-11 | Teselas MapTiler en el video aceptadas para uso personal | Zona gris de términos; PMTiles si se publica |
| 2026-10-12 | Esquema v1 agrega sobre DISENO §5.2: `segment.recording_mode`, `segment.auto_created`, `segment_pause.kind` (USER/AUTO_REST/NESTED), `track_point.is_mock`; `stop.place_id` sin FK hasta que exista `place` (Fase 4) | Adenda 1 (viaje continuo, modo económico) y §16.2 (mock) |
| 2026-10-12 | Perfil ECONOMY con prioridad de alta precisión (GPS) e intervalo 30 s / 40 m / lote 60 s, no prioridad balanceada | Sin Wi-Fi ni celdas en montaña la prioridad balanceada no tiene de dónde ubicar; el ahorro viene del intervalo largo |
| 2026-10-12 | Reposo automático: entra por silencio (sin puntos durante N min) además de por puntos agrupados; umbral configurable en Ajustes (3/5/10/15 min, default 5) | El proveedor no entrega puntos sin movimiento mayor a la distancia mínima |
| 2026-10-12 | Perfil REST: alta precisión, 60 s / 40 m, sin agrupación | Con agrupación la salida del reposo se demoraba hasta 3 min |
| 2026-10-12 | Esquema v2: `track_point.monotonic_ms`; tiempo de cada punto re-derivado del reloj monótono ante saltos del reloj de pared (`ClockGuard`) | DISENO §16.2 cambio manual de hora |
| 2026-10-12 | El filtro se siembra con el último punto de la etapa al (re)iniciar el servicio | Un primer punto espurio tras un reinicio se aceptaba sin historia |
| 2026-10-12 | compileSdk 37 (targetSdk sigue en 36) | 18 librerías AndroidX vigentes (Navigation 2.10, Hilt Compose 1.4...) exigen API 37 |

## Versiones fijadas
| Componente | Versión | Verificada el |
|---|---|---|
| JDK (Temurin, user space `~/.local/opt/jdk17`) | 17.0.20.1 | 2026-10-11 |
| Android cmdline-tools | 22.0 (`commandlinetools-linux-15859902`) | 2026-10-11 |
| Android SDK platforms / build-tools | android-37.0 (compileSdk) + android-36 (emulador) / 36.0.0 | 2026-10-12 |
| Emulator system image | android-36 google_apis x86_64 (AVD `bitacora36`, 2 GB RAM) | 2026-10-11 |
| Gradle (wrapper) | 9.8.1 | 2026-10-11 |
| Android Gradle Plugin | 9.4.1 (Kotlin integrado; min Gradle 9.6, JDK 17) | 2026-10-11 |
| Kotlin | 2.3.21 (2.4.21 disponible; Hilt validado con 2.3.21) | 2026-10-11 |
| KSP | 2.3.12 | 2026-10-11 |
| Compose BOM | 2026.09.00 | 2026-10-11 |
| Hilt | 2.60.1 | 2026-10-11 |
| Room | 2.8.5 (plugin `androidx.room`, esquemas en `data/schemas/`) | 2026-10-11 |
| androidx.test core-ktx / runner | 1.7.0 / 1.7.0 | 2026-10-12 |
| Navigation Compose / Activity Compose / Lifecycle | 2.10.2 / 1.13.0 / 2.11.0 | 2026-10-11 |
| WorkManager / DataStore / core-ktx | 2.12.0 / 1.2.1 / 1.19.1 | 2026-10-11 |
| Coroutines / kotlinx-serialization | 1.11.0 / 1.11.0 | 2026-10-12 |
| Play Services Location / lifecycle-service | 21.4.0 / 2.11.0 | 2026-10-12 |
| hilt-navigation-compose | 1.4.0 | 2026-10-11 |
| JUnit / androidx.test.ext junit / Espresso / Robolectric | 4.13.2 / 1.3.0 / 3.7.0 / 4.17 | 2026-10-11 |
| ffmpeg (build estático, user space) | 7.0.2 | 2026-10-11 |
| Python / uv | 3.12.3 / 0.12.5 | 2026-10-11 |

Observaciones de la verificación (ítem 0.1):
- Existen SDK 37.0, 37.1 y 37.2 (Android 17 y sus versiones menores) e
  imágenes de emulador con páginas de 16 KB (`google_apis_ps16k`). El
  PLAN preveía compileSdk 36, pero las librerías AndroidX vigentes exigen
  API 37: se compila contra 37.0 y targetSdk queda en 36 (decisión del
  2026-10-12). El emulador sigue en android-36. Revisar en Fase 3 si
  conviene una imagen `ps16k` para probar whisper.
- `sdkmanager` está marcado como deprecado en favor del nuevo comando
  `android sdk` (Android CLI). El script `tools/setup-linux.sh` sigue
  usando `sdkmanager` porque funciona; migrar cuando la doc del CLI esté
  asentada.
- La máquina de desarrollo no tiene `sudo` sin contraseña: JDK, SDK y
  ffmpeg se instalan en espacio de usuario; solo el grupo `kvm` requiere
  un comando con sudo del usuario.

## Problemas conocidos
- `adb shell am kill` no mata un proceso con servicio en primer plano; para
  simular una muerte por memoria usar `kill -9 <pid>` (con `adb root`).
- Tras `adb reboot`, `persist.sys.timezone` del emulador vuelve a UTC; fijarla
  de nuevo antes de pruebas que dependan de la medianoche local.
- El emulador no reproduce fielmente la altitud de `geo fix` (la mantiene
  durante descensos). El desnivel se valida con el test JVM sobre el GPX y
  en el Pixel 8 Pro; no confiar en el emulador para esa métrica.
- Reproducción de GPX en el emulador: máximo 3x para perfiles a pie (el
  filtro rechaza velocidades implícitas mayores a 4 m/s); comparaciones
  de cantidad de puntos entre modos solo a 1x.
- El emulador requiere el grupo `kvm` (resuelto 2026-10-12 con
  `usermod -aG kvm`). La sesión de Claude Code no hereda el grupo: el
  emulador se lanza con `sg kvm -c "emulator ..."`.
- Memoria: con 7 GB, los demonios de Gradle (3,2 GB) + Kotlin + emulador
  (2 GB) agotaron la RAM. Mitigado con `-Xmx2g`/`-Xmx1g` y `--no-daemon`
  en la puerta local; parar demonios (`./gradlew --stop`) antes de
  arrancar el emulador.
- Pendientes anotados por la revisión de código de Fase 0 (ver PR #1):
  idioma de transcripción se muestra como etiqueta cruda y no es editable
  (placeholder hasta Fase 3); `allowBackup` sin `dataExtractionRules`
  (se decide en 1.1 con la base; ítem 3.5); el plugin de Room y
  `schemaDirectory` se aplican en 1.1; cuatro `build.gradle.kts` de
  `core/*` idénticos → convention plugin cuando entre `core/videoplan`.

## Pendientes del usuario
- [x] Definir nombre y `applicationId` (D1) — default confirmado 2026-10-11
- [ ] Crear clave gratuita de MapTiler y ponerla en `local.properties` (antes del ítem 1.6)
