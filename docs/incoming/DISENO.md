# Bitácora de Viaje — Documento de diseño

**Versión:** 1.0 · **Fecha:** 11 de octubre de 2026 · **Destino:** sesión de Claude Code en Linux

Este documento describe en detalle cómo construir una app Android para registrar un viaje. La app combina el recorrido GPS, las fotos y videos, y comentarios dictados en una bitácora cronológica. Al final genera un video resumen. Está pensado para que una sesión de Claude Code lo use como especificación. Las skills de `.claude/skills/` amplían cada área técnica.

---

## Índice

1. Visión y alcance
2. Conceptos y vocabulario
3. Decisiones técnicas principales (y por qué)
4. Arquitectura
5. Modelo de datos
6. Módulo de recorrido (tracking GPS)
7. Módulo de fotos y videos
8. Módulo de bitácora por voz
9. Línea de tiempo unificada
10. Mapas
11. Enriquecimiento con información de internet
12. Exportación a PC
13. Generación del video resumen
14. Interfaz de usuario (pantallas y flujos)
15. Permisos, políticas de Google Play y privacidad
16. Batería, robustez y casos límite
17. Estrategia de pruebas
18. Entorno de desarrollo en Linux
19. Plan de trabajo por fases (con criterios de aceptación)
20. Riesgos y decisiones abiertas
21. Referencias

---

## 1. Visión y alcance

### 1.1 Problema
Durante un viaje se generan muchos datos dispersos: fotos en la galería, recuerdos que no se anotan y recorridos que nadie guarda. Al volver es difícil reconstruir qué pasó, dónde y cuándo.

### 1.2 Solución
Una app que, una vez iniciada, registra automáticamente el recorrido. Además vincula cada foto o video con el lugar y la hora en que se tomó, y permite dictar notas que quedan transcriptas. Todo forma una bitácora cronológica que se puede exportar a la PC y convertir en un video resumen.

### 1.3 Objetivos
- **O1.** Registrar el recorrido con coordenadas, hora, velocidad, altitud y precisión, con un consumo de batería razonable para un día completo.
- **O2.** Vincular fotos y videos al punto del recorrido, sin trabajo manual en el caso normal.
- **O3.** Dictar notas con un toque y obtenerlas como texto editable, conservando el audio original.
- **O4.** Ver todo en una línea de tiempo y en un mapa.
- **O5.** Exportar cada viaje en formatos abiertos que se abran en la PC sin la app: GPX, KML, GeoJSON, JSON, HTML y los archivos originales.
- **O6.** Generar un video resumen con mapa animado, fotos, videos, notas e información de los lugares.
- **O7.** Funcionar sin conexión durante el viaje. La conexión solo se necesita para el mapa base, el enriquecimiento y la transcripción en la nube, si se elige.

### 1.4 Fuera de alcance (versión 1)
- Red social o compartir en vivo.
- Sincronización multi-dispositivo en tiempo real.
- Navegación turn-by-turn.
- Versión iOS.

### 1.5 Tipos de sesión
El usuario indica al iniciar qué está haciendo. El tipo define el perfil de muestreo y cómo se presenta en la bitácora y el video.

| Tipo | Uso | Perfil GPS por defecto |
|---|---|---|
| **Inicio de viaje** | Crea un viaje nuevo y su primera etapa | Según medio de transporte |
| **Continuar etapa** | Retoma un viaje existente en una etapa nueva (otro día, otro tramo) | Según medio de transporte |
| **Excursión / visita** | Salida corta dentro de una etapa: un museo, un pueblo, una playa | Peatonal / mixto |
| **Trekking** | Caminata de montaña o sendero, con énfasis en desnivel | Alta precisión |

El usuario también elige (o la app detecta) el **medio**: a pie, bicicleta, auto/micro, tren, barco o avión. El medio ajusta la frecuencia de muestreo.

---

## 2. Conceptos y vocabulario

- **Viaje (Trip):** la unidad completa, por ejemplo "Patagonia 2026". Tiene una o más etapas.
- **Etapa (Segment):** una sesión de registro continua dentro de un viaje: un día, un tramo, una excursión o un trekking. Cada etapa tiene un tipo.
- **Punto de recorrido (TrackPoint):** una posición GPS con su hora y sus métricas.
- **Parada (Stop):** un lugar donde el usuario permaneció quieto más de cierto tiempo. Se detecta automáticamente.
- **Medio (Media):** una foto o un video vinculado a la etapa.
- **Nota de voz (VoiceNote):** un audio con su transcripción y su ubicación.
- **Nota de texto / marcador:** un punto que el usuario marca manualmente con un texto opcional.
- **Lugar (Place):** un sitio identificado por geocodificación inversa o por proximidad a un punto de interés.
- **Enriquecimiento (Enrichment):** información obtenida de internet sobre un lugar, con su fuente.
- **Evento de línea de tiempo:** cualquier elemento con una marca de tiempo: medio, nota, parada, cambio de medio de transporte, inicio o fin de etapa.

---

## 3. Decisiones técnicas principales

| Tema | Decisión | Motivo |
|---|---|---|
| Plataforma | Android nativo, **Kotlin** | Acceso completo a servicios en primer plano, MediaStore, sensores y Media3 |
| UI | **Jetpack Compose** + Material 3 | Estándar actual, menos código |
| Arquitectura | MVVM + capa de dominio liviana, flujo de datos unidireccional | Testeable y conocida |
| Inyección de dependencias | **Hilt** | Integración oficial con WorkManager y ViewModel |
| Persistencia | **Room** (SQLite) con WAL | Local, offline, consultas por tiempo eficientes |
| Asincronía | Coroutines + Flow | Estándar en Kotlin |
| Trabajo diferido | **WorkManager** | Transcripción, vinculación, enriquecimiento y exportación en segundo plano |
| Ubicación | **FusedLocationProvider** (Google Play services) en un servicio en primer plano tipo `location` | Mejor precisión/consumo; obligatorio declarar el tipo |
| Fotos | **MediaStore** local + cámara propia opcional con **CameraX** | La API de Google Fotos ya no permite leer la biblioteca completa (ver §7.1) |
| Transcripción | Grabar audio siempre; transcribir con **whisper.cpp en el dispositivo**; alternativa en la nube configurable | Funciona sin conexión y la calidad en español es alta |
| Mapa en la app | **Abstracción `MapRenderer`** con implementación en Google Maps (Maps Compose) o MapLibre + OSM | El usuario pidió Google Maps; MapLibre permite mapas offline y uso libre en el video |
| Video | **Doble motor**: renderizador de escritorio en Python + FFmpeg (fase 4) y motor en el dispositivo con **Media3 Transformer** (fase 5) | El renderizador de escritorio se puede probar desde Linux sin teléfono |
| Enriquecimiento | Wikipedia GeoSearch + Wikidata + Nominatim; resumen opcional con un LLM | Fuentes abiertas, con atribución |
| minSdk / targetSdk | **minSdk 26** (Android 8.0) / **targetSdk 36** y **compileSdk 36** (Android 16) | Google Play exige target 36 para apps nuevas y actualizaciones desde el 31/08/2026 |
| Build | Gradle (Kotlin DSL) con catálogo de versiones `libs.versions.toml` | Estándar |

> **Nota para Claude Code:** las versiones exactas de las dependencias deben consultarse al crear el proyecto (Maven Google, Maven Central). No usar números de versión de memoria sin verificarlos.

---

## 4. Arquitectura

### 4.1 Vista general

```
┌──────────────────────────── UI (Compose) ────────────────────────────┐
│ Inicio · Nueva sesión · Sesión activa · Línea de tiempo · Mapa ·     │
│ Detalle de viaje · Revisión de medios · Exportar · Video · Ajustes   │
└───────────────▲──────────────────────────────────────────────────────┘
                │ StateFlow / eventos
┌───────────────┴──────────── ViewModels ──────────────────────────────┐
└───────────────▲──────────────────────────────────────────────────────┘
                │
┌───────────────┴──────────── Dominio (casos de uso) ──────────────────┐
│ StartSession · StopSession · AddVoiceNote · LinkMedia · BuildTimeline│
│ DetectStops · ExportTrip · EnrichPlaces · RenderVideo                │
└──────▲─────────────▲──────────────▲─────────────▲────────────────────┘
       │             │              │             │
┌──────┴────┐ ┌──────┴──────┐ ┌─────┴──────┐ ┌────┴──────────────────┐
│ Tracking  │ │ Media       │ │ Voice      │ │ Export / Video /      │
│ Service   │ │ Scanner +   │ │ Recorder + │ │ Enrichment (Workers)  │
│ (FGS)     │ │ Observer    │ │ Transcriber│ │                       │
└──────┬────┘ └──────┬──────┘ └─────┬──────┘ └────┬──────────────────┘
       └─────────────┴──────┬───────┴─────────────┘
                     ┌──────┴──────┐        ┌──────────────────────┐
                     │ Room (DB)   │        │ Archivos de la app   │
                     └─────────────┘        │ (audios, exports)    │
                                            └──────────────────────┘
```

### 4.2 Módulos de Gradle

Se busca un equilibrio: pocos módulos al principio, pero con la lógica pura separada para poder probarla en la JVM sin emulador.

```
bitacora-viaje/
├── app/                      # Android: UI, servicios, Workers, DI, permisos
├── core/
│   ├── model/                # Kotlin puro: entidades de dominio, enums
│   ├── geo/                  # Kotlin puro: haversine, filtros, simplificación,
│   │                         #   detección de paradas, interpolación, zonas horarias
│   ├── timeline/             # Kotlin puro: construcción de la línea de tiempo
│   └── export/               # Kotlin puro: GPX, KML, GeoJSON, JSON, CSV, HTML
├── data/                     # Android library: Room, DAOs, repositorios
├── desktop-renderer/         # Python: renderizador de video de escritorio
│   ├── pyproject.toml
│   └── bitacora_render/
├── tools/
│   ├── sample-trips/         # Viajes de ejemplo (GPX + fotos + JSON) para pruebas
│   └── gpx-replay/           # Script para reproducir un GPX en el emulador
└── docs/
```

Los módulos `core/*` son librerías **JVM puras** (plugin `kotlin("jvm")`). Así sus pruebas corren con `./gradlew :core:geo:test` en segundos, sin emulador.

### 4.3 Paquetes dentro de `app`

```
com.bitacoraviaje.app
├── di/                 # Módulos Hilt
├── ui/
│   ├── home/  newsession/  active/  timeline/  map/  trip/
│   ├── mediareview/  export/  video/  settings/  onboarding/
│   └── theme/  components/
├── tracking/           # TrackingService, LocationClient, perfiles, notificación
├── media/              # MediaScanner, MediaObserver, ExifReader, CameraX
├── voice/              # VoiceRecorder, Transcriber (interface + impls)
├── enrichment/         # Clientes Wikipedia, Nominatim, LLM; EnrichWorker
├── export/             # ExportWorker, empaquetado ZIP, Storage Access Framework
├── video/              # Motor Media3, VideoPlan, RenderWorker
├── map/                # MapRenderer (interface), GoogleMapRenderer, MapLibreRenderer
└── permissions/        # Flujos de permisos y textos explicativos
```

### 4.4 Flujo de datos de una sesión

1. El usuario elige el tipo de sesión y el medio. Se crea la etapa en la base con estado `ACTIVE`.
2. Se inicia `TrackingService` (servicio en primer plano, tipo `location`) con el perfil correspondiente.
3. Cada actualización de ubicación pasa por el filtro (§6.4) y se guarda en `track_point` en lotes.
4. Un `ContentObserver` sobre MediaStore detecta fotos y videos nuevos y los registra como pendientes de vincular.
5. El botón de nota de voz graba un archivo de audio y guarda la posición actual. Un Worker lo transcribe después.
6. Al terminar la etapa se ejecuta el procesamiento posterior: detección de paradas, simplificación, vinculación definitiva de medios, geocodificación inversa y enriquecimiento, si hay conexión.

---

## 5. Modelo de datos

### 5.1 Principios
- **IDs:** UUID en texto, generados en el dispositivo. Son estables al exportar e importar.
- **Tiempo:** siempre en **epoch milisegundos UTC** (`*_utc_ms`), más el **desfase horario** en minutos (`tz_offset_min`) y el identificador de zona (`tz_id`, por ejemplo `America/Argentina/Buenos_Aires`) donde haga falta. Un viaje puede cruzar husos horarios.
- **Coordenadas:** WGS84, grados decimales en `REAL` (double).
- **Archivos:** las fotos y los videos **no se copian** durante el viaje. Se guarda su URI de MediaStore y su huella para reencontrarlos. Se copian recién al exportar. Los audios sí son propios de la app y viven en su almacenamiento interno.
- **Versionado:** la base tiene migraciones de Room. El formato de exportación lleva `schema_version`.

### 5.2 Tablas (Room)

```sql
-- Viaje
CREATE TABLE trip (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT,
  created_utc_ms INTEGER NOT NULL,
  started_utc_ms INTEGER,
  ended_utc_ms INTEGER,
  cover_media_id TEXT,
  status TEXT NOT NULL            -- PLANNED | ACTIVE | PAUSED | FINISHED
);

-- Etapa / sesión
CREATE TABLE segment (
  id TEXT PRIMARY KEY,
  trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
  parent_segment_id TEXT REFERENCES segment(id), -- excursión/trekking anidado dentro de una etapa (§16.2)
  type TEXT NOT NULL,             -- TRIP_START | STAGE | EXCURSION | TREKKING
  transport TEXT,                 -- WALK | BIKE | CAR | BUS | TRAIN | BOAT | PLANE | MIXED
  title TEXT,
  intent_text TEXT,               -- lo que el usuario dijo/escribió al iniciar
  started_utc_ms INTEGER NOT NULL,
  ended_utc_ms INTEGER,
  tz_id TEXT NOT NULL,
  sampling_profile TEXT NOT NULL,
  status TEXT NOT NULL,           -- ACTIVE | PAUSED | FINISHED | RECOVERED
  -- estadísticas calculadas al cerrar
  distance_m REAL, moving_time_ms INTEGER, total_time_ms INTEGER,
  max_speed_mps REAL, avg_moving_speed_mps REAL,
  elevation_gain_m REAL, elevation_loss_m REAL,
  min_alt_m REAL, max_alt_m REAL,
  bbox_min_lat REAL, bbox_min_lon REAL, bbox_max_lat REAL, bbox_max_lon REAL
);
CREATE INDEX idx_segment_trip ON segment(trip_id, started_utc_ms);

-- Pausas dentro de una etapa
CREATE TABLE segment_pause (
  id TEXT PRIMARY KEY,
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  paused_utc_ms INTEGER NOT NULL,
  resumed_utc_ms INTEGER
);

-- Puntos de recorrido (tabla grande)
CREATE TABLE track_point (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  time_utc_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL,
  alt_m REAL,                     -- altitud elipsoidal del GPS
  alt_msl_m REAL,                 -- altitud sobre el nivel del mar si está disponible (API 34+)
  h_accuracy_m REAL, v_accuracy_m REAL,
  speed_mps REAL, speed_accuracy_mps REAL,
  bearing_deg REAL,
  provider TEXT,                  -- fused | gps | network
  activity TEXT,                  -- de Activity Recognition, si está habilitado
  is_filtered_out INTEGER NOT NULL DEFAULT 0, -- 1 si el filtro lo descartó
  is_simplified_keep INTEGER NOT NULL DEFAULT 1 -- para la versión simplificada
);
CREATE INDEX idx_tp_segment_time ON track_point(segment_id, time_utc_ms);

-- Paradas detectadas
CREATE TABLE stop (
  id TEXT PRIMARY KEY,
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  start_utc_ms INTEGER NOT NULL, end_utc_ms INTEGER NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL, radius_m REAL NOT NULL,
  place_id TEXT REFERENCES place(id),
  user_label TEXT
);

-- Fotos y videos
CREATE TABLE media_item (
  id TEXT PRIMARY KEY,
  segment_id TEXT REFERENCES segment(id) ON DELETE SET NULL,
  trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,             -- PHOTO | VIDEO
  source TEXT NOT NULL,           -- MEDIASTORE | APP_CAMERA | GOOGLE_PHOTOS_PICKER | IMPORT
  content_uri TEXT,               -- content://media/...
  mediastore_id INTEGER,
  google_photos_id TEXT,
  display_name TEXT, mime_type TEXT,
  size_bytes INTEGER, width INTEGER, height INTEGER,
  duration_ms INTEGER,            -- videos
  orientation_deg INTEGER,
  taken_utc_ms INTEGER NOT NULL,
  taken_time_source TEXT NOT NULL,-- EXIF_WITH_OFFSET | EXIF_LOCAL | MEDIASTORE | FILE_MTIME
  exif_lat REAL, exif_lon REAL,
  linked_lat REAL, linked_lon REAL,
  link_method TEXT,               -- EXIF | TRACK_INTERPOLATED | TRACK_NEAREST | MANUAL | NONE
  link_confidence REAL,           -- 0..1
  link_time_gap_ms INTEGER,       -- distancia temporal al punto usado
  sha256 TEXT,                    -- huella para reencontrar el archivo
  phash TEXT,                     -- hash perceptual para detectar duplicados
  sharpness_score REAL,           -- para elegir fotos para el video
  user_caption TEXT,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  is_excluded INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_media_trip_time ON media_item(trip_id, taken_utc_ms);
CREATE UNIQUE INDEX idx_media_ms ON media_item(mediastore_id) WHERE mediastore_id IS NOT NULL;

-- Notas de voz
CREATE TABLE voice_note (
  id TEXT PRIMARY KEY,
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  started_utc_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
  lat REAL, lon REAL, h_accuracy_m REAL,
  audio_path TEXT NOT NULL,       -- relativo a filesDir/audio/
  audio_format TEXT NOT NULL,     -- m4a-aac | wav-pcm16
  transcript TEXT,
  transcript_edited TEXT,         -- versión corregida por el usuario
  transcript_lang TEXT,           -- es-AR, etc.
  transcript_engine TEXT,         -- WHISPER_LOCAL | ANDROID_SPEECH | CLOUD_xxx
  transcript_status TEXT NOT NULL,-- PENDING | RUNNING | DONE | FAILED
  transcript_segments_json TEXT   -- marcas de tiempo por frase (para subtítulos)
);

-- Notas de texto y marcadores
CREATE TABLE text_note (
  id TEXT PRIMARY KEY,
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  time_utc_ms INTEGER NOT NULL,
  lat REAL, lon REAL,
  kind TEXT NOT NULL,             -- NOTE | MARKER | HIGHLIGHT
  text TEXT
);

-- Lugares
CREATE TABLE place (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL,
  kind TEXT,                      -- city | town | poi | peak | trailhead | museum | ...
  country_code TEXT, admin1 TEXT, admin2 TEXT, locality TEXT,
  osm_type TEXT, osm_id INTEGER,
  wikidata_id TEXT, wikipedia_title TEXT, wikipedia_lang TEXT,
  source TEXT NOT NULL,           -- NOMINATIM | ANDROID_GEOCODER | WIKIPEDIA | OVERPASS | MANUAL
  fetched_utc_ms INTEGER
);

-- Información de internet sobre un lugar
CREATE TABLE enrichment (
  id TEXT PRIMARY KEY,
  place_id TEXT NOT NULL REFERENCES place(id) ON DELETE CASCADE,
  lang TEXT NOT NULL,
  title TEXT,
  summary TEXT NOT NULL,          -- texto corto para la bitácora y el video
  source_name TEXT NOT NULL,      -- Wikipedia, Wikidata...
  source_url TEXT NOT NULL,
  license TEXT,                   -- CC BY-SA 4.0, CC0...
  thumbnail_url TEXT,
  llm_rewritten INTEGER NOT NULL DEFAULT 0,
  user_approved INTEGER NOT NULL DEFAULT 0,
  fetched_utc_ms INTEGER NOT NULL
);

-- Relación lugar ↔ etapa (qué lugares se visitaron en cada etapa)
CREATE TABLE segment_place (
  segment_id TEXT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  place_id TEXT NOT NULL REFERENCES place(id) ON DELETE CASCADE,
  first_seen_utc_ms INTEGER NOT NULL,
  dwell_ms INTEGER,
  relevance REAL,
  PRIMARY KEY (segment_id, place_id)
);

-- Videos generados
CREATE TABLE video_render (
  id TEXT PRIMARY KEY,
  trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
  scope TEXT NOT NULL,            -- TRIP | SEGMENT
  segment_id TEXT,
  plan_json TEXT NOT NULL,        -- el VideoPlan usado (§13.3)
  output_uri TEXT,
  status TEXT NOT NULL,           -- PENDING | RUNNING | DONE | FAILED | CANCELLED
  progress REAL,
  error TEXT,
  created_utc_ms INTEGER NOT NULL
);

-- Registro de exportaciones
CREATE TABLE export_job (
  id TEXT PRIMARY KEY,
  trip_id TEXT NOT NULL,
  segment_id TEXT,
  options_json TEXT NOT NULL,
  output_uri TEXT,
  status TEXT NOT NULL,
  created_utc_ms INTEGER NOT NULL
);
```

### 5.3 Volumen estimado
- A 1 punto cada 5 s durante 10 h: unos 7.200 puntos por día y alrededor de 1 MB en SQLite.
- Un viaje de 30 días: unos 216.000 puntos. Room lo maneja sin problema si las inserciones van en lote y las consultas usan los índices por `(segment_id, time_utc_ms)`.

---

## 6. Módulo de recorrido (tracking GPS)

### 6.1 Servicio en primer plano
- `TrackingService` extiende `LifecycleService`. Se declara en el manifiesto con `android:foregroundServiceType="location"`.
- Se inicia con `ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)`.
- **Debe iniciarse mientras la app está visible**, desde el botón "Iniciar". Desde Android 12 no se pueden iniciar servicios en primer plano desde segundo plano, salvo excepciones.
- La notificación persistente muestra: tipo de etapa, tiempo transcurrido, distancia y velocidad actual. Tiene acciones **Pausar/Reanudar**, **Nota de voz** (abre una actividad de grabación; ver §8.2) y **Terminar**.
- El estado vivo (posición, distancia, velocidad) se publica en un `StateFlow` de un repositorio singleton. La UI lo observa.
- Con el servicio en primer plano iniciado desde la app visible **no hace falta `ACCESS_BACKGROUND_LOCATION`**. Se recomienda no pedirlo en la versión 1: simplifica mucho la revisión de Google Play.

### 6.2 Perfiles de muestreo
`LocationRequest.Builder(priority, intervalMs)` con:

| Perfil | Prioridad | Intervalo | Mín. intervalo | Distancia mín. | Agrupado (maxUpdateDelay) |
|---|---|---|---|---|---|
| `TREKKING_HIGH` | HIGH_ACCURACY | 3 s | 2 s | 3 m | 0 |
| `WALK` | HIGH_ACCURACY | 5 s | 3 s | 5 m | 0 |
| `BIKE` | HIGH_ACCURACY | 4 s | 2 s | 8 m | 0 |
| `VEHICLE` | HIGH_ACCURACY | 10 s | 5 s | 30 m | 30 s |
| `PLANE` | BALANCED_POWER | 60 s | 30 s | 500 m | 120 s |
| `SAVER` | BALANCED_POWER | 30 s | 15 s | 50 m | 60 s |

- El perfil se puede cambiar durante la sesión. Ejemplo: se baja del micro y empieza a caminar.
- **Modo adaptativo (opcional, fase 3):** con la Activity Recognition API (permiso `ACTIVITY_RECOGNITION`) se cambia de perfil según la actividad detectada. Si el usuario está quieto (`STILL`) más de 3 minutos, se baja la frecuencia; al moverse se sube.
- **Batería baja (<15 %):** se pasa a `SAVER` automáticamente y se avisa en la notificación.

### 6.3 Escritura en la base
- Los puntos se acumulan en memoria y se insertan **en lote cada 10 puntos o cada 30 s**, lo que ocurra primero. Al pausar o terminar se vacía el búfer.
- Si el proceso muere, se pierden como máximo esos puntos del búfer.

### 6.4 Filtrado (en `core/geo`)
Cada punto entrante pasa por estas reglas, en orden:
1. **Precisión:** se descarta si `h_accuracy_m` supera un umbral por perfil (25 m caminando, 50 m en vehículo). Se guarda igual con `is_filtered_out = 1`, para depuración.
2. **Saltos imposibles:** se descarta si la velocidad implícita respecto del último punto válido supera un máximo por medio (a pie 4 m/s sostenido, auto 70 m/s, avión 300 m/s).
3. **Ruido en reposo:** si el usuario está detenido (velocidad < 0,5 m/s durante N puntos), los puntos dentro de un radio de 15 m no suman distancia.
4. **Suavizado (opcional):** filtro de Kalman 2D simple, solo para la visualización. El dato crudo se conserva.

### 6.5 Métricas
- **Distancia:** suma de haversine entre puntos válidos.
- **Tiempo en movimiento:** suma de intervalos con velocidad > 0,5 m/s (a pie) o > 1,5 m/s (vehículo).
- **Desnivel:** se usa la altitud MSL si existe, o la elipsoidal si no. Se suaviza con media móvil de 5 puntos y se ignoran cambios menores a 3 m (histéresis), para no inflar el acumulado.
- **Velocidad máxima:** percentil 98 de la velocidad, no el máximo crudo, para evitar picos espurios.

### 6.6 Detección de paradas
Algoritmo de permanencia (*stay point*):
- Se recorre la etapa buscando ventanas donde todos los puntos estén dentro de un radio R (por defecto 50 m) durante al menos T (por defecto 5 min; 10 min en vehículo).
- Cada ventana genera un `stop` con su centroide, su radio y su intervalo.
- Las paradas se geocodifican (§11) y son candidatas naturales para capítulos del video y entradas de la bitácora.

### 6.7 Simplificación
- **Douglas-Peucker** con tolerancia por escala: 5 m para la vista de detalle y 30 m para la vista general y el video.
- Se marca `is_simplified_keep` y se generan polilíneas para el mapa, sin borrar puntos.

### 6.8 "Identificar ruta o trekking"
La versión 1 lo resuelve así:
- **Clasificación de la etapa:** el tipo lo declara el usuario. La app lo valida con la velocidad media y la actividad detectada (por ejemplo, sugiere "¿fue en vehículo?" si la velocidad media supera 25 km/h).
- **Nombre de la ruta:** con geocodificación inversa de puntos muestreados del recorrido, se extraen nombres de rutas o calles (`road` en Nominatim) y de localidades. Resultado: "Ruta 40, de El Calafate a El Chaltén".
- **Senderos (trekking):** consulta Overpass por `highway=path|footway|track` con `name` o relaciones `route=hiking` cerca del recorrido. Si un tramo de sendero con nombre coincide con más del 60 % del recorrido (puntos a menos de 30 m), se etiqueta con ese nombre, por ejemplo "Sendero a Laguna de los Tres".
- **Map matching** (ajustar el recorrido a la red vial) queda como mejora futura con OSRM o Valhalla.

### 6.9 Recuperación ante fallos
- Al abrir la app, si hay una etapa en `ACTIVE` pero el servicio no corre, se ofrece **Reanudar** o **Cerrar etapa**. Si se cierra, queda en estado `RECOVERED`.
- Si el sistema reinicia el servicio (`START_STICKY`), este lee la etapa activa de la base y continúa.
- **Reinicio del teléfono:** no se puede iniciar automáticamente un servicio de ubicación desde `BOOT_COMPLETED` en versiones recientes. Se muestra una notificación "Tu registro se interrumpió, tocá para reanudar".

---

## 7. Módulo de fotos y videos

### 7.1 La situación de Google Fotos (importante)
- Desde el **31 de marzo de 2025**, la **Library API** de Google Fotos solo permite listar y leer fotos y álbumes **creados por la propia app**. Ya no se puede leer la biblioteca completa del usuario.
- Para elegir fotos de toda la biblioteca existe la **Picker API**: el usuario abre un selector de Google Fotos y elige a mano. No sirve para vincular automáticamente en segundo plano.
- **Conclusión:** la fuente principal son las **fotos locales del teléfono (MediaStore)**. Las fotos de la cámara existen localmente aunque se respalden en Google Fotos, salvo que el usuario haya liberado espacio.

### 7.2 Fuentes de medios (en orden de prioridad)
1. **Cámara propia de la app (CameraX).** Vínculo perfecto: se conoce el instante y la posición exacta al sacar la foto. Las fotos se guardan en MediaStore, en `Pictures/BitacoraViaje/<viaje>/`, con EXIF GPS escrito. El usuario puede seguir usando la cámara que prefiera; esta es una opción adicional.
2. **MediaStore automático.** Fotos y videos de cualquier cámara del teléfono, detectados por rango de tiempo de la etapa.
3. **Google Fotos Picker (opcional, fase 3).** Para fotos sacadas con **otro dispositivo** (una cámara réflex o el celular de un acompañante) que ya están en Google Fotos. Requiere OAuth y un proyecto en Google Cloud.
4. **Importación manual.** Selector de archivos del sistema, para tarjetas SD o fotos compartidas por WhatsApp. Atención: WhatsApp elimina el EXIF, así que esas fotos se vinculan solo por hora, y la hora puede ser la de recepción.
5. **Subida opcional a Google Fotos (fase 5).** Con la Library API, la app puede crear un álbum "Bitácora: <viaje>" y subir los medios vinculados. Esto sí está permitido, porque son medios creados por la app.

### 7.3 Detección en MediaStore
- **En vivo:** un `ContentObserver` registrado sobre `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` y `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` mientras dura la sesión. Ante un cambio se consulta lo nuevo desde la última verificación.
- **Barrido de reconciliación:** al terminar la etapa y en cada apertura del detalle del viaje, se consulta MediaStore filtrando por `DATE_TAKEN` entre el inicio y el fin de la etapa (con un margen de ±10 min). Este barrido cubre lo que el observador no vio.
- **Columnas:** `_ID`, `DATE_TAKEN`, `DATE_ADDED`, `DATE_MODIFIED`, `DISPLAY_NAME`, `MIME_TYPE`, `SIZE`, `WIDTH`, `HEIGHT`, `ORIENTATION`, `DURATION` (video), `RELATIVE_PATH`, `BUCKET_DISPLAY_NAME`.
- **Exclusiones configurables:** capturas de pantalla, carpetas de WhatsApp y descargas.

### 7.4 Permisos de medios
- **Android 13+:** `READ_MEDIA_IMAGES` y `READ_MEDIA_VIDEO`.
- **Android 14+:** el usuario puede conceder **acceso parcial** (solo fotos seleccionadas, `READ_MEDIA_VISUAL_USER_SELECTED`). La app tiene que detectarlo y explicar que con acceso parcial no puede vincular automáticamente, ofreciendo "Seleccionar más fotos".
- **Android 12 o inferior:** `READ_EXTERNAL_STORAGE` con `maxSdkVersion="32"`.
- **`ACCESS_MEDIA_LOCATION`:** imprescindible para leer las coordenadas EXIF. Desde Android 10 el sistema **borra el GPS del EXIF** de cualquier imagen leída por URI, salvo que la app tenga este permiso **y** pida el original con `MediaStore.setRequireOriginal(uri)`.
- **El Photo Picker del sistema elimina la ubicación siempre**, incluso con `ACCESS_MEDIA_LOCATION`. Por eso no sirve como fuente principal para esta app.
- **Política de Google Play:** `READ_MEDIA_IMAGES` y `READ_MEDIA_VIDEO` solo se aprueban si el acceso amplio a fotos es parte central de la app. Hay que justificarlo en la Play Console: la app vincula automáticamente las fotos del viaje con el recorrido. Ver §15.

### 7.5 Lectura de hora y ubicación de una foto
**Hora (`taken_utc_ms`), en orden de preferencia:**
1. EXIF `DateTimeOriginal` + `OffsetTimeOriginal` (si existe el offset): hora UTC exacta. Fuente `EXIF_WITH_OFFSET`.
2. EXIF `DateTimeOriginal` sin offset: es hora local del dispositivo. Se convierte con la zona horaria de la etapa vigente en ese momento. Fuente `EXIF_LOCAL`.
3. `MediaStore.DATE_TAKEN` (ms UTC). Fuente `MEDIASTORE`.
4. Fecha de modificación del archivo. Fuente `FILE_MTIME`, con baja confianza.

**Ubicación:**
- `ExifInterface(inputStream).latLong` sobre el stream original. Si está en (0,0), se considera ausente: es lo que devuelve el sistema cuando redacta la ubicación.

**Videos:** usar `MediaMetadataRetriever` (`METADATA_KEY_LOCATION`, formato ISO-6709 `+lat+lon/`, y `METADATA_KEY_DATE`).

### 7.6 Algoritmo de vinculación (en `core/geo`)
Para cada medio con su `taken_utc_ms`:
1. Buscar la etapa cuyo intervalo `[inicio-10min, fin+10min]` contiene la hora. Si hay varias (excursión dentro de una etapa), gana la de intervalo más corto.
2. Si el medio tiene **EXIF GPS válido**:
   - Si existe un punto del recorrido a menos de 2 min, comparar la distancia. Si están a menos de 500 m, `link_method = EXIF` con confianza 1,0. Si están más lejos, se marca para revisión: puede ser una foto de otra cámara con la hora mal configurada.
   - Si no hay recorrido cerca, se usa EXIF con confianza 0,9.
3. Sin EXIF GPS, **interpolar** entre el punto anterior y el siguiente del recorrido:
   - Si ambos existen y el hueco es menor a 5 min: interpolación lineal en lat/lon según el tiempo. `TRACK_INTERPOLATED`, confianza 0,8.
   - Si solo hay uno a menos de 10 min: `TRACK_NEAREST`, con confianza decreciente según la distancia temporal.
   - Si no hay nada: `NONE`. El medio va a la pantalla de **revisión** para ubicarlo a mano en el mapa.
4. **Corrección de desfase de reloj de la cámara:** cuando la cámara tiene la hora mal (típico en cámaras réflex), el usuario elige una foto y la ubica a mano en el mapa. La app calcula el desfase y lo aplica a todas las fotos de esa cámara (agrupadas por `Make`/`Model` del EXIF).

### 7.7 Calidad y duplicados (para el video)
- **pHash** (hash perceptual de 64 bits): fotos con distancia de Hamming ≤ 6 se agrupan como ráfaga o duplicado y se elige una.
- **Nitidez:** varianza del laplaciano sobre una miniatura de 512 px. Las fotos movidas se descartan para el video.
- **Favoritas:** las marcadas por el usuario siempre entran.

### 7.8 Cámara integrada (CameraX)
- Pantalla simple: foto, video y un botón para dictar una nota "sobre esta foto". La nota queda asociada al medio.
- La foto se escribe en MediaStore con EXIF GPS de la última ubicación y `OffsetTimeOriginal`.
- Requiere el permiso `CAMERA`, y `RECORD_AUDIO` para video con sonido.

---

## 8. Módulo de bitácora por voz

### 8.1 Grabación
- Botón grande en la pantalla de sesión activa. Modos **mantener presionado para hablar** o **tocar para empezar y tocar para terminar** (configurable).
- Al empezar se toma la posición actual. Si la última tiene más de 30 s, se pide una nueva.
- Formato: **AAC en M4A, mono, 16 kHz, 32 kbps** con `MediaRecorder` (unos 240 KB por minuto). Whisper necesita PCM de 16 kHz mono, así que se decodifica con `MediaExtractor` y `MediaCodec` antes de transcribir. Alternativa: grabar WAV PCM16 con `AudioRecord`, más simple para Whisper pero 8 veces más pesado.
- Duración máxima por nota: 5 minutos (configurable).
- Respuesta háptica al empezar y al terminar.

### 8.2 Grabar desde la notificación o con la pantalla bloqueada
- Desde Android 11, un servicio no puede acceder al micrófono si se inició desde segundo plano (*while-in-use*). La acción **Nota de voz** de la notificación abre una **actividad translúcida** (`VoiceNoteActivity`, con `showWhenLocked` y `turnScreenOn`) que graba y se cierra sola. Al estar en primer plano, el acceso al micrófono queda permitido.
- Alternativas: **mosaico de Configuración rápida** (`TileService`) que abre la misma actividad, y **widget de pantalla de inicio**.
- Si se quiere grabar sin abrir actividad, hay que agregar el tipo `microphone` al servicio en primer plano, con el permiso `FOREGROUND_SERVICE_MICROPHONE`. Para la versión 1 se recomienda la actividad translúcida, que es más simple y más robusta.

### 8.3 Transcripción
Interfaz común:

```kotlin
interface Transcriber {
    val id: String                       // "whisper-local", "android-speech", "cloud-xxx"
    suspend fun isAvailable(): Boolean
    suspend fun transcribe(audio: File, languageTag: String): TranscriptResult
}
data class TranscriptResult(
    val text: String,
    val segments: List<TranscriptSegment>,   // inicio/fin en ms + texto (para subtítulos)
    val language: String?,
)
```

Implementaciones:
1. **`WhisperLocalTranscriber` (por defecto):** whisper.cpp compilado con el NDK (hay un ejemplo oficial para Android en el repositorio de whisper.cpp). Modelo multilingüe `base` (≈150 MB) o `small` (≈470 MB), que se **descarga en el primer uso** desde Ajustes, no se incluye en el APK. Corre en un `CoroutineWorker` con restricciones de batería no baja y, opcionalmente, cargando. Devuelve segmentos con marcas de tiempo.
2. **`AndroidSpeechTranscriber`:** el `SpeechRecognizer` del sistema, con reconocimiento en el dispositivo en API 31+ (`createOnDeviceSpeechRecognizer`). Limitación: transcribe **mientras se habla desde el micrófono**, no desde un archivo. Se usaría en modo "dictado en vivo", grabando el audio en paralelo solo si el dispositivo lo permite. Es útil como vista previa inmediata. *Verificar en la documentación vigente si la versión de Android en uso admite una fuente de audio externa.*
3. **`CloudTranscriber` (opcional):** un servicio de transcripción en la nube con clave del usuario, configurable en Ajustes. Solo con conexión y consentimiento explícito.

- **Idioma:** por defecto `es` (con variante `es-AR`), configurable. Whisper detecta el idioma si se elige "automático".
- **Edición:** el usuario puede corregir el texto. Se guarda en `transcript_edited` y se conserva el original.
- **Reintentos:** si falla, queda en `FAILED` con un botón "Reintentar"; WorkManager reintenta con espera exponencial.

### 8.4 Notas de texto y marcadores
- Botón **Marcar lugar** (con texto opcional) para registrar "acá había un mirador increíble" sin hablar.
- Botón **Destacado**: marca el momento actual como importante. Esos momentos tienen prioridad en el video.

---

## 9. Línea de tiempo unificada

- La construye `core/timeline` como función pura: `buildTimeline(segment, points, stops, media, voiceNotes, textNotes, places, enrichments) -> List<TimelineEvent>`.
- Tipos de evento: `SegmentStart`, `SegmentEnd`, `Pause`, `Resume`, `TransportChange`, `StopArrived`, `StopLeft`, `Photo`, `Video`, `VoiceNote`, `TextNote`, `Highlight`, `PlaceVisited` y `DayChange` (se agrega al cruzar la medianoche local).
- **Agrupación:** las fotos dentro de 2 minutos y 100 m se agrupan en un "momento" con carrusel.
- **Resúmenes intermedios:** entre dos eventos se calcula "recorriste 12,4 km en 2 h 10 min".
- **Vista:** lista vertical por día. Cada evento muestra hora local, ícono, miniatura o texto y lugar. Al tocar un evento, el mapa se centra en ese punto.
- **Sincronización mapa ↔ lista:** desplazarse por la lista mueve un marcador en el mapa; tocar el recorrido en el mapa salta al evento más cercano en el tiempo.

---

## 10. Mapas

### 10.1 En la app
- Interfaz `MapRenderer` con dos implementaciones intercambiables desde Ajustes:
  - **Google Maps** (Maps SDK for Android + `maps-compose`). Requiere una **clave de API de Google Cloud con facturación habilitada**. El SDK de Android tiene una cuota gratuita amplia, pero hay que configurar la clave y restringirla al paquete y al certificado de la app.
  - **MapLibre Native** con teselas OSM o de un proveedor con clave (MapTiler, Stadia). Permite **mapas offline**: descargar una región antes del viaje, algo muy útil en zonas sin señal como la montaña o la Patagonia.
- Capas: recorrido (coloreado por velocidad o por medio de transporte), marcadores de fotos (miniaturas agrupadas en clústeres), notas de voz, paradas y posición actual.
- Durante la sesión el mapa sigue la posición, salvo que el usuario lo mueva a mano.

### 10.2 "Crear el mapa en Google Maps"
Google no ofrece una API pública para crear mapas de **Mis Mapas (My Maps)** programáticamente. El camino es:
1. Exportar **KML/KMZ** con el recorrido, las paradas y los marcadores de fotos y notas, con descripción y miniatura.
2. El usuario lo **importa en Google My Maps** (mymaps.google.com → Crear mapa → Importar) o lo abre en **Google Earth**.
3. Opcional: compartir el KML directamente a Google Earth desde el teléfono con un `Intent`.

La guía de usuario explica estos pasos y el exportador genera un KML compatible con My Maps (§12.3).

### 10.3 Mapas en el video
- Las condiciones de uso de Google Maps restringen el uso de capturas de su mapa fuera de la app. **Para el video se usa cartografía OSM**, a través de un proveedor de teselas con clave y atribución "© OpenStreetMap contributors".
- **No** usar `tile.openstreetmap.org` para renderizar video: su política de uso prohíbe la descarga masiva.

---

## 11. Enriquecimiento con información de internet

### 11.1 Qué se busca
Para cada **parada** relevante, cada **localidad** atravesada y cada lugar asociado a fotos:
- Nombre del lugar y jerarquía administrativa (localidad, provincia, país).
- Un resumen breve (2-4 oraciones) de por qué es interesante.
- Datos curiosos para tarjetas del video: altitud de un pico, año de fundación de un pueblo, etc.
- Imagen de referencia, opcional y solo con licencia libre.

### 11.2 Fuentes
1. **Geocodificación inversa:** **Nominatim** (`/reverse?format=jsonv2&lat=..&lon=..&zoom=..&accept-language=es`). La política de uso exige un User-Agent identificable, como máximo 1 pedido por segundo y caché. Alternativa: `android.location.Geocoder`, sin costo pero con resultados variables según el dispositivo.
2. **Wikipedia GeoSearch:** `https://es.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=LAT|LON&gsradius=5000&gslimit=10&format=json`. Devuelve artículos cercanos con su distancia.
3. **Resumen de Wikipedia:** `https://es.wikipedia.org/api/rest_v1/page/summary/{título}` (campo `extract`, miniatura, coordenadas). Si no hay artículo en español, se prueba en inglés y se marca el idioma.
4. **Wikidata:** a partir del `wikidata_id`, datos estructurados como población (P1082), altitud (P2044), fecha de fundación (P571) o patrimonio UNESCO (P1435).
5. **Overpass API (OSM):** puntos de interés cercanos (`tourism=*`, `historic=*`, `natural=peak`) y senderos (§6.8).

### 11.3 Selección y ranking
- Se descartan artículos a más de X metros del lugar (X depende del tipo: 300 m para un punto de interés, 5 km para una localidad).
- Se priorizan lugares donde hubo **permanencia larga**, **fotos** o **notas**.
- Se puntúa por la distancia y la "importancia", medida como cantidad de enlaces a otros idiomas (*sitelinks*) en Wikidata.
- Límite por etapa configurable (por defecto 5 lugares).

### 11.4 Reescritura con un LLM (opcional)
- Si el usuario configura una clave de API (por ejemplo de la API de Claude), el `extract` de Wikipedia se puede reescribir en tono de bitácora, en el idioma elegido y con una longitud fija, **sin inventar datos**. El prompt exige usar solo el texto fuente provisto.
- Se guarda `llm_rewritten = 1`. Siempre se conserva el texto y la URL de origen.
- El usuario aprueba o descarta cada nota antes de que entre al video (`user_approved`).

### 11.5 Licencias y atribución
- Los textos de Wikipedia son **CC BY-SA 4.0**: la bitácora exportada y los créditos del video deben citar el artículo y la licencia.
- Los datos de OSM están bajo **ODbL**: atribución "© OpenStreetMap contributors".
- Wikidata es **CC0**.

### 11.6 Funcionamiento sin conexión
- El enriquecimiento corre en un `EnrichWorker` con la restricción `NetworkType.CONNECTED`. Si no hay red, queda encolado y se ejecuta al volver la conexión.
- Todas las respuestas se cachean en la base (`place`, `enrichment`).

---

## 12. Exportación a PC

### 12.1 Paquete de exportación
Un ZIP por viaje (o por etapa) con esta estructura:

```
Patagonia-2026_2026-10-11/
├── LEEME.txt                       # cómo abrir cada archivo
├── bitacora.html                   # diario navegable, autocontenido, con mapa (Leaflet)
├── bitacora.md                     # el mismo diario en Markdown
├── trip.json                       # TODO el viaje en JSON (esquema §12.2)
├── tracks/
│   ├── viaje-completo.gpx          # todas las etapas como <trk> separados
│   ├── 01_2026-10-02_etapa.gpx     # una por etapa
│   ├── viaje.kml                   # para Google Earth / My Maps
│   ├── viaje.kmz                   # KML + miniaturas
│   └── viaje.geojson
├── tablas/
│   ├── puntos.csv                  # todos los puntos
│   ├── medios.csv
│   ├── notas.csv
│   └── lugares.csv
├── media/
│   ├── fotos/2026-10-02/IMG_20261002_101500.jpg
│   └── videos/2026-10-02/VID_....mp4
├── audio/
│   └── 2026-10-02/nota_101732.m4a
├── subtitulos/
│   └── notas.srt                   # notas de voz como subtítulos con hora real
└── video/
    └── resumen.mp4                 # si ya se generó
```

Opciones del usuario: incluir o no los medios originales (pueden ser muchos GB), reducir las fotos a 2048 px, incluir audios, e incluir puntos filtrados (para depuración).

### 12.2 `trip.json` (esquema)
- `schema_version: 1`, `app_version` y `exported_utc`.
- `trip {...}` y `segments[] {... points[] (opcional, o referencia al GPX), stops[], media[], voice_notes[], text_notes[]}`, `places[]`, `enrichments[]`.
- En `media[]` cada elemento incluye `file` con la ruta relativa dentro del ZIP.
- Debe haber un **JSON Schema** (`docs/trip.schema.json`), y el exportador valida su salida contra él en las pruebas.
- El renderizador de escritorio (§13.5) **consume exactamente este formato**, que es el contrato entre ambos mundos.

### 12.3 Detalles de formatos
- **GPX 1.1:** un `<trk>` por etapa y un `<trkseg>` por tramo entre pausas. Cada `<trkpt>` lleva `<ele>`, `<time>` (ISO 8601 UTC) y extensiones propias `<bv:speed>`, `<bv:hacc>`. Las fotos, notas y paradas van como `<wpt>` con `<name>`, `<desc>` y `<link>` al archivo. Validar contra el XSD oficial de GPX 1.1 en las pruebas.
- **KML (compatible con My Maps):** una `Folder` por etapa, con el recorrido como `LineString` y estilo de color por etapa, y `Placemark` para fotos y notas con `description` en HTML simple. My Maps tiene límites (por ejemplo, de capas y de elementos por capa), así que hay que simplificar el recorrido (§6.7) y no superarlos. **Verificar los límites vigentes de My Maps.**
- **KMZ:** el KML más las miniaturas (`files/`) referenciadas en las descripciones, para Google Earth.
- **GeoJSON:** una `FeatureCollection` con `LineString` por etapa y `Point` para cada evento.
- **CSV:** UTF-8 con BOM (para que Excel lo abra bien), separador `,` y horas en UTC y en hora local.
- **HTML:** un solo archivo con Leaflet desde CDN y los datos embebidos. Muestra el mapa, la línea de tiempo y las fotos referenciadas con rutas relativas. Se abre con doble clic en la PC.
- **SRT:** cada nota de voz es un subtítulo, con su hora relativa al inicio de la etapa.

### 12.4 Cómo llega a la PC
1. **Guardar en el teléfono** con el Storage Access Framework (`ACTION_CREATE_DOCUMENT` para el ZIP, o `ACTION_OPEN_DOCUMENT_TREE` para elegir una carpeta). Después se copia por cable USB.
2. **Compartir** con un `Intent.ACTION_SEND`: Google Drive, correo, Nearby Share/Quick Share, etc.
3. **Exportar a una carpeta pública** `Download/BitacoraViaje/`, visible por USB (MTP) sin pasos extra.
4. **Fase futura:** subida directa a Google Drive mediante su API.

La exportación corre en un `ExportWorker` (servicio en primer plano tipo `dataSync`, porque puede tardar), con progreso en una notificación. **Verificar** las restricciones de tiempo vigentes para el tipo `dataSync` en Android 15+.

### 12.5 Importación
- La app puede **importar** un `trip.json` + ZIP, ya sea para restaurar en otro teléfono o para recuperar un respaldo.
- Puede importar un **GPX externo** de otra app o un reloj GPS como una etapa nueva.

---

## 13. Generación del video resumen

### 13.1 Estructura narrativa del video
1. **Apertura (5 s):** título del viaje, fechas y una foto de portada con zoom suave.
2. **Mapa general (6-8 s):** el recorrido completo se dibuja animado sobre un mapa que encuadra todo el viaje.
3. **Por cada etapa (capítulo):**
   - Tarjeta de título: "Día 3 · Trekking a Laguna de los Tres", con la fecha.
   - Zoom del mapa a la etapa y animación del recorrido de esa etapa, con un marcador que avanza.
   - Momentos en orden cronológico: fotos (3-4 s cada una, con efecto Ken Burns), fragmentos de video (recortados a un máximo de 6 s), notas de voz (como subtítulo sobre la foto más cercana, o con el audio original, según la opción elegida) y **tarjetas de lugar** con el resumen de internet.
   - Pie con estadísticas: distancia, desnivel y tiempo.
4. **Cierre:** estadísticas totales (km, días, fotos, localidades, punto más alto), créditos de fuentes y licencias, y música.

### 13.2 Parámetros del usuario
- Alcance: todo el viaje, una etapa o un rango de fechas.
- Duración objetivo: 1, 3, 5 o 10 minutos. El planificador elige cuántas fotos entran.
- Formato: 16:9 (1920×1080) o 9:16 (1080×1920, para redes sociales).
- Voz: subtítulos, audio original o ambos.
- Música: archivo del usuario o ninguna. La app no incluye música con derechos de autor.
- Incluir o no notas de internet, el mapa animado y los videos.

### 13.3 `VideoPlan` (independiente del motor)
Primero se genera un **plan declarativo** en JSON, separado de cómo se renderiza:

```json
{
  "schema_version": 1,
  "output": {"width": 1920, "height": 1080, "fps": 30, "format": "mp4-h264-aac"},
  "music": {"file": "musica.mp3", "volume": 0.4, "duck_under_voice": true},
  "scenes": [
    {"type": "title", "duration_ms": 5000, "title": "Patagonia 2026", "subtitle": "2 al 18 de octubre", "background_media": "m-123"},
    {"type": "map_route", "duration_ms": 7000, "segments": ["s-1","s-2"], "style": "overview"},
    {"type": "chapter", "duration_ms": 2500, "title": "Día 3 · Trekking", "date": "2026-10-04"},
    {"type": "map_route", "duration_ms": 5000, "segments": ["s-3"], "style": "follow"},
    {"type": "photo", "duration_ms": 3500, "media": "m-456", "kenburns": {"from": [0,0,1.0], "to": [0.1,0.05,1.15]}, "caption": "Laguna de los Tres", "subtitle_from_voice": "v-12"},
    {"type": "video_clip", "media": "m-789", "trim_start_ms": 2000, "trim_end_ms": 8000, "keep_audio": true},
    {"type": "place_card", "duration_ms": 5000, "place": "p-77", "text": "...", "source": "Wikipedia (CC BY-SA 4.0)"},
    {"type": "stats", "duration_ms": 4000, "segment": "s-3"},
    {"type": "credits", "duration_ms": 6000}
  ]
}
```

- El **planificador** es lógica pura (en `core/timeline` o en un módulo `core/videoplan`): recibe la línea de tiempo y los parámetros y devuelve el `VideoPlan`. Se puede probar en la JVM.
- Algoritmo de selección: asignar el tiempo disponible por etapa en proporción a su "riqueza" (fotos, notas, destacados, lugares). Dentro de cada etapa, elegir fotos por favorita > destacada > nitidez, sin duplicados (pHash) y con dispersión temporal (no todas del mismo minuto).

### 13.4 Mapa animado
Se genera una secuencia de cuadros:
1. Calcular el encuadre (bbox) del tramo, con margen, y el nivel de zoom.
2. Obtener una **imagen base del mapa** de ese encuadre. En el dispositivo: snapshot de MapLibre (`MapSnapshotter`). En escritorio: componer teselas de un proveedor con clave, o renderizar con `staticmap`/`contextily`.
3. Para cada cuadro *i*: dibujar sobre la base la polilínea **hasta el punto correspondiente al instante i/N** del recorrido, con un marcador en la punta y, opcionalmente, un rótulo de la localidad actual.
4. En el modo `follow`, interpolar el centro y el zoom con suavizado (*ease-in-out*) para simular una cámara que sigue el recorrido.
5. Codificar los cuadros como un clip.

### 13.5 Motor A: renderizador de escritorio (Python + FFmpeg) — **se hace primero**
- **Ventajas:** se desarrolla y prueba íntegramente en Linux, sin teléfono; tiene más potencia y no gasta batería.
- **Entrada:** la carpeta de exportación (§12.1) y un `video_plan.json`, o se genera el plan desde `trip.json` con las mismas reglas.
- **Stack:** Python 3.11+, `Pillow` (tarjetas, textos, composición), `numpy`, `staticmap` o `contextily`+`matplotlib` (mapas), `ffmpeg` del sistema (codificación, Ken Burns con el filtro `zoompan`, mezcla de audio, subtítulos `.ass`), `pydantic` (validación del plan) y `typer` (CLI).
- **CLI:** `bitacora-render plan export/ --duration 180 --aspect 16:9 -o plan.json` y `bitacora-render render export/ plan.json -o resumen.mp4`.
- **Pruebas:** con un viaje sintético en `tools/sample-trips/`. Se verifica con `ffprobe` la duración, la resolución y la presencia de audio, y se comparan cuadros clave con imágenes de referencia (tolerancia por SSIM).

### 13.6 Motor B: en el dispositivo (Media3 Transformer) — fase 5
- `Composition` con `EditedMediaItemSequence`. Las fotos entran como `EditedMediaItem` de imagen, con duración y frame rate; los videos, recortados con `ClippingConfiguration`.
- Efectos: `Presentation` (resolución y relación de aspecto), `OverlayEffect` con `TextOverlay`/`BitmapOverlay` (títulos, subtítulos, tarjetas) y efectos de matriz para Ken Burns (`MatrixTransformation` dependiente del tiempo).
- Mapa animado: generar los cuadros como bitmaps y codificarlos en un clip previo (con `MediaCodec`, o con imágenes secuenciales vía `BitmapOverlay` animado).
- Audio: secuencia de audio aparte para la música y las voces.
- Corre en un `RenderWorker` como servicio en primer plano. Para procesamiento de medios existe el tipo `mediaProcessing` (Android 15+), con límites de tiempo: **verificar** los vigentes.
- **Las APIs de Media3 evolucionan rápido:** fijar la versión y consultar la documentación de esa versión.

### 13.7 Narración por voz sintética (opcional, futura)
Leer las tarjetas de lugar con TTS (`TextToSpeech` de Android, sintetizando a archivo con `synthesizeToFile`).

---

## 14. Interfaz de usuario

### 14.1 Pantallas
1. **Bienvenida / permisos (onboarding):** explica qué hace la app y pide los permisos de a uno, en contexto (§15.1).
2. **Inicio:** lista de viajes (portada, fechas, km). Botón principal **Nuevo registro**. Si hay una etapa activa, se muestra un banner para volver a ella.
3. **Nueva sesión (asistente):**
   - Paso 1: ¿Qué estás haciendo? Cuatro tarjetas grandes: Empezar un viaje · Continuar una etapa · Excursión o visita · Trekking.
   - Paso 2: elegir viaje (si continúa) y nombre de la etapa (opcional). Botón para **dictar** la intención ("Hoy vamos de El Calafate al Glaciar"), que se guarda en `intent_text`.
   - Paso 3: medio de transporte (con sugerencia) y perfil de GPS (avanzado).
   - Botón **Iniciar**.
4. **Sesión activa:**
   - Mapa en vivo (60 % de la pantalla) con el recorrido.
   - Panel de datos: tiempo, distancia, velocidad actual y media, altitud y desnivel (en trekking).
   - Botones grandes: 🎙️ **Nota de voz** · 📷 **Cámara** · 📍 **Marcar** · ⭐ **Destacar** · ⏸️ **Pausa** · ⏹️ **Terminar**.
   - Últimos eventos (fotos detectadas, notas transcriptas) en una tira inferior.
   - Indicador de estado del GPS (precisión) y de la batería.
5. **Terminar etapa:** resumen (mapa, estadísticas, cantidad de fotos y notas) con las opciones *Cerrar etapa* · *Cerrar viaje* · *Generar video de esta etapa*.
6. **Detalle de viaje:** pestañas **Línea de tiempo** · **Mapa** · **Fotos** · **Lugares** · **Estadísticas**.
7. **Revisión de medios:** fotos sin ubicación o con baja confianza, para ubicarlas a mano o descartarlas. También aloja la herramienta de corrección de desfase horario de una cámara (§7.6).
8. **Detalle de nota de voz:** reproductor, texto editable y ubicación en el mapa.
9. **Lugares:** tarjetas con la información de internet, para aprobarlas o descartarlas para el video.
10. **Exportar:** opciones (§12.1), elección de destino y progreso.
11. **Crear video:** parámetros (§13.2), **vista previa del plan** (lista de escenas que el usuario puede reordenar o quitar), progreso y reproductor del resultado.
12. **Ajustes:** unidades, idioma de transcripción, motor de transcripción y descarga del modelo, proveedor de mapa y claves, mapas offline, carpetas excluidas, perfiles GPS, claves de API (guardadas cifradas, §15.4), respaldo e importación, y "Acerca de / licencias".

### 14.2 Principios de UX
- **Uso con una mano y con guantes:** botones grandes (mínimo 64 dp en la sesión activa).
- **Legible al sol:** alto contraste. Tema oscuro y claro.
- **Nunca perder datos:** confirmación antes de terminar o borrar, y "deshacer" en los borrados.
- **Offline primero:** nada de la sesión activa depende de la red.
- Textos en español (rioplatense neutro), con recursos `strings.xml` preparados para traducción.
- Accesibilidad: descripciones de contenido, tamaños de fuente dinámicos y TalkBack.

### 14.3 Accesos rápidos
- Notificación persistente con acciones.
- Mosaico de Configuración rápida: "Nota de voz" e "Iniciar/Pausar".
- Widget de pantalla de inicio con el estado y los botones de nota y marcador.
- Accesos directos de la app (long-press en el ícono): "Nueva excursión", "Nota de voz".

---

## 15. Permisos, políticas de Google Play y privacidad

### 15.1 Permisos y cuándo se piden

| Permiso | Para qué | Cuándo se pide |
|---|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Recorrido | Al iniciar la primera sesión |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Servicio de tracking | Normal (no se pide en tiempo de ejecución) |
| `POST_NOTIFICATIONS` (Android 13+) | Notificación del servicio | Al iniciar la primera sesión |
| `RECORD_AUDIO` | Notas de voz | Al tocar la primera nota de voz |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (o `READ_EXTERNAL_STORAGE` ≤ 32) | Vincular fotos | Al iniciar la primera sesión, con explicación |
| `ACCESS_MEDIA_LOCATION` | Leer el GPS de las fotos | Junto con el anterior |
| `CAMERA` | Cámara integrada | Al abrir la cámara |
| `ACTIVITY_RECOGNITION` | Modo adaptativo (opcional) | Al activarlo en Ajustes |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Mapa, enriquecimiento | Normal |
| `FOREGROUND_SERVICE_DATA_SYNC` / `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Exportación / video | Normal |
| `WAKE_LOCK` | Mantener el CPU durante la exportación y el render | Normal |
| `ACCESS_BACKGROUND_LOCATION` | **No usar en la versión 1** | — |

- Cada permiso peligroso se pide **en contexto**, con una pantalla previa que explica el motivo.
- La app debe funcionar de forma degradada si se niega un permiso. Ejemplos: sin fotos, igual registra el recorrido; con ubicación aproximada, avisa que la precisión será baja.

### 15.2 Google Play (si se publica)
- **targetSdk 36** obligatorio para apps nuevas y actualizaciones desde el 31/08/2026.
- **Declaración de servicios en primer plano** en la Play Console: tipo `location`, con video demostrativo.
- **Declaración de permisos de fotos y videos:** justificar `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` como funcionalidad central. Si no se aprueba, el plan B es solo cámara integrada + Photo Picker sin vinculación por EXIF (con vinculación por hora contra el recorrido, que igual funciona si la hora es confiable).
- **Formulario de seguridad de los datos (Data safety):** declarar la ubicación precisa, las fotos, el audio y si algo sale del dispositivo (solo enriquecimiento con coordenadas aproximadas y transcripción en la nube si se activa).
- **Divulgación destacada** de la recolección de ubicación antes de pedir el permiso.
- **Uso personal sin Play Store:** se puede instalar el APK directamente (sideload), y estas exigencias no aplican. Se recomienda empezar así.

### 15.3 Privacidad
- **Todo es local por defecto.** No hay servidor propio ni cuenta.
- Los pedidos a Wikipedia y Nominatim envían coordenadas. Opción de **redondearlas a ~1 km** para el enriquecimiento.
- Exportar no sube nada: el usuario elige el destino.
- Opción de **zonas de privacidad** (por ejemplo, el domicilio) con un radio en el que el recorrido se recorta al exportar.
- Borrado completo de un viaje: base y audios. Los medios originales de la galería no se tocan.

### 15.4 Claves de API
- Google Maps: en `local.properties` → `BuildConfig`/manifiesto mediante el *Secrets Gradle Plugin*. **Nunca** en el repositorio.
- Claves del usuario (LLM, transcripción en la nube, proveedor de teselas): ingresadas en Ajustes y guardadas en DataStore cifrado con una clave del **Android Keystore**.

---

## 16. Batería, robustez y casos límite

### 16.1 Batería
- Objetivo: **≤ 5-8 % por hora** en perfil `WALK` con pantalla apagada (medir en dispositivos reales).
- Usar agrupación de actualizaciones (`maxUpdateDelay`) en vehículo.
- Pantalla de sesión activa: el mapa en vivo se actualiza como máximo una vez por segundo y se pausa cuando la pantalla está apagada.
- **Fabricantes agresivos** (Xiaomi, Huawei, Samsung, OnePlus): mostrar una guía específica por marca para excluir la app de la optimización de batería (referencia: dontkillmyapp.com). Solicitar `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` tiene restricciones de política en Play; mejor abrir la pantalla de ajustes con `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.

### 16.2 Casos límite
- **Sin señal GPS** (túneles, cañones, interiores): huecos en el recorrido. No interpolar la distancia; dibujar el hueco con línea punteada.
- **Modo avión en vuelos:** el GPS puede funcionar igual. Si no hay puntos, la etapa se registra con huecos y la app ofrece "Agregar tramo de vuelo" entre dos aeropuertos, dibujado como arco geodésico.
- **Cambio de huso horario:** guardar todo en UTC y mostrar en la zona de cada evento (por la zona del dispositivo en ese momento, guardada en la etapa, o calculada por coordenadas con una tabla de zonas offline).
- **Cambio manual de la hora del teléfono:** usar `Location.elapsedRealtimeNanos` como referencia monótona y detectar saltos.
- **Medianoche:** una etapa puede cruzar días; la línea de tiempo inserta un separador.
- **Almacenamiento lleno:** verificar el espacio antes de grabar audio y de exportar; avisar.
- **Fotos borradas de la galería después del viaje:** se detecta al exportar (el URI no resuelve). Se intenta reencontrarlas por `sha256` o nombre; si no aparecen, quedan marcadas como faltantes.
- **Dos etapas activas a la vez:** no se permite. Una excursión dentro de una etapa **pausa** la etapa principal y la reanuda al terminar (anidamiento de un solo nivel).
- **Ubicación simulada (mock):** detectar `Location.isMock` (API 31+) y marcar los puntos.
- **Etapas muy largas** (más de 24 h continuas): sugerir dividirlas.

### 16.3 Respaldo
- Exportación automática opcional del `trip.json` (sin medios) al terminar cada etapa, a la carpeta elegida.
- Auto Backup de Android: excluir los audios y los archivos grandes en `data_extraction_rules.xml`. La base sí puede respaldarse.

---

## 17. Estrategia de pruebas

### 17.1 Pirámide
1. **Unitarias JVM (la mayoría):** `core/geo` (haversine, filtros, Douglas-Peucker, paradas, interpolación, desfase de reloj), `core/timeline`, `core/export` (validación GPX contra XSD, JSON contra JSON Schema, KML bien formado) y el planificador de video.
2. **Integración Android:** DAOs de Room (pruebas instrumentadas o Robolectric), `MediaScanner` contra un MediaStore poblado en el emulador, y Workers con `WorkManagerTestInitHelper`.
3. **UI:** pruebas de Compose para los flujos críticos (iniciar, pausar, terminar, nota de voz simulada).
4. **Extremo a extremo en el emulador:** reproducir un GPX (§17.2), sacar fotos de prueba con `adb push` + escaneo de medios, terminar la etapa, exportar y verificar el ZIP.
5. **Renderizador de escritorio:** pytest con el viaje de ejemplo; verificación con `ffprobe` y cuadros clave.

### 17.2 Simulación de recorrido en el emulador
- `adb emu geo fix <lon> <lat> [alt]` para posiciones individuales.
- Script `tools/gpx-replay/replay.py` que lee un GPX y envía `geo fix` respetando los tiempos (con factor de aceleración).
- La consola extendida del emulador también permite cargar GPX/KML (manual).
- Proveer `tools/sample-trips/` con:
  - un trekking corto (ida y vuelta, con desnivel),
  - un tramo en auto con una parada larga,
  - un recorrido urbano a pie con 20 fotos sintéticas (JPEG generados con EXIF de hora y algunas con GPS).

### 17.3 Fixtures de fotos
Script Python (`tools/sample-trips/make_photos.py`) que genera JPEG con `Pillow` + `piexif`, con `DateTimeOriginal`, `OffsetTimeOriginal` y GPS opcional, para probar la vinculación con todos los casos de §7.6.

### 17.4 Pruebas en el dispositivo real (manuales, imprescindibles)
- Una salida de 1 hora caminando con el teléfono en el bolsillo y la pantalla apagada.
- Medir la batería y la calidad del recorrido.
- Probar la nota de voz desde la pantalla bloqueada.
- Probar con un fabricante con ahorro de energía agresivo, si es posible.

### 17.5 Integración continua (opcional)
GitHub Actions: `./gradlew lint test :core:*:test` y `assembleDebug`; pytest del renderizador.

---

## 18. Entorno de desarrollo en Linux

Detalle completo en la skill `android-linux-env`. Resumen:
- **JDK 17** (el que usa el Android Gradle Plugin actual; verificar si la versión de AGP pide 21).
- **Android SDK command-line tools**, con `sdkmanager` instalando `platform-tools`, `platforms;android-36`, `build-tools;36.x`, `emulator` y una imagen de sistema `system-images;android-36;google_apis;x86_64` (con Google APIs, necesaria para Play services y FusedLocation).
- **KVM** habilitado para el emulador (`/dev/kvm`). Sin KVM, el emulador x86_64 es inviable; en ese caso se usa un dispositivo físico.
- **Emulador sin interfaz:** `emulator -avd bitacora36 -no-window -no-audio -gpu swiftshader_indirect`.
- **Dispositivo físico:** depuración USB o depuración inalámbrica (`adb pair` / `adb connect`).
- **NDK y CMake** para whisper.cpp (fase 3).
- **Python 3.11+ y ffmpeg** para el renderizador de escritorio y los scripts.
- **Gradle wrapper** en el repositorio; nunca depender de un Gradle global.

> **Limitación a tener en cuenta:** Claude Code no ve la pantalla del teléfono. Puede compilar, instalar con `adb`, leer `logcat`, sacar capturas con `adb exec-out screencap -p > captura.png` y examinarlas, y usar `uiautomator dump` para inspeccionar la UI. Las pruebas reales en la calle las hace el usuario.

---

## 19. Plan de trabajo por fases

Cada fase termina con algo usable y probado. No se avanza sin cumplir los criterios de aceptación.

### Fase 0 — Esqueleto (1-2 días de trabajo de agente)
- Proyecto Gradle multi-módulo (§4.2) con el catálogo de versiones, Hilt, Room, Compose y la navegación.
- Tema, pantalla de inicio vacía y Ajustes básicos.
- Script `tools/setup-linux.sh` que verifica o instala el entorno.
- **Aceptación:** `./gradlew assembleDebug test` pasa; la app abre en el emulador; hay captura de pantalla de prueba.

### Fase 1 — Recorrido (MVP 1)
- Modelo de datos completo (las tablas que se usen en esta fase) con migraciones.
- Asistente de nueva sesión, servicio de tracking, perfiles, filtro, escritura en lote, pausa y reanudación, terminar y recuperación ante fallos.
- Sesión activa con mapa (empezar con MapLibre o Google, según la clave disponible) y estadísticas.
- `core/geo`: haversine, filtros, métricas, paradas y Douglas-Peucker, con pruebas.
- **Aceptación:** reproducir un GPX de ejemplo en el emulador produce una etapa con la distancia esperada (±2 %), las paradas esperadas y el mapa dibujado. La recuperación funciona tras matar el proceso con `adb shell am kill`.

### Fase 2 — Fotos, voz y línea de tiempo (MVP 2)
- Escaneo y observador de MediaStore, permisos de medios, lectura de EXIF con el original y vinculación (§7.6).
- Grabación de notas de voz (incluida `VoiceNoteActivity` desde la notificación). Transcripción **inicialmente con `AndroidSpeechTranscriber` o un stub**, dejando la interfaz lista.
- Notas de texto, marcadores y destacados.
- Línea de tiempo unificada y detalle de viaje con mapa.
- Revisión de medios sin ubicación.
- **Aceptación:** con las fotos sintéticas, cada caso de §7.6 se vincula como se espera (pruebas unitarias + E2E). Una nota de voz grabada en el emulador aparece en la línea de tiempo con su ubicación.

### Fase 3 — Exportación y transcripción local
- `core/export`: GPX, KML/KMZ, GeoJSON, CSV, JSON (+ JSON Schema), Markdown, HTML con Leaflet y SRT.
- ExportWorker, ZIP y destinos (SAF, compartir, Download).
- Importación de `trip.json` y de GPX externos.
- whisper.cpp con el NDK, descarga del modelo y TranscribeWorker.
- Detección de senderos con Overpass (§6.8); modo adaptativo con Activity Recognition (opcional).
- **Aceptación:** el ZIP exportado se abre en la PC: el GPX valida contra el XSD, el KML se importa en Google My Maps (prueba manual del usuario), el HTML se ve en Firefox/Chrome y el JSON valida contra su esquema. Whisper transcribe una nota de prueba en español.

### Fase 4 — Enriquecimiento y video de escritorio
- Nominatim, Wikipedia GeoSearch, resumen, Wikidata, caché, ranking y pantalla de lugares.
- Reescritura opcional con LLM.
- `desktop-renderer` completo: planificador, mapa animado, fotos con Ken Burns, clips, tarjetas, subtítulos, música y créditos.
- **Aceptación:** `bitacora-render` genera desde el viaje de ejemplo un MP4 de la duración pedida (±5 %), 1080p, con audio, que se reproduce en VLC. Los créditos incluyen las atribuciones.

### Fase 5 — Video en el dispositivo y extras
- Motor Media3 con el mismo `VideoPlan`, vista previa del plan y RenderWorker.
- Cámara integrada (CameraX).
- Google Fotos: Picker (importar) y Library API (subir a un álbum de la app).
- Widget, mosaico de Configuración rápida y accesos directos.
- Mapas offline (MapLibre), zonas de privacidad.
- **Aceptación:** un video de 1 minuto generado en un dispositivo de gama media en menos de 5 minutos.

### Fase 6 — Pulido y publicación (opcional)
- Accesibilidad, traducciones, iconografía y onboarding final.
- Pruebas de batería en dispositivo real y ajuste de perfiles.
- Play Console: declaraciones de permisos y servicios, seguridad de los datos y política de privacidad.

---

## 20. Riesgos y decisiones abiertas

| # | Riesgo / decisión | Impacto | Mitigación / recomendación |
|---|---|---|---|
| R1 | Google Play rechaza `READ_MEDIA_IMAGES` | Alto si se publica | Uso personal (sideload) primero; plan B en §15.2 |
| R2 | Fabricantes que matan el servicio | Recorridos con huecos | Guía por marca, recuperación, aviso en la UI |
| R3 | Clave de Google Maps con facturación | Costo / fricción | Empezar con MapLibre; Google Maps como opción |
| R4 | Tamaño del modelo Whisper (150-470 MB) | Espacio en el teléfono | Descarga opcional; modelo `base` por defecto |
| R5 | APIs de Media3 cambiantes | Retrabajo | Video de escritorio primero; versión fijada |
| R6 | Fotos de otra cámara con hora incorrecta | Vinculación errónea | Corrección de desfase por cámara (§7.6) |
| R7 | Calidad de la información de internet | Datos incorrectos en el video | Solo fuentes citadas, aprobación del usuario, LLM restringido al texto fuente |
| R8 | Licencias de teselas para video | Legal | Proveedor con clave y atribución; nunca teselas de Google ni de tile.openstreetmap.org |
| D1 | ¿Nombre definitivo y `applicationId`? | — | Por defecto `com.bitacoraviaje.app` (cambiar antes de publicar) |
| D2 | ¿Proveedor de mapa por defecto? | — | Recomendado: MapLibre + MapTiler (clave gratuita) |
| D3 | ¿Transcripción en la nube? ¿Cuál? | Costo, privacidad | Opcional, desactivada por defecto |
| D4 | ¿Publicar en Play Store o uso personal? | Alcance de la fase 6 | Decidir antes de la fase 6 |

---

## 21. Referencias (verificar las versiones vigentes al implementar)

- Requisitos de target API de Google Play: https://developer.android.com/google/play/requirements/target-sdk
- Servicios en primer plano y sus tipos: https://developer.android.com/develop/background-work/services/fgs
- Cambios en servicios en primer plano por versión: https://developer.android.com/develop/background-work/services/fgs/changes
- Fused Location Provider: https://developers.google.com/location-context/fused-location-provider
- Acceso a medios compartidos y ubicación de medios: https://developer.android.com/training/data-storage/shared/media
- Cambios de las APIs de Google Fotos (Picker / Library): https://developers.google.com/photos/support/updates
- Media3 Transformer: https://developer.android.com/media/media3/transformer
- whisper.cpp: https://github.com/ggml-org/whisper.cpp
- MapLibre Native Android: https://maplibre.org/
- Maps Compose (Google): https://developers.google.com/maps/documentation/android-sdk/maps-compose
- API de Wikipedia (GeoSearch): https://www.mediawiki.org/wiki/API:Geosearch
- API REST de Wikipedia (summary): https://en.wikipedia.org/api/rest_v1/
- Política de uso de Nominatim: https://operations.osmfoundation.org/policies/nominatim/
- Política de uso de teselas OSM: https://operations.osmfoundation.org/policies/tiles/
- Overpass API: https://wiki.openstreetmap.org/wiki/Overpass_API
- GPX 1.1: https://www.topografix.com/GPX/1/1/
- KML: https://developers.google.com/kml/documentation
- Fabricantes y optimización de batería: https://dontkillmyapp.com/
