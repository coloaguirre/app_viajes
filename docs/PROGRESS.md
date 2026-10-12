# Registro de avance

> Este archivo lo mantiene Claude Code. Actualizarlo al terminar cada tarea relevante.

## Estado actual
- Fase: **0 — Esqueleto y entorno, en curso** (ítem 0.2 cerrado; 0.1 y 0.3 esperan el emulador; 0.4 espera el merge)
- Última actualización: 2026-10-12

## Fases
| Fase | Estado | Evidencia de aceptación |
|---|---|---|
| 0 — Esqueleto | 🟨 En curso | 0.2: `docs/notes/evidencia/fase-0/0.2-build-test-lint.md` · 0.4: `0.4-ci.md` (PR #1 verde) |
| 1 — Recorrido | ⬜ Pendiente | |
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
| Room | 2.8.5 | 2026-10-11 |
| Navigation Compose / Activity Compose / Lifecycle | 2.10.2 / 1.13.0 / 2.11.0 | 2026-10-11 |
| WorkManager / DataStore / core-ktx | 2.12.0 / 1.2.1 / 1.19.1 | 2026-10-11 |
| Coroutines / kotlinx-serialization | 1.11.0 / 1.11.0 | 2026-10-12 |
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
- El emulador requiere pertenecer al grupo `kvm` (comando con sudo del
  usuario, pendiente al 2026-10-12); hasta entonces los ítems 0.1 y 0.3
  no pueden cerrarse.
- Pendientes anotados por la revisión de código de Fase 0 (ver PR #1):
  idioma de transcripción se muestra como etiqueta cruda y no es editable
  (placeholder hasta Fase 3); `allowBackup` sin `dataExtractionRules`
  (se decide en 1.1 con la base; ítem 3.5); el plugin de Room y
  `schemaDirectory` se aplican en 1.1; cuatro `build.gradle.kts` de
  `core/*` idénticos → convention plugin cuando entre `core/videoplan`.

## Pendientes del usuario
- [x] Definir nombre y `applicationId` (D1) — default confirmado 2026-10-11
- [ ] Crear clave gratuita de MapTiler y ponerla en `local.properties` (antes del ítem 1.6)
