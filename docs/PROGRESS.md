# Registro de avance

> Este archivo lo mantiene Claude Code. Actualizarlo al terminar cada tarea relevante.

## Estado actual
- Fase: **3 mergeada en `dev` (PR #7, 2026-10-13) con 3.7 (usuario) y la medición de 3.4 abiertos; Fase 4 iniciada en `feat/fase-4-enriquecimiento`.** Siguen abiertos los ítems de campo 1.7, 2.8 y 3.7, que cierran sus fases formalmente.
- Última actualización: 2026-10-13

## Fases
| Fase | Estado | Evidencia de aceptación |
|---|---|---|
| 0 — Esqueleto | ✅ Cerrada 2026-10-12 | `docs/notes/evidencia/fase-0/`: 0.1-0.3-emulador.md (+capturas), 0.2-build-test-lint.md, 0.4-ci.md (PR #1 verde) |
| 1 — Recorrido | 🟨 1.1-1.6 cerrados y mergeados; 1.7 pendiente (campo) | 1.1: `docs/notes/evidencia/fase-1/1.1-room.md` · 1.2: `1.2-core-geo.md` · 1.3: `1.3-tracking.md` · 1.4: `1.4-continuidad.md` · 1.5: `1.5-recuperacion.md` · 1.6: `1.6-ui.md` · Requiere clave de MapTiler del usuario antes del ítem 1.6 |
| 2 — Fotos, voz y línea de tiempo | 🟨 2.1-2.7 y 2.9 cerrados (2.4 verificado con micrófono real en el Pixel el 2026-10-13); 2.8 (campo) pendiente | 2.1-2.3: `docs/notes/evidencia/fase-2/2.1-2.3-fotos.md` · 2.4 y 2.5: `2.4-2.5-notas.md` · 2.6 y 2.7: `2.6-2.7-detalle.md` (+capturas) · 2.9: `2.9-cambio-de-medio.md` · fotos reales del test en `local-data/` (gitignorado) |
| 3 — Exportación y transcripción local | 🟨 3.1–3.3, 3.5 y 3.6 cerrados; 3.4 hecho salvo la medición con ≥10 notas; 3.7 (usuario) pendiente | 3.1: `docs/notes/evidencia/fase-3/3.1-export.md` · 3.2: `3.2-export-worker.md` · 3.3: `3.3-import.md` · 3.4: `3.4-whisper.md` · 3.5: `3.5-respaldo.md` · 3.6: `3.6-nombre-de-ruta.md` (+captura) |
| 4 — Enriquecimiento y video de escritorio | 🟨 4.1 cerrado | 4.1: `docs/notes/evidencia/fase-4/4.1-lugares.md` |
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
| 2026-10-12 | Mapa: MapLibre 13.6.1 + MapTiler estilo `outdoor-v2`, clave en `local.properties` → `BuildConfig.MAPTILER_KEY` (sin clave la app compila y muestra un aviso) | D2 |
| 2026-10-12 | Cerrar etapa espera el volcado del búfer del servicio (tope 5 s) | Estadísticas sin los últimos puntos (0 m en una excursión corta) |
| 2026-10-12 | Revisión de código de Fase 1 aplicada: pipeline del servicio en un solo hilo; volcado del búfer también por tiempo desde el ticker y limpieza solo tras persistir; `finishTrip` cierra todas las etapas abiertas del viaje; simplificación en lotes de 500 ids; corte diario con la zona horaria actual del teléfono; estado vivo rehidratado al retomar una etapa; primer tramo tras pausa/reinicio no suma; ClockGuard sembrado y con detección de reinicio; un solo nivel de anidado; nombre del viaje en el asistente | PR #4, revisión 2026-10-12 |
| 2026-10-12 | Criterios adaptados en E2E: parada de 4 min (no 20) con umbral de 3 min en 1.4; relación Económico/Completo medida sobre 8 min a 1x en 1.3 | Límite de reproducción a 1x y de 10 min por comando |
| 2026-10-12 | Fase 2 arranca con 1.7 pendiente (excepción a la regla "no empezar una fase sin cerrar la anterior") | Decisión del usuario; 1.7 depende de una salida de campo y no bloquea el código de Fase 2 |
| 2026-10-12 | Ítem 2.9 agregado: cambiar medio de transporte durante la sesión | Pregunta del usuario en la prueba: hoy el medio se fija por etapa |
| 2026-10-12 | Esquema v3: `media_item` (+`camera_make/model`, `needs_review` sobre §5.2); vinculación como tarea de WorkManager única por viaje, disparada al cerrar etapa y por el observador (2 s) | DISENO §4.4 paso 6, §7.3 |
| 2026-10-12 | Fotos sintéticas con el escritor EXIF de Pillow (piexif no es legible por ExifInterface) | Hallazgo E2E 2.2 |
| 2026-10-12 | Esquema v4: `voice_note` (+`media_item_id`), `text_note`; grabador AAC 16 kHz mono fuera del hilo principal con tope de 4 s; transcriptor del sistema desde archivo (API 33+) con stub de respaldo | DISENO §8, hallazgo ANR en emulador |
| 2026-10-12 | Esquema v5: `camera_clock_offset` (viaje, cámara → desfase). La corrección de reloj de cámara se guarda y el vinculador la aplica en cada barrido; antes el barrido recalculaba la hora desde EXIF y deshacía el ajuste | DISENO §7.6 paso 4; hallazgo E2E 2.7 |
| 2026-10-12 | Línea de tiempo (`core/timeline`): momentos de fotos con ventana 2 min / 100 m, resúmenes intermedios a partir de 300 m y 10 min, cambio de día por medianoche local de la etapa; eventos sin posición propia la toman del recorrido (`positionAt`) | DISENO §9 |
| 2026-10-12 | Detalle del viaje: tocar un evento cambia a la pestaña Mapa y centra la cámara; la revisión de fotos ofrece la corrección de reloj solo si el desfase implícito supera 1 min | DISENO §14.1 |
| 2026-10-12 | Cambio de medio en sesión (2.9) sin tabla nueva: `text_note.kind = TRANSPORT_CHANGE` con el medio en `text`; la etapa guarda el último medio y perfil; el servicio re-siembra el filtro con el último punto aceptado | Pedido del usuario 2026-10-12; evita esquema v6 |
| 2026-10-12 | Revisión de código Fase 2 (PR #5) aplicada antes del merge: tope de 5 min guarda la nota (antes se borraba), rotación no cancela la grabación, grabador liberado si falla el arranque, solo reconocedor on-device (nunca el micrófono en segundo plano), escáner compatible API 26–28, ventana de barrido ensanchada por el desfase de cámara, re-vinculación con UPDATE condicionado (no pisa ubicaciones manuales), desfase automático por hora GPS, config de vinculación por medio de transporte | PR #5 |
| 2026-10-12 | Diferidos con registro: barrido incremental por generación (Fase 3); claves foráneas de `camera_clock_offset`→`trip` y `voice_note.media_item_id` (entran con el próximo cambio de esquema); acceso solo-video en permisos; `creation_time` de MP4 asumido UTC hasta validarlo con un video real en 2.8 | Revisión PR #5 (NIT, M6) |
| 2026-10-13 | Transcripción con el reconocedor del dispositivo: elegir el paquete instalado del mismo idioma (es-US/es-ES en lugar de es-AR), alimentar el audio a ritmo real y usar la sesión por segmentos. D3 (nube): Claude no acepta audio (documentación oficial verificada 2026-10-13); si se suma transcripción en la nube en Fase 3 será un motor de voz a texto aparte, con la clave del usuario, y Claude queda para corregir y redactar texto (Fase 4.3) | PR #6; pregunta del usuario |
| 2026-10-13 | Exportación (3.1): `core/export` puro con un escritor por formato y ZIP en streaming; `trip.json` con esquema en `docs/trip.schema.json` (versión 1) validado en tests; GPX validado contra el XSD oficial; sin extensiones de Garmin (sin datos de sensores); límites de My Maps como constantes | DISENO §12; skill trip-export |
| 2026-10-13 | Exportación (3.2): staging con manifiesto como checkpoint (cada entrada un archivo en caché; el ZIP se arma al final), worker `dataSync` único por viaje con política KEEP, destinos SAF / Descargas (MediaStore, API 29+) / compartir (FileProvider); en API 26–28 Descargas cae a compartir. Presupuesto `dataSync` de 6 h/día en Android 15+ anotado | DISENO §12.4; skill trip-export |
| 2026-10-13 | Importación (3.3): ZIP / trip.json / GPX por el selector del sistema; conflicto de id → reemplazar o copia con ids regenerados; medios copiados a `Pictures/BitacoraViaje/<viaje>/` con su id de MediaStore guardado (evita que el barrido los duplique); GPX con SAX (StAX no existe en Android); GPX sin horas → horas ficticias + aviso. En API 26–28 los medios del ZIP no se copian (permiso heredado) | DISENO §12.5 |
| 2026-10-13 | whisper.cpp v1.9.5 como submódulo, NDK r29 + CMake 4.1.2, solo CPU, `-O3` en debug, 16 KB; VAD Silero obligatorio; modelos `base`/`small` descargados y verificados por SHA-256, nunca en el APK | DISENO §8.3; PLAN 3.4 |
| 2026-10-13 | Motor de transcripción por defecto **Automático** (reconocedor del sistema primero, whisper de respaldo) en lugar de whisper primero: con 2 notas reales el sistema no tuvo errores y whisper `base` tuvo ~20–40 % de palabras mal (emulador). Se revisa con ≥10 notas en el Pixel (`3.4-whisper.md`) | Dato manda sobre la doctrina de DISENO §8.3 |
| 2026-10-13 | Respaldo (3.5): reglas de Auto Backup como listas de inclusión (preferencias, DataStore, `files/backup/`; audio solo en transferencia directa); la base nunca se respalda; `trip.json` automático por viaje al cerrar etapa o viaje, escritura atómica | PLAN 3.5; revisión Fase 0 (`allowBackup`) |
| 2026-10-13 | Esquema v6: `segment.auto_name` (nombre derivado del mapa; `title` del usuario gana), FK `camera_clock_offset`→`trip` e índice `voice_note.media_item_id` | PLAN 3.6; diferidos del PR #5 |
| 2026-10-13 | OSM: un cliente OkHttp con User-Agent, caché, 1 pedido/s por host y backoff en 429/503/504; instancias de Nominatim/Overpass configurables; muestras a zoom 16 (a 14 no hay calles); departamento aceptado como lugar en campo abierto | PLAN 3.6; política de uso de Nominatim |
| 2026-10-13 | Revisión de código Fase 3 (PR #7) aplicada antes del merge: ids de archivos externos validados antes de volverse rutas; importación atómica (medios y notas en la misma transacción) y cola que no cancela una importación en curso; escritura SAF con truncado y limpieza de la fila pendiente de Descargas; parser GPX endurecido (sin DOCTYPE ni entidades); respaldo encadenado después de vincular fotos, comprimido (`.json.gz`) y restaurable desde Ajustes; extremos del recorrido (200 m) nunca se envían a OSM; staging en `noBackupFilesDir` con chequeo de espacio | PR #7 |
| 2026-10-13 | Diferidos de la revisión del PR #7 con registro: avisos de importación como textos en `core` (pasarlos a códigos + `strings.xml` en Fase 6); documentos del staging no se invalidan si la base cambia entre reintentos; redondeo a ~1 km / zonas de privacidad para OSM (Fase 6, DISENO §15); `libwhisper.so` genérico de arm64 lleva ggml con fp16 (como el ejemplo upstream; afecta solo CPUs sin `fphp`, anteriores a 2017); el respaldo no se refresca tras editar textos (solo al cerrar etapa/viaje); medios se copian al staging antes del ZIP (duplica espacio; el chequeo previo lo cubre) | Revisión PR #7 (M3, M8, M15, M17, I3 parcial) |
| 2026-10-12 | compileSdk 37 (targetSdk sigue en 36) | 18 librerías AndroidX vigentes (Navigation 2.10, Hilt Compose 1.4...) exigen API 37 |
| 2026-10-13 | Esquema v7: `place`, `enrichment`, `segment_place` (DISENO §5.2) con ids determinísticos (`wd-Q…`, `osm-N…`) y `@Upsert` (un `REPLACE` sobre `place` borraría en cascada textos y vínculos); un `enrichment` por fuente (Wikipedia CC BY-SA 4.0, Wikidata CC0); re-enriquecer conserva `user_approved` | PLAN 4.1; DISENO §11 |
| 2026-10-13 | Wikipedia detrás de `WikipediaSource`: resumen por REST v1 con caída automática a la Action API (`prop=extracts`) por la migración anunciada de los endpoints REST de Wikimedia; GeoSearch por Action API; inglés como idioma de respaldo marcado en `lang` | PLAN 4.1; DISENO §11.2 |
| 2026-10-13 | Ranking de lugares (`PlaceRanking`, puro): radios 5 km / 10 km / 300 m por clase, pesos 2·permanencia + 1,5·fotos + 1·notas + 0,5·ln(1+sitelinks) − 1·distancia, 5 por etapa. Qué sale del teléfono: paradas y 10 muestras a 4 decimales sin los 200 m de los extremos; opción de 2 decimales (~1 km) en Ajustes; tope de 45 llamadas por etapa | DISENO §11.3, §15 |
| 2026-10-13 | `trip.json` suma `places[].visits[]` y campos opcionales de `enrichments[]` sin subir `schema_version` (solo agrega opcionales); `attributions[]` cita cada artículo usado; URLs de servicios aceptan `http` solo hacia loopback (instancia local de prueba) | DISENO §12.2, §11.5 |

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
| kotlinx-serialization-json | 1.11.0 (misma que serialization-core) | 2026-10-13 |
| whisper.cpp (submódulo) | v1.9.5 | 2026-10-13 |
| OkHttp / MockWebServer (mockwebserver3, solo tests) | 5.5.0 | 2026-10-13 |
| NDK | 29.0.14206865 | 2026-10-13 |
| CMake (SDK) | 4.1.2 | 2026-10-13 |
| Modelos whisper (ggml, Hugging Face) | base 60ed5bc3…, small 1be3a9b2…, VAD silero v5.1.2 29940d98… (SHA-256 completos en `ModelCatalog`) | 2026-10-13 |
| json-schema-validator (optimumcode, solo tests) | 0.5.5 | 2026-10-13 |
| androidx.test core-ktx / runner | 1.7.0 / 1.7.0 | 2026-10-12 |
| Navigation Compose / Activity Compose / Lifecycle | 2.10.2 / 1.13.0 / 2.11.0 | 2026-10-11 |
| WorkManager / DataStore / core-ktx | 2.12.0 / 1.2.1 / 1.19.1 | 2026-10-11 |
| Coroutines / kotlinx-serialization | 1.11.0 / 1.11.0 | 2026-10-12 |
| Play Services Location / lifecycle-service | 21.4.0 / 2.11.0 | 2026-10-12 |
| MapLibre Android SDK | 13.6.1 | 2026-10-12 |
| ExifInterface / hilt-work / Coil | 1.4.2 / 1.4.0 / 3.6.3 | 2026-10-12 |
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
- Backlog de la revisión de Fase 1 (no bloqueante): pantalla previa de
  explicación antes de pedir permisos (§15.1); extraer el pipeline de puntos
  a una clase pura `TrackRecorder` testeable con reloj inyectado; interacción
  pausa manual + excursión anidada; fuente única de altitud (MSL vs
  elipsoidal) por etapa; `ForegroundServiceStartNotAllowedException` a
  vigilar en el Pixel durante 1.7.
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
  (resuelto en 3.5 el 2026-10-13); el plugin de Room y
  `schemaDirectory` se aplican en 1.1; cuatro `build.gradle.kts` de
  `core/*` idénticos → convention plugin cuando entre `core/videoplan`.

- Escribir en la base del emulador con `sqlite3` por fuera de Room no dispara
  los `Flow` observados por las pantallas; cerrar la app antes de manipular
  datos de prueba. El barrido de fotos al abrir el detalle re-vincula todo lo
  que no sea manual o excluido.
- Transcripción en el emulador: el reconocedor del sistema devuelve error 12
  (sin modelo de español); el flujo completo se valida en el Pixel. Un cierre
  temprano de la tubería de audio mataba el proceso (corregido 2026-10-12).

## Pendientes del usuario
- [x] Definir nombre y `applicationId` (D1) — default confirmado 2026-10-11
- [x] Crear clave gratuita de MapTiler y ponerla en `local.properties` — hecho 2026-10-12
- [ ] Ítem 1.7: hecha una salida de 46 min en auto en Full (2026-10-12, ver evidencia); falta 1 h con pantalla apagada en Económico (y Full), anotando batería al inicio y al final
- [ ] Ítem 2.8: las 5 fotos de la salida quedaron vinculadas solas y las 2 notas de voz transcribieron bien; falta una salida con pantalla apagada para cerrar junto con 1.7 y revisar la línea de tiempo del viaje en el teléfono
