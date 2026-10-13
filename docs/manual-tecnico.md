# Bitácora de Viaje — Manual técnico

> Interno. Derivado de `PLAN.md`, el código, `CLAUDE.md`, `docs/PROGRESS.md` y
> `docs/notes/evidencia/` al cierre de la Fase 5 (2026-10-13). La especificación de origen es
> `docs/incoming/DISENO.md` (inmutable); ante una duda de comportamiento, manda ese documento.
> Se regenera con `/document`; su historia está en git.

## 1. Arquitectura general

Aplicación Android nativa (Kotlin, Jetpack Compose, Hilt, Room, WorkManager, Media3, CameraX,
MapLibre, Glance) más un renderizador de video de escritorio en Python. Módulos Gradle:

| Módulo | Tipo | Contenido |
|---|---|---|
| `app` | Android application | UI (Compose), servicio de registro, workers, DI, integraciones Android (MediaStore, CameraX, MapLibre, Media3, mosaicos, widget) |
| `data` | Android library | Room (`BitacoraDatabase`, esquema v9, `data/schemas/`), DAOs, repositorios, mappers entidad↔dominio |
| `core/model` | Kotlin JVM | Modelos de dominio y enums (sin Android) |
| `core/geo` | Kotlin JVM | Haversine, filtro de puntos, métricas, paradas, Douglas-Peucker, interpolación temporal, vinculación de medios, ranking de lugares, `TileMath` |
| `core/timeline` | Kotlin JVM | Línea de tiempo por etapa (`buildTimeline`) |
| `core/videoplan` | Kotlin JVM | Planificador determinista del video (`Planner`) y contrato `docs/video-plan.schema.json` |
| `core/export` | Kotlin JVM | Escritores del paquete (GPX, KML/KMZ, GeoJSON, CSV, JSON, HTML, Markdown, SRT, LEEME), `StagedExport`, `PrivacyFilter`, lector de `trip.json` |
| `whisper` | Android library (NDK) | whisper.cpp (submódulo `third_party/whisper.cpp`) con JNI |
| `desktop-renderer/` | Python (uv) | Renderizador de video de escritorio (FFmpeg) que consume el paquete exportado |
| `tools/` | Python/shell | Reproductor de GPX en el emulador, generador de Gantt, puerta de pruebas, verificación 16 KB, viajes de muestra |

Dependencias permitidas: `app → data → core/*`; `core/timeline → core/geo → core/model`;
`core/export → core/model, core/geo, core/videoplan`. **`core/*` no importa `android.*`**.

Regla de persistencia: toda escritura en transacciones, migraciones de Room probadas con
`MigrationTestHelper`, nunca `fallbackToDestructiveMigration`. Tiempo en UTC epoch ms con la
zona horaria guardada aparte en la etapa.

### Tablas (Room v9)
`trip`, `segment`, `segment_pause`, `track_point`, `stop`, `media_item`, `voice_note`,
`text_note`, `camera_clock_offset`, `place`, `enrichment`, `segment_place`. Detalle de
columnas en `data/schemas/com.bitacoraviaje.data.db.BitacoraDatabase/9.json` y en DISENO
§5.2 (con los agregados registrados en `docs/PROGRESS.md`, "Decisiones": `recording_mode`,
`auto_created`, `segment_pause.kind`, `dismissed`, `original_summary`, etc.).

### Pantallas (Navigation Compose, rutas tipadas en `BitacoraNavHost`)
`Home`, `Settings`, `NewSession`, `ActiveSession(segmentId)`, `FinishSegment(segmentId)`,
`TripDetail(tripId)` (pestañas línea de tiempo / mapa / fotos / lugares, diálogos de
exportación, mapa sin red y creación de video), `MediaReview(tripId)`,
`VoiceNoteDetail(noteId)`, `VideoCreate(tripId)`, `Camera(segmentId)`. Fuera del grafo:
`VoiceNoteActivity` (burbuja translúcida de grabación, `showWhenLocked`).

Patrón: `*Route` (Hilt ViewModel + estado) → `*Screen` sin estado (con `testTag` expuestos
como `resource-id` para uiautomator). Efectos únicos por `Channel<UiEvent>`.

### Estado compartido y servicios
- `TrackingService` (`LifecycleService`, foreground `location`): recibe comandos por `Intent`
  (`ACTION_START/PAUSE/RESUME/STOP/CHANGE_TRANSPORT`), filtra puntos con `PointFilter`
  (`core/geo`), los guarda en lotes (10 puntos o 30 s), publica `LiveTrackingState` en
  `TrackingStateHolder` (singleton observado por la UI, el widget y los mosaicos), actualiza la
  notificación (`TrackingNotificationFactory`, cada 15 s con puntos y cada 60 s por temporizador)
  y aplica la política de reposo automático y corte diario (`SessionPolicy`). Registra un
  `ContentObserver` de MediaStore (debounce 2 s) que dispara `LinkMediaWorker`.
- `TrackingController` (singleton): inicia/pausa/reanuda/termina sesiones; al cerrar una
  etapa calcula estadísticas, detecta paradas, encola la vinculación de medios, el
  enriquecimiento, el respaldo y, a pedido, el minivideo.
- Workers (`@HiltWorker`, nombres únicos por entidad, `ExistingWorkPolicy.KEEP`):
  `LinkMediaWorker`, `TranscribeWorker`, `ModelDownloadWorker`, `EnrichPlacesWorker`,
  `EnrichRouteWorker`, `ExportWorker`, `ImportWorker`, `BackupWorker`, `RenderWorker`. Los
  largos usan `setForeground` (`dataSync`) y publican progreso con `setProgress`.

## 2. El pipeline paso a paso

### 2.1 Registro del recorrido (Fase 1)
`FusedLocationProvider` con el perfil de la etapa (a pie / bici / vehículo / avión; modo
Completo o Económico, adenda 1) → `PointFilter` (tiempo duplicado, precisión, salto
imposible confirmado por 3 puntos, reposo) → lote a `track_point` (los rechazados se guardan
con `is_filtered_out = 1`) → `LiveTrackingState` (distancia acumulada por haversine entre
aceptados, velocidad, GPS disponible) → notificación. Pausa manual, reposo automático
(`AUTO_REST`, por puntos agrupados o por silencio N minutos), excursión anidada (`NESTED`,
`parent_segment_id`), corte a medianoche local (`SessionPolicy`), reinicio (`START_STICKY` +
etapa `ACTIVE` en la base; tras reboot solo notificación "tocá para reanudar").

### 2.2 Cierre de etapa (Fases 1–2)
`finishSegment` → estadísticas (`core/geo`: distancia, tiempo en movimiento, velocidad p98,
desnivel con histéresis sobre altitud suavizada) → `detectStops` (radio 50 m, 5 min,
fusión) → `LinkMediaWorker` → `EnrichPlacesWorker` → `BackupWorker` → opcional `RenderWorker`
(minivideo de 1 min).

### 2.3 Fotos y videos (Fase 2)
`MediaScanner` consulta MediaStore por rango de tiempo (`DATE_TAKEN` o `DATE_ADDED`),
excluye carpetas (capturas, WhatsApp, descargas, Telegram, `Android/media`), lee EXIF con
`setRequireOriginal` (ubicación) y la hora con prioridad `DateTimeOriginal+Offset` →
`EXIF_LOCAL` (zona de la etapa) → `DATE_TAKEN` → `mtime`. `MediaLinker` resuelve la etapa
por hora, aplica desfases de reloj por cámara (`camera_clock_offset`; se confirman solos
cuando el sello GPS de la foto difiere más de 1 min del reloj) y llama a `linkMedia`
(`core/geo`, tabla de DISENO §7.6: EXIF 1,0 / 0,5 / 0,9, interpolado 0,8, cercano 0,7·f,
NONE → revisión). Reglas: las colocaciones manuales, exclusiones, medios de otro viaje y las
capturas de la cámara propia ya vinculadas no se re-vinculan; `applyLinks` inserta con
`IGNORE` sobre el índice único de `mediastore_id`.
Cámara propia (Fase 5.2): `CameraCapture` registra la fila `APP_CAMERA` con el fix del momento
(confianza 1,0; sin fix reciente de menos de 2 min queda `NONE` y el barrido la vincula por
hora), sella `OffsetTimeOriginal` y GPS en el JPEG propio (en el lugar por descriptor `rw`,
con respaldo por temporal verificado) y publica el resultado por `SharedFlow` desde un ámbito
de aplicación.

### 2.4 Notas de voz y transcripción (Fases 2–3)
`VoiceRecorder` (AAC/M4A mono 16 kHz, `VOICE_RECOGNITION`, 5 min máx.) → `voice_note` con
posición (`NotesController.position`: último fix si tiene menos de 30 s, si no un pedido de
ubicación actual de hasta 5 s) → `TranscribeWorker` → `Transcriber` elegido: `whisper-local`
(módulo `whisper`, modelos ggml `base`/`small` descargados con SHA-256 verificado,
decodificación M4A→PCM 16 kHz con `MediaExtractor`/`MediaCodec`) o `android-speech`; estados
`PENDING → RUNNING → DONE | FAILED` con reintentos. La transcripción editada se guarda aparte
(`transcript_edited`).

### 2.5 Línea de tiempo (Fase 2)
`buildTimeline` (`core/timeline`) fusiona puntos, pausas, paradas, medios, notas y lugares de
una etapa en eventos ordenados con resúmenes de tramo ("Recorriste X km en Y"); cortes de
día por medianoche local. La misma función alimenta la pantalla, el HTML/Markdown exportado y
el planificador del video.

### 2.6 Exportación e importación (Fase 3, 5.5)
`ExportLoader` arma `TripExport` (dominio puro) → opcional `PrivacyFilter.apply` (zonas de
`UserPreferences`: puntos de adentro omitidos con pausa `PRIVACY` cerrada al salir, paradas y
lugares omitidos, medios y notas sin coordenadas, créditos y bbox recalculados; reporte con
todos los conteos) → `StagedExport` (cada entrada se materializa en
`noBackupFilesDir/export/staging/<trip>` con un manifiesto y una huella de opciones+zonas:
una interrupción se reanuda, un cambio de opciones descarta el staging) → `zipTo` al destino
(SAF, `Download/BitacoraViaje` vía MediaStore o archivo para compartir por `FileProvider`).
Copias JPEG de adentro de una zona: `ExifGps.stripped` quita todo el IFD GPS y verifica;
HEIC/MP4 se listan en el LEEME. `trip.json` sigue `docs/trip.schema.json` (validado en test)
y lo lee `TripJsonReader` para la importación (`ImportWorker`, con conflicto
reemplazar/mantener). `BackupWorker` escribe un `trip.json` comprimido por viaje incluido en la
copia de seguridad de Android (`data_extraction_rules.xml`).

### 2.7 Enriquecimiento de lugares (Fase 4)
`EnrichPlacesWorker` (`NetworkType.CONNECTED`, idempotente, reintento ante fallo total) →
`PlaceEnricher`: paradas y hasta 20 muestras del recorrido por etapa → Nominatim (1 req/s)
→ geosearch de Wikipedia (`es` → `en`) → resumen REST (fallback Action API) → Wikidata
(hechos literales: altitud, población, fundación) → Overpass (POIs y senderos; coincidencia
de sendero ≥ 60 % de puntos a < 30 m) → `PlaceRanking` (`core/geo`: permanencia, fotos,
notas, notoriedad, distancia; radio por tipo; 5 por etapa; dedup por QID). Tope de 45
consultas por etapa; opción de redondear coordenadas a 2 decimales. Tablas `place`,
`enrichment` (con `original_summary`, `user_approved`, `dismissed`), `segment_place`.
Reescritura con LLM (`ClaudeRewriter`, clave en `KeystoreSecretStore` AES-GCM): solo con la
clave del usuario; `RewriteGuard` exige que los números del resultado estén en la fuente, 70
palabras máximo, `SIN_DATOS` descarta. Atribuciones obligatorias (`TripExport.attributionsFor`).

### 2.8 Video (Fases 4–5)
`Planner` (`core/videoplan`) es determinista y está espejado en
`desktop-renderer/bitacora_render/planner.py`; las fixtures de conformidad en
`tools/plan-fixtures/` garantizan que ambos producen el mismo `VideoPlan` para la misma
entrada (`PlanConformanceTest`; regenerar con `-DupdatePlanFixtures=true`). Selección de
fotos: destacadas, nitidez (varianza del laplaciano), duplicados por pHash, empates → foto
posterior. Escenas: título, capítulo, mapa (general/por etapa, animado), foto (Ken Burns),
clip, tarjeta de lugar, nota de voz (subtítulo / audio / ambos), créditos.
- Teléfono: `MiniVideoComposer` + Media3 Transformer 1.11.1 (imágenes como ítems JPEG,
  `MatrixTransformation` para Ken Burns, `RouteOverlay` como `BitmapOverlay` con tiempo exacto
  por cuadro para el mapa animado, secuencia de voces con huecos y recorte por escena, música
  en bucle con `ChannelMixingAudioProcessor`), `RenderWorker` (foreground `dataSync`, salida a
  `Movies/BitacoraViaje` por MediaStore, duración medida con `MediaMetadataRetriever`),
  `VideoPlanStore` (plan y parámetros por viaje en `files/videoplans/`, exportable como
  `video_plan.json`), mapas estáticos por `MapSnapshotter` (MapTiler) con fondo liso de
  respaldo.
- Escritorio: `uv run bitacora-render plan|preview|render <paquete>`; FFmpeg con zoompan,
  mapas por teselas (MapTiler o sin red con fondo), ducking de música bajo voces, subtítulos
  (Pillow + DejaVu), tramos partidos por pausas y cortes de privacidad.

### 2.9 Mapas (Fases 1, 5.4)
`MapRenderer` (interfaz) con `MapLibreRenderer` (MapLibre 13.6.1 + estilo MapTiler
`outdoor-v2`, clave `BuildConfig.MAPTILER_KEY` desde `local.properties`; nunca se loguea el
estilo). `OfflineMaps` envuelve `OfflineManager`: regiones `OfflineTilePyramidRegionDefinition`
(zoom 8 → 12/14, misma URL de estilo, metadatos JSON con nombre/fecha/viaje),
`plan(stageBoxes, maxZoom)` fusiona etapas vecinas bajo el límite de 6000 teselas,
descarga secuencial por región con progreso agregado, lista y borrado desde Ajustes.
Estimación: 20 MB fijos + 20 KB/tesela (calibrada).

### 2.10 Accesos rápidos (Fase 5.3)
`VoiceNoteTileService` y `TrackingTileService` (`TileService`, Hilt; modelo puro
`TrackingTileModel`), widget Glance 1.2.0 (`BitacoraWidget`, estado leído con
`collectAsState`, refrescado desde `TrackingNotificationFactory.notify`), accesos directos
estáticos (`res/xml/shortcuts.xml`) que entran por `MainActivity` con acciones propias.

## 3. Cómo se corre

### Entorno (Linux sin Android Studio; ver skill `android-linux-env` y `tools/setup-linux.sh`)
- JDK 17 en `$HOME/.local/opt/jdk17`, SDK en `$HOME/Android/Sdk` (`compileSdk 37`,
  `minSdk 26`, `targetSdk 36`), NDK y CMake para `whisper`, `ffmpeg`, `uv`. KVM para el
  emulador (la sesión del agente lo lanza con `sg kvm -c "emulator …"`).
- `local.properties`: `sdk.dir=…` y `MAPTILER_KEY=…` (nunca al repo).
- Submódulo: `git submodule update --init third_party/whisper.cpp`.

### Comandos
```bash
./gradlew assembleDebug                      # compilar
./gradlew test                               # pruebas JVM (Robolectric incluido)
./gradlew :core:geo:test                     # un módulo
./gradlew lint
./tools/run-tests.sh                         # la puerta: gradle test + pytest de los subproyectos (igual que CI)
./tools/check-16kb.sh app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Bitacora:* AndroidRuntime:E
uv run --project tools/gpx-replay python tools/gpx-replay/replay.py tools/sample-trips/trekking/track.gpx --speed 3
cd desktop-renderer && uv run pytest && uv run bitacora-render render <carpeta-exportada> --duration 180
uv run --with openpyxl python tools/gantt.py  # reports/gantt.xlsx desde PLAN.md + git
```
CI (`.github/workflows/ci.yml`) corre la misma puerta. Ramas `main`/`dev`; trabajo en
`feat/*` que mergea a `dev` por PR con el informe del code-reviewer persistido en la
descripción. El hook local `guard-git.sh` corre la puerta antes de cualquier push a `dev`/`main`.

### Memoria de la máquina de desarrollo
7 GB: Gradle (`--no-daemon`, `-Xmx2g`) + Robolectric + emulador (2 GB) se pisan. Con el
emulador o un agente revisor activo, correr la puerta partida (`test`, luego `lint
assembleDebug`, luego 16 KB y `uv`), no `run-tests.sh` entera.

## 4. Decisiones de diseño y su porqué

| Decisión | Porqué (fuente) |
|---|---|
| Nivel de calidad 2 del PLAN (gate + revisión persistida) | Perder un recorrido es irreversible (`PROGRESS` 2026-10-11) |
| MapLibre + MapTiler en vez de Google Maps (D2) | Clave gratuita, mapas sin red, uso libre en el video; Google Maps exige facturación y no se puede usar en video; My Maps no tiene API de creación → KML exportado (PLAN §5) |
| Fotos desde MediaStore, no Google Fotos | La Library API ya no lee la biblioteca del usuario (31/03/2025); el Photo Picker borra la ubicación EXIF (PLAN §5, skill `media-linking`) |
| Transcripción solo local (D3) | Privacidad y uso sin red; el audio nunca sale del teléfono salvo opción explícita futura |
| Uso personal por instalación directa (D4); Fase 6 en pausa | Decisión del usuario 2026-10-11 |
| Registro continuo con reposo automático y corte diario; modos Completo/Económico | Adenda 1 del usuario (viajes largos sin recarga) |
| Teselas de MapTiler compuestas en el video | Aceptado para uso personal; si se publica, migrar a PMTiles (PLAN 6.2) |
| `core/*` puro + Robolectric para lo Android; E2E en emulador con GPX reproducido | Lo que se puede probar sin emulador, se prueba sin emulador (`CLAUDE.md`) |
| Planificador de video espejado Kotlin/Python con fixtures de conformidad | El mismo `VideoPlan` debe rendir igual en teléfono y PC (PLAN 5.1) |
| Mapa animado como `BitmapOverlay` y no como clip MediaCodec | El clip por MediaCodec no respetaba la duración (152 s de 180); revisión B1 del PR #11 |
| Staging reanudable de la exportación con huella de opciones | Nada se escribe dos veces; una huella distinta evita reanudar con archivos sin filtrar (revisión B1 del PR #12) |
| Zonas de privacidad solo en la exportación | DISENO §15.3: el teléfono conserva todo; solo lo que sale se recorta |
| Captura propia vinculada con confianza 1,0 solo con fix de menos de 2 min | Un fix viejo no puede quedar como "lugar exacto" en el EXIF (revisión I1 del PR #13) |
| Mosaico "Iniciar" abre la app en vez de arrancar el servicio | Un servicio de ubicación en primer plano no puede arrancar desde un mosaico (Android 12+) |
| Regiones sin red por grupo de etapas | Un viaje con etapas lejanas no entra en el límite de 6000 teselas de MapLibre (evidencia 5.4) |
| `dataSync` como tipo de servicio del render | `mediaProcessing` (Android 15) se evalúa antes de publicar (revisión M10 del PR #11) |

Las revisiones de código de cada PR (#1–#15) están persistidas en sus descripciones en
GitHub y resumidas en `docs/PROGRESS.md`; las notas de evidencia por ítem viven en
`docs/notes/evidencia/fase-N/`.

## 5. Rarezas y hallazgos de datos (de las pruebas)

- Las fotos recién guardadas pueden tener `IS_PENDING = 1`: el barrido las ignora y las toma
  en el siguiente (observador con debounce de 2 s).
- Un `@Insert(onConflict = REPLACE)` sobre una tabla padre dispara `ON DELETE CASCADE` en los
  hijos: usar `@Upsert` (detectado en 4.1).
- El delegado `preferencesDataStore` es un singleton del proceso en Robolectric: cada test que
  lo use repone sus valores en `setUp`.
- El emulador entrega pocos fixes de FusedLocation aun con `geo fix` frecuente, no reproduce
  la altitud y vuelve a UTC tras `adb reboot`; la reproducción de GPX a pie va como máximo a 3x
  (el filtro rechaza más de 4 m/s). La codificación de video del emulador solo llega a 720p.
- `completedResourceSize` de una región de MapLibre incluye estilo, sprites y glifos
  (~20 MB por región); el tamaño en disco real es menor porque los recursos compartidos se
  deduplican por URL.
- La hora de un MP4 es el fin del clip: el linker resta la duración.
- Una excursión en bici de 13 min en el emulador dio 17 puntos: alcanza para enriquecer, no
  para medir filtros.

## 6. Deuda técnica conocida y dónde tocar

- Backlog de revisiones (anotado en `docs/PROGRESS.md`): pantalla previa de permisos (§15.1);
  extraer el pipeline de puntos a un `TrackRecorder` puro con reloj inyectado; pausa manual +
  excursión anidada; fuente única de altitud (MSL vs elipsoidal); convention plugin para los
  `build.gradle.kts` de `core/*`; tests de Compose de `CameraScreen` y de la navegación por
  `newSessionRequest`; GeoJSON mezcla `LineString`/`MultiLineString` según haya pausas; JSON de
  zonas de privacidad inválido se trata como "sin zonas"; rotar corta una grabación de video;
  `targetPackage` fijo en `shortcuts.xml` (ligado a D1); widget sin `previewLayout`.
- Fase 6 (pulido y publicación) en pausa por D4: accesibilidad/traducciones/onboarding,
  declaraciones de Play, política de privacidad, PMTiles para el video.
- Dónde tocar: perfiles de GPS y filtros → `core/geo` (`FilterConfig`, `SamplingProfile` en
  `core/model`); reglas de vinculación → `core/geo/MediaLink*` y `app/media/MediaLinker`;
  formatos de exportación → `core/export/*Writer`; esquema del paquete → `docs/trip.schema.json`
  + `TripJsonWriter/Reader` (y el modelo pydantic del renderizador); planificador →
  `core/videoplan/Planner` **y** `desktop-renderer/bitacora_render/planner.py` (regenerar
  fixtures); enriquecimiento → `app/enrichment`; ajustes → `app/settings/UserPreferences`.

## 7. Puntos de fallo frecuentes y diagnóstico

| Síntoma | Dónde mirar |
|---|---|
| El servicio no arranca (`SecurityException` / `ForegroundServiceStartNotAllowedException`) | Permisos de ubicación antes de `startForegroundService`; solo se inicia con la app visible (`TrackingController.startService`) |
| Puntos rechazados en masa | `logcat -s Bitacora` muestra el motivo del filtro; comparar `is_filtered_out` en `track_point`; perfil vs velocidad real |
| Fotos sin vincular | Permiso de medios (parcial en Android 14+), carpeta excluida, `takenTimeSource`, desfase de reloj (`camera_clock_offset`) |
| Transcripción `FAILED` | Modelo whisper no descargado o SHA inválido (`ModelCatalog`), `transcribe_only_charging`, audio de menos de 0,5 s descartado |
| Lugares vacíos | Sin red al cerrar la etapa (reintento desde la pestaña), tope de 45 consultas, radio por tipo, `enrich_places` apagado |
| Exportación "faltan archivos" | `LEEME.txt` lista los faltantes; el staging en `noBackupFilesDir/export/staging/` se reanuda; huella distinta lo descarta |
| Video de duración incorrecta | La duración se mide sobre el archivo; mapas animados por `RouteOverlay`, no por clip; voces recortadas a su escena |
| Mapa en blanco | `MAPTILER_KEY` ausente (aviso en pantalla); sin red solo las regiones descargadas (`Ajustes → Mapas sin red`) |
| Widget o mosaico desactualizado | El estado sale de `TrackingStateHolder` del proceso; con el servicio muerto y etapa abierta muestran "Volver a la sesión" |
| Gate local falla pero CI pasa (o al revés) | Memoria de la máquina (puerta partida), demonios de Gradle vivos, zona horaria del emulador |

## 8. Huecos de documentación

- [PENDIENTE: completar — no documentado en las fuentes] Mediciones en el Pixel 8 Pro:
  batería 1 h por modo (ítem 1.7), tiempos de render de 1 y 3 min (4.6, 5.1), calidad de
  transcripción con ≥10 notas (3.4). Están como pendientes del usuario en `docs/PROGRESS.md`.
- [PENDIENTE: completar — no documentado en las fuentes] Comportamiento por fabricante ante
  restricciones de batería (DISENO §6: pantalla de ayuda por marca); solo probado en Pixel.
- [PENDIENTE: completar — no documentado en las fuentes] Google Fotos (Picker/subida, ítem
  5.6) marcado como deseable a futuro; no hay diseño detallado más allá de DISENO §7.
