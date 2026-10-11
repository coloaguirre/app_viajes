# Estado del arte — app Android de bitácora de viaje (recorrido GPS + medios + voz + video resumen)

**Documento interno** (no entregable). Fecha de investigación: 2026-10-11.
Autor: agente researcher. Fuente contrastada: `docs/incoming/DISENO.md` v1.0,
`pedido-original.md`, `README-paquete-arranque.md`.

Convención de este documento: no es una auditoría. Cada hallazgo se clasifica
como **GAP** (la spec no lo define y conviene definirlo), **DIVERGENCIA** (la spec
decide distinto a la práctica común; se pregunta si es deliberado), **RIESGO**
(la spec define algo que la literatura o la documentación oficial documenta
como problemático) u **OPORTUNIDAD** (algo que el proyecto no pidió y que lo
elevaría; es decisión de alcance del usuario, no entra al plan por sí sola).

Glosario de términos usados: *GPS/GNSS* = posicionamiento satelital; *EXIF* =
metadatos incrustados en una foto (hora, cámara, coordenadas); *FGS* = servicio
en primer plano de Android (proceso con notificación persistente que el sistema
no mata); *API* = interfaz de programación; *SDK* = kit de desarrollo; *NDK* =
kit para código nativo C/C++ en Android; *WAL* = modo de escritura anticipada de
SQLite; *VAD* = detección de actividad de voz (separar habla de silencio);
*WER* = tasa de error por palabra de una transcripción; *DEM* = modelo digital
de elevación (mapa de alturas del terreno); *OSM* = OpenStreetMap; *ODbL* y
*CC BY-SA* = licencias abiertas con obligación de atribución; *MSL* = altitud
sobre el nivel del mar; *RTF* = factor de tiempo real (segundos de cómputo por
segundo de audio).

---

## 1. Problema abstraído

Una aplicación móvil Android nativa, de uso personal, sin servidor ni cuenta,
que:

1. Registra un recorrido GPS continuo durante horas con un servicio en primer
   plano, con perfiles de muestreo por medio de transporte, filtrado de ruido,
   detección de paradas, simplificación y métricas (distancia, desnivel, etc.).
2. Vincula automáticamente las fotos y videos del teléfono al recorrido por
   hora y por EXIF, incluida la corrección de reloj de cámaras externas.
3. Graba notas de voz georreferenciadas y las transcribe en el dispositivo.
4. Construye una línea de tiempo cronológica unificada.
5. Exporta a formatos abiertos (GPX, KML/KMZ, GeoJSON, JSON, CSV, HTML, SRT).
6. Enriquece los lugares visitados con fuentes abiertas (Wikipedia, Wikidata,
   Nominatim, Overpass), con reescritura opcional por un modelo de lenguaje
   restringida al texto fuente.
7. Genera un video resumen con mapa animado, fotos, clips, subtítulos y
   tarjetas de lugar; primero en escritorio (Python + FFmpeg) y después en el
   dispositivo (Media3 Transformer).

Restricciones: offline primero; sin permiso de ubicación en segundo plano en la
versión 1; minSdk 26 / targetSdk 36; desarrollo desde Linux con emulador y
`adb`; publicación en Google Play opcional y al final.

---

## 2. Verificación de vigencia de lo que la spec cita

La spec marca varios puntos como "verificar". Resultado al 2026-10-11:

| Tema | Lo que dice la spec | Estado verificado | Fuente |
|---|---|---|---|
| Límites de tiempo de FGS | "verificar límites de `dataSync` y `mediaProcessing`" | **6 h por cada 24 h**, contadas por separado para cada tipo. El contador se reinicia solo cuando el usuario trae la app al frente. Al vencer, el sistema llama `Service.onTimeout()` y el servicio tiene segundos para `stopSelf()`; si no, excepción fatal. **`location` no tiene límite.** | [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [Android 15 FGS types](https://developer.android.com/about/versions/15/changes/foreground-service-types) |
| Android 16 y FGS | — | No hay cambios nuevos de tipos de FGS en Android 16. Sí hay (para **todas** las apps): los *jobs* (WorkManager) que corren en paralelo a un FGS quedan sujetos a cuota de tiempo de ejecución; `setImportantWhileForeground` ya no hace nada. Para apps con target 36: edge-to-edge obligatorio sin opt-out; fotos "propias de la app" aparecen preseleccionadas en el selector parcial. | [Android 16 all apps](https://developer.android.com/about/versions/16/behavior-changes-all), [Android 16 target 36](https://developer.android.com/about/versions/16/behavior-changes-16) |
| Arranque desde `BOOT_COMPLETED` | "no se puede iniciar un servicio de ubicación desde BOOT_COMPLETED" | Vigente en la práctica. Android 15 prohíbe lanzar desde `BOOT_COMPLETED` los tipos `camera`, `dataSync`, `mediaPlayback`, `mediaProjection`, `phoneCall` (y `microphone` desde Android 14). `location` no está en esa lista, pero iniciar un FGS de ubicación desde segundo plano requiere `ACCESS_BACKGROUND_LOCATION`, que la v1 no pide. Conclusión de la spec correcta. | ídem |
| Política de fotos de Google Play | "solo si el acceso amplio es parte central" | Vigente y **ya en aplicación plena desde el 28/05/2025**. Se exige declaración en Play Console demostrando por qué el Photo Picker no alcanza. Un resumen de búsqueda daba "septiembre 2026" como fecha de aplicación: es incorrecto; la página oficial dice 28/05/2025. | [Política Play](https://support.google.com/googleplay/android-developer/answer/14115180) |
| Google Fotos Library / Picker | Library API solo lee contenido creado por la app desde 31/03/2025 | Confirmado. Scopes `readonly`, `sharing` y `photoslibrary` eliminados; quedan `appendonly` y `*.appcreateddata`. **Picker API**: entrega EXIF "excepto la ubicación"; `baseUrl` válida 60 min; descarga con token OAuth. No hay cambios anunciados para 2026. | [Updates](https://developers.google.com/photos/support/updates), [Picker media items](https://developers.google.com/photos/picker/guides/media-items) |
| Media3 Transformer | "APIs cambian rápido; fijar versión" | Última estable **1.10 (30/03/2026)**. 1.9.0 (19/12/2025) trajo `CompositionPlayer` (vista previa en tiempo real de la misma `Composition` que se exporta; marcado experimental), tipos de pista explícitos al crear `EditedMediaItemSequence`, `setSpeed(SpeedProvider)`, y `InAppMp4Muxer` como muxer por defecto. 1.10 agrega `setFrameRate()` como tope de fps de salida. Imágenes: obligatorio `setImageDurationMs`. | [Media3 1.9](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html), [Media3 1.10](https://android-developers.googleblog.com/2026/03/media3-110-is-out.html), [Transformations](https://developer.android.com/media/media3/transformer/transformations) |
| Reconocedor de voz del sistema desde archivo | "verificar si admite fuente de audio externa" | **Sí, desde API 33**: `RecognizerIntent.EXTRA_AUDIO_SOURCE` acepta un `ParcelFileDescriptor` ya abierto, con `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`, `_ENCODING` y `_SAMPLING_RATE`. Advertencia textual de la documentación: si el reconocedor no soporta la función, **abre el micrófono en silencio** en lugar de fallar. | [RecognizerIntent API 33 diff](https://developer.android.com/sdk/api_diff/33/changes/android.speech.RecognizerIntent), [referencia](https://developer.android.com/reference/android/speech/RecognizerIntent) |
| Nominatim | UA identificable, 1 req/s, caché | Vigente. Máximo **absoluto** 1 req/s; caché **obligatoria**; prohibido autocompletar, barridos sistemáticos (grillas de reverse) y scraping de `details`. Los límites se aplican **por aplicación** (suma de todos los usuarios) y la app debe poder **cambiar de servicio sin actualización**. | [Política Nominatim](https://operations.osmfoundation.org/policies/nominatim/) |
| Overpass | consultas de senderos y POIs | Instancia principal declarada **sobrecargada** ("no esperar alta confiabilidad"). Referencia para apps: menos de 100 consultas y 10 MB por día **por aplicación**; sin consultas en paralelo; ante 429/406 pausar 30 s; identificar la app en User-Agent. Hay instancias alternativas. | [Overpass wiki](https://wiki.openstreetmap.org/wiki/Overpass_API) |
| Teselas OSM | no usar `tile.openstreetmap.org` para video | Vigente: prohibido uso offline, precarga y archivos de teselas; User-Agent propio obligatorio; atribución visible. | [Política de teselas](https://operations.osmfoundation.org/policies/tiles/) |
| MapTiler (proveedor sugerido) | "clave gratuita" | Plan gratuito: 100k requests/mes, **solo uso no comercial**. Términos: la caché solo puede ser temporal y personal; prohibido "almacenar o redistribuir contenido del mapa… mediante una captura u otra imagen estática en lugar de acceder por la API"; video permitido en canales de hasta 100k suscriptores con atribución mientras el mapa esté en pantalla; prohibido exportar contenido para uso fuera del servicio. | [Términos MapTiler](https://www.maptiler.com/terms/cloud/), [Precios](https://www.maptiler.com/cloud/pricing/) |
| Google My Maps | "verificar límites" | **10 capas por mapa, 2.000 elementos por capa, 10.000 por mapa; KML/KMZ descomprimido hasta 5 MB** (otros formatos 40 MB). Cada archivo importado es una capa. | [Ayuda My Maps](https://support.google.com/mymaps/answer/3024836) |
| Target SDK 36 obligatorio desde 31/08/2026 | afirmado | Coherente con el patrón anual de Play (target 35 fue 31/08/2025). **No lo verifiqué contra la página oficial**; confirmar al preparar Fase 6. | [Requisitos target](https://developer.android.com/google/play/requirements/target-sdk) |
| 16 KB de página de memoria | **no mencionado en la spec** | Google Play exige soporte de páginas de 16 KB para apps nuevas y actualizaciones con target Android 15+ desde el **01/11/2025** (prórroga en Play Console hasta 31/05/2026, ya vencida). Afecta a toda app con bibliotecas nativas `.so`. Android 16 tiene modo compatibilidad con diálogo al usuario. | [Blog Android](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html), [Android 16 all apps](https://developer.android.com/about/versions/16/behavior-changes-all) |
| Auto Backup | "la base sí puede respaldarse" | Cuota de **25 MB por app y usuario**; si se supera, el sistema **no respalda nada** (`onQuotaExceeded`) hasta que baje. Las bases de datos entran por defecto. | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| WorkManager y tareas largas | transcripción, exportación y render en Workers | Un Worker común se corta a los ~10 min; para más tiempo hay que usar `setForeground()` (WorkManager levanta un FGS por detrás y exige tipo en Android 14+). Nota oficial nueva: **"a partir de Android 16, los workers largos pueden agotar la cuota de jobs de la app; si pasa, lance el FGS directamente"**. | [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) |
| Botón "Detener" del administrador de apps activas (Android 13+) | — | El usuario puede detener la app desde el panel de notificaciones. El sistema **mata el proceso entero sin ningún callback**. Las apps con FGS `location` están exentas solo de la notificación de "larga duración", no del botón. | [Handle user stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping) |

---

## 3. Estado del arte por área (resumen con fuentes)

### 3.1 Registro GPS en móvil
- **Filtrado**: la práctica común coincide con la spec: umbral de precisión
  horizontal, descarte por velocidad implícita imposible, radio de reposo,
  suavizado opcional (Kalman) sólo para visualización. Se conserva el crudo.
- **Detección de paradas**: el algoritmo de ventana deslizante de Li, Zheng et
  al. (SIGSPATIAL 2008) que usa la spec sigue siendo la referencia de bajo
  costo. Un *experiment paper* aceptado en SIGSPATIAL 2026 comparó nueve
  métodos bajo ruido y huecos y concluye que **los huecos de datos (dropout)
  degradan más la detección que el ruido espacial**, y que SSPE (*Sequential
  Stay Point Extraction*) y métodos de radio adaptativo son los más robustos.
  Umbrales de evaluación habituales: ~100 m y 5 min.
  Fuentes: [Zheng, Trajectory Data Mining (TIST 2015)](https://www.microsoft.com/en-us/research/wp-content/uploads/2015/09/TrajectoryDataMining-tist-yuzheng.pdf), [Staypoint Detection from Noisy Trajectory Data (arXiv 2607.19312)](https://arxiv.org/html/2607.19312), [stay points on-device (Sensors 2016)](https://pmc.ncbi.nlm.nih.gov/articles/PMC5087481).
- **Desnivel**: la altitud GPS tiene error vertical típico de 10-20 m (1,5-2x
  el horizontal), por lo que el desnivel acumulado por GPS, aun con histéresis,
  es poco confiable. La práctica de las apps de montaña es usar el **barómetro**
  (relativo, resolución ~1 m, deriva lenta con el clima) fusionado con GPS, o
  corregir contra un DEM. Fuentes: [fizziq, altitud GPS vs barómetro](https://www.fizziq.org/en/glossary/altitude/), [alpinetrek](https://www.alpinetrek.co.uk/blog/barometric-and-gps-based-altimeters/).
- **Servicios en primer plano**: ver tabla §2. Clave: `location` sin límite de
  tiempo; `dataSync`/`mediaProcessing` con 6 h/24 h; el botón "Detener" mata
  sin aviso.

### 3.2 Vinculación de fotos al recorrido
- La referencia de facto es el geotagging de **exiftool**: interpolación lineal
  entre fixes si el hueco es menor a `GeoMaxIntSecs` (por defecto **1800 s**),
  extrapolación al fix más cercano hasta `GeoMaxExtSecs` (**1800 s**), opción
  `Geosync` para el desfase de reloj de la cámara (se determina fotografiando un
  reloj GPS o ubicando una foto conocida), y filtros opcionales por calidad del
  fix. `DateTimeOriginal` sin zona se asume hora local; **los sellos GPS del
  EXIF (`GPSDateStamp`/`GPSTimeStamp`) son siempre UTC**.
  Fuente: [exiftool geotag](https://exiftool.org/geotag.html).
- Android: `ACCESS_MEDIA_LOCATION` + `setRequireOriginal` para leer el GPS del
  EXIF; el Photo Picker siempre lo elimina. Ambos puntos de la spec son
  correctos y vigentes ([media location](https://developer.android.com/training/data-storage/shared/media)).
- **Videos**: en MP4/MOV el `creation_time` de la cabecera se escribe **al
  terminar** la grabación (semántica QuickTime) y casi siempre en UTC sin zona.
  Fuente: [foro exiftool](https://exiftool.org/forum/index.php?topic=11608.0), [metadata-extractor #460](https://github.com/drewnoakes/metadata-extractor/issues/460).

### 3.3 Transcripción en el dispositivo
- **whisper.cpp** sigue activo y tiene **VAD Silero integrado** (modelo
  `ggml-silero-v6.2.0`, flag `--vad`). Whisper alucina texto en silencio y ruido
  (~1 % de segmentos según *Careless Whisper*, arXiv 2402.08021); el VAD previo
  reduce mucho pero no elimina el problema; `no_speech_threshold` solo no
  alcanza. Fuentes: [issue whisper_ggml #29](https://github.com/sk3llo/whisper_ggml/issues/29), [theneuralbase VAD](https://theneuralbase.com/whisper/learn/intermediate/reducing-hallucinations-with-vad/).
- **Alternativas**: `sherpa-onnx` (ONNX Runtime) corre en Android Whisper,
  Moonshine (incluye `moonshine-base-es` cuantizado, 2026-02), SenseVoice,
  Parakeet TDT v3 (multilingüe, incluye español) y Zipformer. Un benchmark de
  ingeniería (sep 2026, Galaxy S10, solo inglés, **sin medir precisión**) midió
  51x de diferencia de velocidad entre motores para el mismo Whisper tiny; el
  número es sospechoso de build no optimizado, pero la conclusión "el motor
  importa tanto como el modelo" es razonable. Fuentes: [sherpa-onnx docs](https://k2-fsa.github.io/sherpa/onnx/index.html), [benchmark voiceping](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/).
- **Reconocedor del sistema desde archivo**: `EXTRA_AUDIO_SOURCE` (API 33), ver
  tabla §2.

### 3.4 Enriquecimiento con fuentes abiertas
- Nominatim, Overpass y teselas OSM: ver tabla §2. Las tres políticas piden
  User-Agent propio, caché y límites **por aplicación**.
- **Wikimedia REST** (`/api/rest_v1/page/summary`): el servicio RESTBase está
  en proceso de *sunset*; el endpoint `summary` sigue servido (lo usan las
  propias apps de Wikipedia) pero ya perdió el campo `api_urls`, y Wikimedia
  anunció que entre julio 2026 y junio 2027 creará nuevas URLs para los
  endpoints de `api.wikimedia.org` con deprecación gradual posterior. Fuentes:
  [API Portal/Deprecation](https://wikitech.wikimedia.org/wiki/API_Portal/Deprecation), [anuncio api_urls](https://lists.wikimedia.org/hyperkitty/list/mediawiki-api-announce@lists.wikimedia.org/thread/U4CCBM5MRII36ROU766WXTIV3LHJNBBG/), [RESTBase](https://www.mediawiki.org/wiki/RESTBase).
- **Zona horaria offline por coordenadas**: bibliotecas Java/Kotlin maduras:
  `timeshape` y `timezonemap` (derivadas de timezone-boundary-builder sobre
  OSM), con opción de cargar solo una caja delimitadora para ahorrar memoria.
  Fuentes: [timeshape](https://github.com/RomanIakovlev/timeshape), [timezonemap](https://github.com/dustin-johnson/timezonemap).
- **Modelos de lenguaje en el dispositivo**: ML Kit GenAI APIs (Gemini Nano)
  ofrecen resumen, corrección, **reescritura** y descripción de imágenes, todo
  local y sin costo de nube; disponibles en Pixel 9+, Galaxy S25 y otros (Google
  reporta >140 M de dispositivos a jul 2026). Fuente: [blog Android, ML Kit GenAI](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html).

### 3.5 Mapas
- **PMTiles** (archivo único de teselas vectoriales) está soportado nativamente
  en MapLibre Android con `pmtiles://file://…` y `pmtiles://asset://…`, lo que
  permite mapa offline sin clave; limitación: no se integra con *offline packs*
  ni caché (hay que traer el archivo entero). Fuente: [ejemplo MapLibre Android](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/).
- **Terreno 3D**: MapLibre Native **todavía no lo soporta** (rama
  `feature/terrain-3d` en desarrollo, septiembre 2026); MapLibre GL JS sí.
  Fuente: [newsletter MapLibre sep 2026](https://maplibre.org/news/2026-10-01-maplibre-newsletter-september-2026/).

### 3.6 Video
- FFmpeg `zoompan` produce **vibración** (jitter) porque recorta a píxeles
  enteros; la mitigación estándar es sobreescalar la imagen antes (p. ej. a
  ~8000 px de ancho) o usar un reemplazo con transformación subpíxel. Fuente:
  [ffmpeg-micro, zoompan](https://www.ffmpeg-micro.com/blog/ffmpeg-zoompan-filter-ken-burns-zoom-and-pan-without-the-jitter), [glide-ffmpeg](https://github.com/jham2081-blip/glide-ffmpeg).
- Media3: ver tabla §2.

---

## 4. Hallazgos clasificados (GAP / DIVERGENCIA / RIESGO)

Ordenados por impacto estimado.

### R1 — RIESGO — Bibliotecas nativas y páginas de 16 KB
La spec incluye al menos tres componentes con código nativo (whisper.cpp vía
NDK, MapLibre Native, y posiblemente pHash/decodificadores) y no menciona el
requisito de 16 KB. Google Play lo exige desde 01/11/2025 para target 15+ (la
prórroga venció 31/05/2026); en sideload, Android 16 arranca la app en modo
compatibilidad **mostrando un diálogo al usuario**. Compilar whisper.cpp con
NDK r28+ y verificar la alineación de cada `.so` (incluidos los de terceros)
es un criterio de aceptación barato de agregar en Fase 0/3.
*Pregunta:* ¿se agrega "APK alineado a 16 KB" como criterio de aceptación de
Fase 0 (toolchain) y Fase 3 (whisper)?

### R2 — RIESGO — Presupuesto de tiempo de las tareas largas (transcripción, exportación, render)
La spec pone la transcripción en un `CoroutineWorker` sin tipo de FGS. Un
Worker común se corta a ~10 min; con el modelo `small` y varias notas de 5 min
en cola, un teléfono de gama media puede superarlo. Si se migra a *worker
largo*, hay que elegir tipo: `mediaProcessing` ("operaciones costosas sobre
activos de medios") es el que mejor encaja, pero **comparte el presupuesto de
6 h/24 h** con el render de video, y la exportación consume el de `dataSync`.
Además, Android 16 aplica cuota a jobs concurrentes con un FGS, y la propia
documentación sugiere lanzar el FGS directamente si la cuota se agota. La spec
no define quién administra ese presupuesto ni qué pasa al vencer (`onTimeout`
→ guardar progreso y reanudar al volver al frente).
*Pregunta:* ¿se define un "gestor de trabajos largos" único que (a) elija el
tipo de FGS por tarea, (b) implemente `onTimeout` con reanudación, y (c)
priorice (transcripción antes que render)?

### R3 — RIESGO — Auto Backup y la base de datos
§16.3 dice que la base "sí puede respaldarse" vía Auto Backup. La cuota es 25 MB
por app; con la estimación de la propia spec (~1 MB/día de puntos), un viaje de
30 días la supera y entonces **no se respalda nada** (ni ajustes). Además,
respaldar una base Room en modo WAL copia archivos `-wal`/`-shm` que pueden
quedar inconsistentes. La práctica común es excluir la base del Auto Backup y
confiar en el respaldo propio (que la spec ya tiene: exportación automática de
`trip.json`), o hacer *checkpoint* y respaldar solo tablas chicas.
*Pregunta:* ¿se excluye la base del Auto Backup y se declara el `trip.json`
automático como el respaldo oficial?

### R4 — RIESGO — Alucinaciones de Whisper en silencio y ruido exterior
Notas dictadas al aire libre (viento, motor, pausas largas) son el peor caso
documentado de Whisper: inventa frases o repite la anterior. La spec no
menciona VAD ni post-filtros. whisper.cpp ya trae Silero VAD; la práctica es
VAD previo + descartar segmentos con alta `no_speech_prob` + conservar el audio
original (la spec ya lo conserva). Un texto inventado termina como subtítulo
del video.
*Pregunta:* ¿se incorpora VAD como paso obligatorio del `WhisperLocalTranscriber`
y se marca en `transcript_segments_json` la confianza por segmento?

### R5 — RIESGO — Términos del proveedor de teselas para el video de escritorio
§10.3 presenta "proveedor con clave y atribución" como camino legalmente
limpio. Los términos del proveedor sugerido (MapTiler) permiten video con
atribución en canales ≤100k suscriptores, pero **prohíben** almacenar
contenido "mediante una captura u otra imagen estática en lugar de acceder por
la API" y "exportar contenido para uso fuera del servicio"; además el plan
gratuito es solo no comercial. Componer teselas en cuadros de video desde
Python está en zona gris. Para uso personal el riesgo práctico es bajo, pero no
es tan limpio como está escrito.
*Pregunta:* ¿se acepta la zona gris para uso personal, o se prefiere una
cartografía sin términos de terceros (ver Oportunidad O2: PMTiles/Protomaps
renderizado propio, solo ODbL)?

### R6 — RIESGO — Endpoint de resumen de Wikipedia en transición
`rest_v1/page/summary` sigue vivo pero RESTBase está en *sunset* y Wikimedia
anunció nuevas URLs 2026-2027 con deprecación posterior. Mantener el cliente
detrás de una interfaz (la spec ya tiene `enrichment/` como paquete) y fijar
el User-Agent según la política de Wikimedia evita retrabajo. Bajo impacto si
la abstracción existe.

### R7 — RIESGO — Hora de los videos: `creation_time` es el fin de la grabación
§7.5 usa `METADATA_KEY_DATE` como hora del video. En MP4/MOV ese valor se
escribe al **terminar** la grabación; un clip de 3 minutos quedaría vinculado
al punto donde terminó, no donde empezó. Corrección trivial: restar
`DURATION`. Conviene registrar `taken_time_source = MP4_CREATION_MINUS_DURATION`.

### G1 — GAP — Transcripción local real desde Fase 2 con el reconocedor del sistema
La spec deja abierta la pregunta de si el `SpeechRecognizer` admite audio de
archivo. La respuesta es sí desde API 33 (`EXTRA_AUDIO_SOURCE` con
`ParcelFileDescriptor`, más canales/codificación/muestreo). Esto permite tener
transcripción offline real en Fase 2, con el modelo de Google ya instalado en
el teléfono, sin descargar 150-470 MB. Trampa documentada: si el reconocedor
no soporta la función, **abre el micrófono en silencio**; hay que detectar ese
caso (p. ej. comprobar que el resultado no llega antes de cerrar el descriptor
o probar con un audio de control). Requiere PCM 16 kHz mono (la spec ya
decodifica el AAC para Whisper).
*Pregunta:* ¿se promueve `AndroidSpeechTranscriber` de "stub" a implementación
real de Fase 2 (API 33+) con detección del fallback al micrófono?

### G2 — GAP — Semántica de los huecos GPS en la detección de paradas
La spec define qué hacer con los huecos para la distancia (no interpolar,
línea punteada) pero no para las paradas. El experimento 2026 muestra que el
*dropout* degrada más la detección que el ruido. Caso típico: 40 min dentro de
un museo sin señal: los dos puntos que rodean el hueco están a 20 m y 40 min
de distancia; con la ventana de Li 2008 pura puede no detectarse como parada
(no hay puntos "dentro"). La práctica es tratar "hueco > T con extremos dentro
de R" como parada (tipo *indoor*).
*Pregunta:* ¿se define esa regla y un tipo de parada "inferida por hueco"?

### G3 — GAP — Desnivel en teléfonos con barómetro
§6.5 calcula desnivel solo con altitud GPS suavizada. Con error vertical de
10-20 m, el acumulado de un trekking de 6 h queda muy inflado o muy
subestimado aun con histéresis de 3 m. Muchos teléfonos tienen
`Sensor.TYPE_PRESSURE`; la práctica de apps de montaña es desnivel relativo
por barómetro anclado a GPS/DEM. La spec no dice qué hacer cuando el sensor
existe. (Ver también Oportunidad O4 para la corrección con DEM.)
*Pregunta:* ¿se agrega `pressure_hpa` al `track_point` y se usa el barómetro
para el desnivel cuando está disponible, dejando GPS como respaldo?

### G4 — GAP — Sellos GPS del EXIF como ancla UTC
§7.5 prioriza `DateTimeOriginal`+`OffsetTimeOriginal` y, sin offset, asume la
zona de la etapa. Falta un escalón: si la foto trae GPS EXIF, `GPSDateStamp` +
`GPSTimeStamp` están **en UTC** y permiten (a) obtener la hora exacta sin
asumir zona, y (b) **calcular el desfase del reloj de esa cámara** sin
intervención manual (diferencia entre hora local declarada y UTC del GPS).
Reduce el trabajo de la herramienta de desfase de §7.6.

### G5 — GAP — Barrido incremental de MediaStore con `GENERATION_ADDED`
§7.3 dice "consultar lo nuevo desde la última verificación" sin especificar la
columna. `DATE_ADDED` está en segundos y depende del reloj; desde API 30 el
mecanismo robusto es `MediaStore.getGeneration()` + filtrar por
`GENERATION_ADDED`/`GENERATION_MODIFIED` (contador monótono del volumen).
También conviene excluir `IS_PENDING = 1` (archivos que la cámara aún está
escribiendo) para no leer un EXIF incompleto.

### G6 — GAP — KML para My Maps: una capa por archivo, 2.000 elementos, 5 MB
Con los límites verificados, un solo `viaje.kml` con 30 etapas, fotos y
paradas supera fácilmente los 2.000 elementos de una capa (My Maps importa
cada archivo como una capa). El exportador debería producir KML
**particionados por capa** (p. ej. `recorrido.kml`, `fotos.kml`, `paradas.kml`,
o por bloques de etapas), cada uno < 2.000 elementos y < 5 MB, además del KMZ
completo para Google Earth.

### G7 — GAP — Política de resiliencia para APIs comunitarias (Nominatim, Overpass, Wikimedia)
Las tres políticas piden User-Agent propio, caché y límites por aplicación;
Overpass se declara sobrecargado y pide pausar 30 s ante 429; Nominatim exige
poder **cambiar de servicio sin actualizar la app**. La spec define el límite
de 1 req/s pero no: User-Agent concreto, backoff, tiempo máximo de espera,
instancia alternativa configurable desde Ajustes, ni qué se muestra al usuario
cuando el enriquecimiento falla parcialmente.

### G8 — GAP — Fotos HEIC/HEIF y el renderizador de escritorio
Pixel y Samsung permiten (y a veces predeterminan) HEIF. En Android,
`ExifInterface` de AndroidX lo lee; pero el renderizador Python con Pillow
**no abre HEIC sin `pillow-heif`**. La spec no lo menciona y el video de
escritorio es lo primero que se construye. Decidir: convertir a JPEG al
exportar (opción "reducir a 2048 px" ya existe) o agregar la dependencia.

### G9 — GAP — El Picker de Google Fotos no entrega ubicación y obliga a copiar
§7.2 (fuente 3) no aclara que la Picker API entrega EXIF **sin ubicación**,
que la `baseUrl` dura 60 min y que hay que **descargar el archivo** (contradice
el principio de §5.1 "los medios no se copian"). La vinculación de esas fotos
será solo por hora. Conviene documentarlo para no prometer EXIF GPS por esa
vía.

### D1 — DIVERGENCIA — Umbrales de interpolación (5/10 min) frente a la referencia (30 min)
exiftool interpola hasta 1800 s y extrapola hasta 1800 s por defecto; la spec
usa 5 min para interpolar y 10 min para el vecino más cercano, con confianza
decreciente. Es más estricto que la referencia, lo que manda más fotos a
revisión manual pero evita ubicaciones falsas en tramos rápidos. Puede ser
deliberado y correcto para vehículo (en 30 min un auto recorre 40 km); para
peatón, 30 min suele ser aceptable.
*Pregunta:* ¿es deliberado un umbral único, o se prefiere por medio de
transporte (p. ej. 20-30 min a pie, 5 min en vehículo) y configurable?

### D2 — DIVERGENCIA — Extensiones GPX propias (`bv:`) frente a Garmin TrackPointExtension
Casi todas las herramientas de escritorio (BaseCamp, GPX Studio, Strava,
Komoot) leen `gpxtpx:TrackPointExtension` v2 (`speed`, `course`, `hr`,
`atemp`). Un namespace propio es válido pero lo ignoran. Se pueden emitir
ambos.
*Pregunta:* ¿se prefiere el namespace propio por simplicidad o se agrega la
extensión Garmin para interoperabilidad?

### D3 — DIVERGENCIA — whisper.cpp como motor único por defecto
La spec fija whisper.cpp con modelo `base`. Dos matices de la práctica actual:
(a) para español, `base` es notablemente peor que `small`; el piso habitual
para idiomas distintos del inglés es `small` (o `large-v3-turbo` cuantizado si
el hardware lo permite); (b) `sherpa-onnx` corre en Android varios modelos
(incluido un Moonshine-base en español de 2026 y Parakeet TDT v3 multilingüe)
detrás de una sola integración, lo que da margen para cambiar de modelo sin
tocar el NDK. La interfaz `Transcriber` de la spec ya lo permite. **Debate
abierto**: los benchmarks públicos de velocidad son de blogs de ingeniería,
solo en inglés y sin WER; no hay evidencia sólida de precisión en español
rioplatense para ninguno. Lo prudente es medir WER con 10-20 notas reales
antes de fijar modelo.
*Pregunta:* ¿se mantiene whisper.cpp pero con `small` como recomendado para
español, y se deja `sherpa-onnx` como segunda implementación opcional?

### D4 — DIVERGENCIA menor — Integración continua en GitHub Actions
§17.5 propone GitHub Actions; la convención de la organización es GitLab. No
es estado del arte, es coherencia interna. Se anota para que el kickoff lo
decida.

---

## 5. Oportunidades

Material de **alcance**, no correcciones. Ordenadas por relación valor/costo.
Ninguna entra al plan por sí sola.

### O1 — Reescritura y resumen con modelo de lenguaje **en el dispositivo** (ML Kit GenAI)
*Qué es:* las APIs GenAI de ML Kit (Gemini Nano) ofrecen reescritura y resumen
locales, sin clave ni nube. *Capacidad nueva:* reescribir el extracto de
Wikipedia en tono de bitácora y resumir notas de voz largas sin salir del
teléfono ni configurar una API de pago; coherente con "offline primero".
*Límite:* solo dispositivos compatibles (Pixel 9+, Galaxy S25, etc.); hay que
mantener el camino de nube como alternativa. *Costo:* bajo-medio.
Fuente: [ML Kit GenAI](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html).

### O2 — Cartografía propia sin clave con PMTiles/Protomaps (app y video)
*Qué es:* un archivo único de teselas vectoriales OSM por región (Protomaps),
cargado en MapLibre Android con `pmtiles://file://…`. *Capacidad nueva:* mapa
offline real descargando un archivo antes del viaje, sin clave, sin términos
de proveedor (solo ODbL); y para el video de escritorio, renderizar los cuadros
desde el mismo archivo elimina la zona gris de R5. *Límite:* sin *offline
packs* ni caché incremental; el renderizado en escritorio requiere un
renderizador de estilos MapLibre (p. ej. `mbgl-render` de maplibre-native o GL
JS sin cabeza). *Costo:* medio.
Fuente: [ejemplo PMTiles MapLibre Android](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/).

### O3 — Vista previa del video en la app con `CompositionPlayer` (Media3 1.9+)
*Qué es:* reproducir la misma `Composition` que se va a exportar, sin
renderizar. *Capacidad nueva:* la pantalla "Crear video" pasa de lista de
escenas a vista previa real antes de gastar minutos de batería en el render.
*Límite:* marcado `@ExperimentalApi`. *Costo:* bajo-medio una vez que exista el
motor B.
Fuente: [Media3 1.9](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html).

### O4 — Corrección de altitud con DEM offline y perfil de elevación fiable
*Qué es:* consultar un modelo digital de elevación (SRTM/Copernicus 30 m)
local para cada punto. *Capacidad nueva:* perfil de elevación y desnivel
coherentes aunque el teléfono no tenga barómetro; en escritorio es trivial
(`srtm.py` o teselas Terrain-RGB); en el teléfono requiere empaquetar las
teselas de la región. Combina con G3. *Costo:* bajo en escritorio, medio en el
dispositivo.

### O5 — Detector de paradas robusto a huecos (SSPE / radio adaptativo)
*Qué es:* los algoritmos mejor evaluados bajo ruido y dropout en el
experimento SIGSPATIAL 2026. *Capacidad nueva:* menos paradas falsas en ciudad
(cañones urbanos) y menos paradas perdidas en interiores; es lógica pura
Kotlin en `core/geo`, probable en JVM. *Costo:* bajo.
Fuente: [arXiv 2607.19312](https://arxiv.org/html/2607.19312).

### O6 — Nombre de localidad en vivo sin red (geocodificación inversa offline)
*Qué es:* una tabla de localidades (GeoNames `cities1000`, CC BY 4.0) con
búsqueda por vecino más cercano. *Capacidad nueva:* la notificación y la
pantalla de sesión muestran "Cerca de X" durante el viaje sin conexión, y la
línea de tiempo tiene nombres antes de que corra el enriquecimiento. *Costo:*
bajo-medio (unos pocos MB de datos).

### O7 — Selección y encuadre de fotos para el video con modelos en el dispositivo
*Qué es:* ML Kit Face Detection para recorte inteligente a 9:16 (que no corte
caras) y un modelo de calidad estética tipo NIMA (Talebi y Milanfar, 2018) en
TFLite/LiteRT en lugar de solo varianza del laplaciano. *Capacidad nueva:*
el video elige fotos por "qué tan buena es" y no solo "qué tan nítida es", y
el formato vertical deja de recortar a ciegas. *Costo:* medio.

### O8 — Segundo motor de transcripción multimodelo (`sherpa-onnx`)
*Qué es:* una implementación alternativa de `Transcriber` con ONNX Runtime
que permite elegir entre Whisper, Moonshine-es, Parakeet TDT v3 y SenseVoice
sin recompilar nativo. *Capacidad nueva:* comparar WER en español real con
notas propias y elegir el modelo con datos; modelos de 30-200 MB. *Costo:*
medio. Relacionado con D3.
Fuente: [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/index.html).

### O9 — Sobrevuelo 3D del recorrido en el video (solo escritorio, por ahora)
*Qué es:* animación con terreno 3D estilo "flyover". *Estado:* MapLibre Native
no soporta terreno (rama en desarrollo, sep 2026), así que en el dispositivo
no es viable hoy; en escritorio sí con MapLibre GL JS sin cabeza (Chromium) y
Terrain-RGB. *Capacidad nueva:* salto visual grande en trekkings. *Costo:*
alto (toolchain nuevo en el renderizador).
Fuente: [newsletter MapLibre sep 2026](https://maplibre.org/news/2026-10-01-maplibre-newsletter-september-2026/).

---

## 6. Notas menores (sin clase, para el implementador)
- `zoompan` de FFmpeg vibra: sobreescalar la foto antes del filtro (práctica
  estándar) o usar transformación subpíxel. Incluirlo en las pruebas de
  cuadros clave por SSIM evita descubrirlo tarde.
- Al iniciar el FGS `location` en Android 14+, el permiso de ubicación debe
  estar **ya concedido** o `startForeground` lanza `SecurityException`; el
  orden "pedir permiso → iniciar servicio" de §15.1 es correcto, solo hay que
  respetarlo también en el camino de "Reanudar" tras reinstalación/revocación.
- Edge-to-edge es obligatorio con target 36; Compose + Material 3 lo manejan si
  se usan `WindowInsets` desde el principio.
- Texto reescrito a partir de Wikipedia sigue siendo CC BY-SA (obra derivada):
  irrelevante para uso personal; relevante si el video se publica.

---

## 7. Candidatos a checklist del kickoff (vía skill retro)
Generalizables a cualquier app Android con tareas largas o código nativo:
1. "¿Hay `.so` en el APK? → alineación 16 KB como criterio de aceptación."
2. "¿Qué tareas superan 10 min y qué tipo de FGS/presupuesto de 6 h usan?"
3. "¿Auto Backup está dentro de 25 MB o se excluye la base explícitamente?"
4. "Toda API comunitaria (OSM, Wikimedia): User-Agent propio, caché, backoff,
   endpoint cambiable desde Ajustes."
5. Transcripción con Whisper: VAD obligatorio y confianza por segmento.

---

## 8. Fuentes (todas consultadas 2026-10-11)
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/sdk/api_diff/33/changes/android.speech.RecognizerIntent
- https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
- https://support.google.com/googleplay/android-developer/answer/14115180
- https://developers.google.com/photos/support/updates
- https://developers.google.com/photos/picker/guides/media-items
- https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html
- https://android-developers.googleblog.com/2026/03/media3-110-is-out.html
- https://developer.android.com/media/media3/transformer/transformations
- https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html
- https://operations.osmfoundation.org/policies/nominatim/
- https://operations.osmfoundation.org/policies/tiles/
- https://wiki.openstreetmap.org/wiki/Overpass_API
- https://www.maptiler.com/terms/cloud/ y https://www.maptiler.com/cloud/pricing/
- https://support.google.com/mymaps/answer/3024836
- https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
- https://maplibre.org/news/2026-10-01-maplibre-newsletter-september-2026/
- https://wikitech.wikimedia.org/wiki/API_Portal/Deprecation
- https://exiftool.org/geotag.html
- https://exiftool.org/forum/index.php?topic=11608.0
- https://arxiv.org/html/2607.19312 (Staypoint Detection from Noisy Trajectory Data, SIGSPATIAL 2026)
- https://www.microsoft.com/en-us/research/wp-content/uploads/2015/09/TrajectoryDataMining-tist-yuzheng.pdf
- https://pmc.ncbi.nlm.nih.gov/articles/PMC5087481
- https://arxiv.org/abs/2402.08021 (Careless Whisper)
- https://k2-fsa.github.io/sherpa/onnx/index.html
- https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/ (blog de ingeniería; solo velocidad, solo inglés)
- https://github.com/sk3llo/whisper_ggml/issues/29 (VAD Silero en whisper.cpp)
- https://github.com/RomanIakovlev/timeshape y https://github.com/dustin-johnson/timezonemap
- https://www.ffmpeg-micro.com/blog/ffmpeg-zoompan-filter-ken-burns-zoom-and-pan-without-the-jitter
- https://www.fizziq.org/en/glossary/altitude/ y https://www.alpinetrek.co.uk/blog/barometric-and-gps-based-altimeters/
