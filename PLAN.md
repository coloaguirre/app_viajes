# PLAN — app-viajes (Bitácora de Viaje)
Estado: APROBADO (2026-10-11) · Tipo: otro (app móvil Android personal + renderizador Python de escritorio)
Fuentes: docs/incoming/DISENO.md · docs/incoming/pedido-original.md · docs/incoming/pedido-original-adenda-1.md · docs/incoming/README-paquete-arranque.md
Inicio: 2026-10-11 · Fin: 2027-03-12 (propuesto; ver §4 Calendario)
Kickoff: 19 preguntas en 2 rondas · researcher: sí
2026-10-11 · otro (app móvil) · common.md@869667b78e5f + agent-system.md@e98697a05a25 (R1) · marcas R/P/NA 2/0/0 · NO APLICA: — · gaps: 2 (nivel de calidad, deadline) · preguntas 19 en 2 rondas · researcher sí

Glosario mínimo (términos que se repiten): **etapa** = sesión de registro
continua dentro del viaje (un día, una excursión, un trekking); **servicio
en primer plano** = proceso de la app que sigue corriendo con la pantalla
apagada porque muestra una notificación permanente; **MediaStore** = la
galería local del teléfono; **EXIF** = metadatos que la cámara escribe en
cada foto (hora, GPS); **GPX / KML / GeoJSON** = formatos abiertos de
recorridos que abren Google Earth, My Maps y la mayoría de las apps de
mapas; **VAD** = detección de voz, distinguir habla de silencio antes de
transcribir; **Media3 Transformer** = la librería de Android para montar
video en el teléfono; **MapLibre** = motor de mapas de código abierto;
**MapTiler** = proveedor de mapas base con clave gratuita; **E2E** =
prueba de extremo a extremo en el emulador.

## 1. Objetivo y métrica de éxito

Construir una app Android de uso personal que, iniciada una vez al
comenzar el viaje, registre sola el recorrido GPS hasta que el usuario
declare el fin del viaje; vincule automáticamente las fotos y videos del
teléfono al punto del recorrido; permita dictar notas que quedan
transcriptas; arme una línea de tiempo; exporte todo en formatos abiertos
a la PC; y genere un video resumen (uno corto de 1 minuto a demanda en el
teléfono, y uno largo narrativo renderizado en la PC) con mapa animado,
fotos, notas y datos de interés de los lugares visitados.

Éxito del proyecto: **un viaje real registrado de punta a punta** que
produce el paquete ZIP exportado (abre en la PC sin la app) y el video
resumen. Umbrales técnicos que lo sostienen (de la spec):

| Medida | Umbral | Dónde se verifica |
|---|---|---|
| Distancia de una etapa vs recorrido de referencia | ±2 % | Fase 1, GPX reproducido en emulador |
| Vinculación de fotos sintéticas | 100 % de los casos de §7.6 | Fase 2, tests + E2E |
| Batería en modo Full caminando, pantalla apagada | ≤ 8 % por hora | Fase 1, prueba real del usuario |
| Batería en modo Económico | ≤ 3 % por hora (objetivo, a medir) | Fase 1, prueba real del usuario |
| Duración del video de escritorio vs pedida | ±5 % | Fase 4, ffprobe |
| Minivideo de 1 min en el Pixel 8 Pro | generado en < 3 min | Fase 4, prueba en dispositivo |

Comparado con la alternativa comercial (ver docs/notes/comparativa-polarsteps.md),
lo que justifica construir: notas de voz transcriptas, vinculación
automática de fotos, exportación inmediata y completa, precisión para
trekking y propiedad total de los datos.

## 2. Datos

- **Fuentes**: GPS del dispositivo (servicio en primer plano), galería
  local (MediaStore) con EXIF, micrófono, barómetro del Pixel 8 Pro para
  desnivel, APIs públicas (Nominatim, Wikipedia, Wikidata, Overpass),
  proveedor de mapas MapTiler.
- **Volumen**: ~7.200 puntos por día a 1 punto cada 5 s (≈1 MB/día en
  SQLite); 30 días ≈ 216.000 puntos. Supera el tope de 25 MB del respaldo
  automático de Android: la base se excluye y el respaldo oficial es el
  `trip.json` automático al cerrar cada etapa.
- **Modelo**: definido en DISENO.md §5.2 (UUIDs, tiempo en UTC epoch ms
  con zona guardada aparte, coordenadas WGS84, medios referenciados por
  URI y huella, nunca copiados hasta exportar). Migraciones de Room
  probadas en cada cambio.
- **Métricas canónicas**: distancia (haversine entre puntos válidos),
  tiempo en movimiento, desnivel con histéresis de 3 m, velocidad máxima
  como percentil 98. Fuente única: `core/geo`, con tests. Exportación y
  video consumen esos valores, nunca los recalculan.
- **Privacidad**: todo local; solo salen coordenadas (opcionalmente
  redondeadas a ~1 km) al enriquecer lugares. Zonas de privacidad al
  exportar (Fase 5). Datos personales del propio usuario.
- **Accesos a conseguir (usuario)**: clave gratuita de MapTiler antes
  del ítem 1.6; clave de la API de Claude solo si quiere la reescritura
  opcional (4.3).

## 3. Decisiones técnicas

- Android nativo, Kotlin + Jetpack Compose + Material 3, Hilt, Room (WAL),
  Coroutines/Flow, WorkManager, FusedLocationProvider. Gradle Kotlin DSL
  con catálogo de versiones; versiones verificadas al crear, nunca de
  memoria. minSdk 26, targetSdk 36, compileSdk 37 (verificado en Fase 0: las
  librerías AndroidX vigentes exigen API 37; decisión 2026-10-12).
- Lógica pura en módulos JVM `core/{model,geo,timeline,export,videoplan}`
  con tests que corren sin emulador.
- **Registro continuo** (adenda 1): el servicio vive desde "Iniciar
  viaje" hasta "Terminar viaje". Etapas diarias cortadas a medianoche
  local; **reposo automático** (quieto N minutos → muestreo mínimo;
  movimiento → vuelve) sin botón extra; excursión y trekking como
  sub-etapas declaradas por el usuario que pausan y reanudan la principal.
  Sin permiso de ubicación en segundo plano en la versión 1.
- **Modo Full / Económico** elegido por el usuario (adenda 1). Full =
  perfiles de la spec §6.2. Económico = GPS de alta precisión con intervalo
  30 s, distancia mínima 40 m y lote de 60 s (cambiado el 2026-10-12: con
  prioridad balanceada no hay fijaciones sin Wi-Fi ni celdas); la UI avisa
  que pierde detalle en zigzags y subestima distancia (5-10 %).
- **Mapas**: abstracción `MapRenderer`; en v1 solo MapLibre + MapTiler
  (clave en `local.properties`). Google Maps queda como implementación
  futura. Para el video se componen teselas de MapTiler: zona gris de
  sus términos, aceptada para uso personal sin publicación (decisión
  usuario 2026-10-11); si se publica, migrar a PMTiles (backlog).
- **Fotos**: MediaStore local + EXIF con original (Library API de Google
  Fotos ya no permite leer la biblioteca). Vinculación por hora/EXIF con
  ventana configurable por medio de transporte; desfase de reloj de
  cámara automático (EXIF GPS UTC) y manual.
- **Voz**: grabación AAC siempre; transcripción detrás de la interfaz
  `Transcriber`. Fase 2: reconocedor del sistema desde archivo (API 33+,
  con detección de soporte). Fase 3: whisper.cpp vía NDK con VAD Silero
  obligatorio y confianza por segmento; modelo `base` vs `small` se elige
  midiendo con notas reales. Nube desactivada.
- **Tareas largas** (exportación, transcripción, render): un gestor único
  sobre servicios en primer plano `dataSync`/`mediaProcessing` con
  reanudación por checkpoint; Android limita cada tipo a 6 h por día.
- **Librerías nativas** (whisper, MapLibre): alineación de páginas de 16
  KB verificada en el build.
- **Video**: plan declarativo `VideoPlan` (JSON) independiente del motor.
  Motor A escritorio (Python 3.12 con `uv`, Pillow, numpy, FFmpeg, pydantic,
  typer) en Fase 4. Minivideo de 1 min en el teléfono con motor Media3
  mínimo en Fase 4; motor completo en Fase 5.
- **Entrega**: APK por instalación directa en el Pixel 8 Pro; CLI
  `bitacora-render` en la PC; paquete ZIP de exportación. Publicación en
  Play en pausa (D4).
- **Entorno**: WSL2 con KVM (emulador viable), 4 CPUs y 7 GB de RAM:
  emulador headless con RAM acotada y Gradle con memoria moderada; el
  Pixel 8 Pro por depuración inalámbrica como vía alternativa de pruebas.
- **Git**: `main`/`dev` protegidas en GitHub; ramas `feat/` por fase;
  conventional commits; `tools/run-tests.sh` verde antes de cada merge
  (hook local) y GitHub Actions como segunda red.

## Nivel de calidad

Nivel declarado: **2** (decisión usuario 2026-10-11). Consecuencia que lo
elige: un recorrido mal capturado o perdido es irreversible; exportación
y video son regenerables. Qué implica acá, adaptado a una app:

- Tests JVM obligatorios en todos los módulos `core/*` y test de cada
  migración de Room (regla "nunca perder datos").
- "Contratos de datos" = cada exportación se valida en los tests contra
  JSON Schema (`trip.json`) y XSD (GPX 1.1); KML bien formado y dentro de
  los límites de My Maps.
- "Auto-chequeo del entregable" = al exportar se concilian conteos
  (puntos, medios, notas, lugares en la base = en el ZIP) y los faltantes
  (fotos borradas, URIs rotos) se listan en `LEEME.txt`; nada se omite en
  silencio.
- Artefacto de revisión del code-reviewer en la descripción de cada MR a
  `dev` (convención del cerebro; no es condición de merge en nivel 2).
- Escalada posterior: decisión registrada acá con fecha, nunca
  deslizamiento.

## Cobertura de ítems bloqueantes

- Nivel de calidad del proyecto (common.md) — **RESPONDIDO**: Nivel 2,
  elegido por consecuencia (pérdida irreversible del recorrido), con el
  significado adaptado descrito arriba.
- agent-system.md §1 "¿esto necesita agentes?" — **RESPONDIDO**: no. La
  única llamada a un modelo de lenguaje (4.3) es opcional, puntual,
  restringida al texto fuente y con aprobación del usuario antes de
  usarse; no hay decisiones dinámicas dirigidas por el modelo. Recorrido
  R1: registrado y se sale del checklist; sus demás ítems bloqueantes
  (§2, §3, §6, §7, acierto, límites de acción, separación numérica,
  filtro por permiso) — **NO APLICA**: no hay agente.

Cobertura relevada contra: `common.md` @ `869667b78e5f` (2026-10-11)
Cobertura relevada contra: `agent-system.md` @ `e98697a05a25` (2026-10-11)

## 4. Fases e ítems

Las fases siguen DISENO.md §19 con los cambios de la adenda 1 y los
hallazgos del researcher. Cada fase se trabaja en una rama `feat/fase-N-*`
que mergea a `dev` con tests verdes; al cerrar cada fase el usuario
revisa la evidencia (salida de tests y capturas) y se corre /retro.
Los ítems marcados **(usuario)** requieren una prueba manual del usuario;
su aceptación es la evidencia que él aporta, registrada en
`docs/PROGRESS.md`.

### Fase 0 — Esqueleto y entorno  (est: 2.5 días)
- [x] 0.1 Entorno Linux: JDK 17 (o el que pida el AGP vigente), Android
      SDK command-line tools, platform 36, build-tools, emulador con AVD
      `bitacora36` (Google APIs x86_64), ffmpeg; script
      `tools/setup-linux.sh` que verifica o instala avisando antes.
      Verificar si existe SDK 37 (Android 17) y registrarlo.  (est: 0.5)
      Aceptación: el script termina en 0; `adb devices` lista el emulador
      arrancado headless; `java -version` y `ffmpeg -version` responden.
- [x] 0.2 Proyecto Gradle multi-módulo (`app`, `core/*`, `data`) con
      catálogo de versiones verificado contra Maven, Hilt, Room, Compose,
      navegación; verificación de alineación de 16 KB en el build.  (est: 1)
      Aceptación: `./gradlew assembleDebug test lint` verde; el APK pasa
      la comprobación de alineación de 16 KB; versiones y fecha de
      verificación en `docs/PROGRESS.md`.
- [x] 0.3 Tema claro/oscuro, pantalla de Inicio vacía y Ajustes básicos;
      `strings.xml` en español.  (est: 0.5)
      Aceptación: la app abre en el emulador; captura guardada en
      `docs/notes/evidencia/fase-0/`.
- [x] 0.4 Integración continua en GitHub Actions: lint, tests JVM,
      assembleDebug y pytest condicional; `tools/run-tests.sh` extendido.  (est: 0.5)
      Aceptación: pipeline verde en un push a `dev`.

### Fase 1 — Recorrido continuo (MVP 1)  (est: 13 días)
- [x] 1.1 `core/model` y `data`: tablas de esta fase (trip, segment,
      segment_pause, track_point, stop) con migraciones probadas.  (est: 2)
      Aceptación: tests de migración y de DAOs (Robolectric) verdes.
- [x] 1.2 `core/geo`: haversine, filtros (precisión, saltos imposibles,
      ruido en reposo), métricas (distancia, tiempo en movimiento,
      desnivel con histéresis usando barómetro si existe, velocidad p98),
      detección de paradas robusta a huecos GPS, Douglas-Peucker.  (est: 2.5)
      Aceptación: tests unitarios con fixtures sintéticas por regla;
      distancia del trekking de ejemplo dentro del ±2 % del valor de
      referencia calculado del GPX.
- [x] 1.3 `TrackingService`: servicio en primer plano tipo `location`,
      perfiles por medio, selector Full/Económico, escritura en lote,
      pausa/reanudar, notificación con acciones.  (est: 3)
      Aceptación: E2E con `tools/gpx-replay` sobre el trekking de ejemplo
      produce una etapa con distancia ±2 % y las paradas esperadas; el
      mismo GPX en modo Económico produce ≤ 1/5 de los puntos.
- [x] 1.4 Viaje continuo: máquina de estados del viaje (ACTIVE hasta
      "Terminar viaje"), corte automático de etapa diaria a medianoche
      local, reposo automático con umbrales configurables, sub-etapas
      excursión/trekking anidadas que pausan y reanudan la principal.  (est: 2.5)
      Aceptación: tests JVM de la máquina de estados; E2E con un GPX que
      incluye una parada de 20 min y un cruce de medianoche simulado
      produce dos etapas y un período de reposo registrado.
- [x] 1.5 Recuperación ante fallos: `START_STICKY`, etapa ACTIVE sin
      servicio → reanudar/cerrar, notificación tras reinicio del
      teléfono, referencia monótona de tiempo ante cambios de hora,
      marcado de ubicaciones simuladas.  (est: 1.5)
      Aceptación: tras `adb shell am kill` el registro continúa; tras
      reiniciar el emulador aparece la notificación de reanudar; E2E
      verde.
- [x] 1.6 UI de la fase: asistente de nueva sesión (viaje, excursión,
      trekking; medio; Full/Económico), sesión activa con mapa MapLibre +
      MapTiler y panel de estadísticas, pantalla de fin de etapa y de
      viaje. Requiere la clave de MapTiler del usuario en
      `local.properties`.  (est: 2)
      Aceptación: prueba de Compose de iniciar/pausar/terminar verde;
      capturas en `docs/notes/evidencia/fase-1/`.
- [ ] 1.7 **(usuario)** Prueba real: 1 hora caminando con el Pixel 8 Pro
      en el bolsillo, pantalla apagada, una vez en Full y otra en
      Económico.  (est: 0.5)
      Aceptación: consumo de batería por hora y captura de las
      estadísticas registrados en `docs/PROGRESS.md`; si Full supera el
      8 %/h se abre un ítem de ajuste de perfiles.
      Estado 2026-10-12: salida de 46 min en auto, modo Full, con 5 fotos y 2
      notas de voz (`docs/notes/evidencia/fase-1/1.7-prueba-de-campo.md`);
      falta la medición de batería con pantalla apagada en Económico (y Full).
### Fase 2 — Fotos, voz y línea de tiempo (MVP 2)  (est: 12.5 días)
- [x] 2.1 Permisos de medios en contexto (Android 13/14+, acceso parcial,
      `ACCESS_MEDIA_LOCATION` con original) con pantallas explicativas y
      funcionamiento degradado.  (est: 1)
      Aceptación: E2E en emulador con acceso parcial muestra el aviso y
      ofrece "seleccionar más"; sin permiso, el registro sigue.
- [x] 2.2 `MediaScanner` + observador + barrido por ventana de la etapa
      (excluyendo pendientes); lectura de hora EXIF con offset y de la
      hora GPS UTC; videos con fecha de fin menos duración; exclusiones
      configurables. (Ajuste 2026-10-12, revisión de código: el barrido
      incremental por generación de MediaStore se difiere a Fase 3; el
      observador dispara un barrido por ventana de la etapa abierta, que
      alcanza para el volumen de un viaje.)  (est: 2)
      Aceptación: `tools/sample-trips/make_photos.py` genera fixtures
      que cubren todas las fuentes de hora de §7.5; tests verdes.
- [x] 2.3 Vinculación en `core/geo` (§7.6) con ventana configurable por
      medio de transporte, desfase de reloj de cámara automático (GPS
      UTC) y manual, confianza por método.  (est: 1.5)
      Aceptación: un test por caso de §7.6, todos verdes.
- [x] 2.4 Notas de voz: grabación AAC, `VoiceNoteActivity` desde la
      notificación y la pantalla bloqueada, posición al grabar;
      transcripción con el reconocedor del sistema desde archivo (API
      33+, con detección de soporte y stub de respaldo) detrás de
      `Transcriber`.  (est: 2.5)
      Aceptación: nota grabada en el emulador aparece en la línea de
      tiempo con ubicación; tests de la interfaz con stub.
      Cerrado 2026-10-13: además del emulador, dos notas reales del usuario
      grabadas en el Pixel 8 Pro se transcribieron completas sin internet
      (PR #6; evidencia en `2.4-2.5-notas.md`).
- [x] 2.5 Notas de texto, marcadores y destacados.  (est: 0.5)
      Aceptación: tests de DAO; aparecen en la línea de tiempo.
- [x] 2.6 `core/timeline`: `buildTimeline` puro con agrupación de
      momentos, resúmenes intermedios y cambio de día.  (est: 1.5)
      Aceptación: tests unitarios con fixture de etapa completa.
- [x] 2.7 UI: detalle de viaje con pestañas línea de tiempo / mapa /
      fotos sincronizadas; revisión de medios sin ubicación con
      ubicación manual; detalle de nota de voz editable.  (est: 2.5)
      Aceptación: prueba de Compose del flujo de revisión; capturas.
- [ ] 2.8 **(usuario)** Salida real con fotos y notas de voz.  (est: 0.5)
      Aceptación: porcentaje de fotos vinculadas sin intervención y
      observaciones registrados en `docs/PROGRESS.md`.
- [x] 2.9 Cambiar el medio de transporte durante la sesión (agregado
      2026-10-12 a pedido del usuario): botón en la sesión activa que cambia
      perfil de GPS y filtro sin cerrar la etapa; el cambio queda como evento
      `TransportChange` para la línea de tiempo. La detección automática
      sigue en Fase 3.  (est: 0.5)
      Aceptación: test de repositorio del evento; E2E: cambiar de a pie a
      vehículo en el emulador hace que el filtro acepte 20 m/s sin rechazar.

### Fase 3 — Exportación, transcripción local y tareas largas  (est: 12 días)
- [x] 3.1 `core/export`: `trip.json` + JSON Schema, GPX 1.1 con
      extensiones propias y de Garmin validado contra XSD, KML/KMZ
      particionado por capa dentro de los límites de My Maps, GeoJSON,
      CSV UTF-8 con BOM, Markdown, HTML autocontenido con Leaflet, SRT.  (est: 4)
      Aceptación: tests validan cada formato; test de conciliación de
      conteos base = ZIP con faltantes listados en `LEEME.txt`.
      Cerrado 2026-10-13 (`docs/notes/evidencia/fase-3/3.1-export.md`); sin
      extensiones de Garmin: la app no tiene los datos de sensores que llevan.
- [x] 3.2 Gestor de tareas largas (servicios `dataSync` /
      `mediaProcessing` con tiempo límite y reanudación por checkpoint);
      `ExportWorker` con ZIP y destinos (SAF, compartir, Download).  (est: 2)
      Aceptación: exporta un viaje sintético de 30 días; una interrupción
      simulada reanuda sin duplicar.
      Cerrado 2026-10-13 (`docs/notes/evidencia/fase-3/3.2-export-worker.md`);
      `mediaProcessing` queda para el render de video (Fase 5).
- [x] 3.3 Importación de `trip.json` y de GPX externo.  (est: 1)
      Aceptación: exportar e importar un viaje da la misma base (test).
      Cerrado 2026-10-13 (`docs/notes/evidencia/fase-3/3.3-import.md`).
- [ ] 3.4 whisper.cpp con NDK (alineado a 16 KB), descarga de modelo,
      VAD obligatorio, confianza por segmento, `TranscribeWorker`;
      medición `base` vs `small` con notas reales del usuario.  (est: 3)
      Aceptación: transcribe una nota de prueba en español; tasa de
      error medida sobre al menos 10 notas reales en `docs/PROGRESS.md`
      y default elegido con ese dato.
      Estado 2026-10-13: módulo nativo, VAD, confianza, descarga verificada
      y motor configurable hechos; transcribe notas reales en español
      (`docs/notes/evidencia/fase-3/3.4-whisper.md`). Falta la medición
      sobre ≥10 notas (salida 2.8) para fijar el default con datos.
- [x] 3.5 Respaldo: base excluida del Auto Backup; `trip.json`
      automático al cerrar cada etapa.  (est: 0.5)
      Aceptación: reglas de extracción verificadas en test; archivo
      generado al cerrar una etapa en el emulador.
      Cerrado 2026-10-13 (`docs/notes/evidencia/fase-3/3.5-respaldo.md`).
- [x] 3.6 Nombre de ruta y detección de senderos (Nominatim, Overpass)
      con política de resiliencia (User-Agent, caché, backoff, instancia
      cambiable en Ajustes).  (est: 1)
      Aceptación: el trekking de ejemplo recibe nombre de sendero (test
      con respuestas grabadas).
      Cerrado 2026-10-13 (`docs/notes/evidencia/fase-3/3.6-nombre-de-ruta.md`).
- [ ] 3.7 **(usuario)** Importar el KML en Google My Maps y abrir el
      HTML en la PC.  (est: 0.5)
      Aceptación: captura de My Maps y del HTML en `docs/notes/evidencia/fase-3/`.

### Fase 4 — Enriquecimiento, video de escritorio y minivideo  (est: 13 días)
- [x] 4.1 Clientes Nominatim, Wikipedia (detrás de abstracción por la
      transición de su API), Wikidata y Overpass; caché; ranking;
      `EnrichWorker`; redondeo opcional de coordenadas.  (est: 2.5)
      Aceptación: tests con respuestas grabadas; etapa de ejemplo
      produce ≤ 5 lugares rankeados con atribución.
      Cerrado 2026-10-13: `docs/notes/evidencia/fase-4/4.1-lugares.md`.
- [x] 4.2 Pantalla de lugares con aprobación/descarte y atribuciones.  (est: 1)
      Aceptación: prueba de Compose; capturas.
      Cerrado 2026-10-13: `docs/notes/evidencia/fase-4/4.2-pantalla-lugares.md`.
- [x] 4.3 Reescritura opcional con modelo de lenguaje (clave del usuario
      en DataStore cifrado, restringida al texto fuente, desactivada por
      default).  (est: 0.5)
      Aceptación: sin clave, la función no aparece; con clave, el texto
      conserva URL y licencia de origen (test con cliente simulado).
      Cerrado 2026-10-13: `docs/notes/evidencia/fase-4/4.3-reescritura-ia.md`
      (la clave vive en un archivo cifrado con el Keystore, no en DataStore:
      las reglas de respaldo no lo incluyen).
- [x] 4.4 `core/videoplan`: planificador puro con preset "rápido 1 min"
      y duraciones 1/3/5/10 min, selección por favorita > destacada >
      nitidez, sin duplicados.  (est: 1.5)
      Aceptación: tests; el plan de 1 min para el viaje de ejemplo entra
      en ±5 %.
      Cerrado 2026-10-13: `docs/notes/evidencia/fase-4/4.4-videoplan.md`
      (pHash y nitidez llegan nulos hasta que la Fase 5 los calcule).
- [x] 4.5 `desktop-renderer` (uv): CLI `plan` y `render`, mapa animado
      con teselas de MapTiler, Ken Burns, clips, tarjetas, subtítulos de
      voz, música con atenuación, créditos, HEIC, corrección de altitud
      con modelo de elevación.  (est: 5)
      Aceptación: `uv run pytest` verde; MP4 de duración ±5 %, 1080p, con
      audio según ffprobe; cuadros clave comparados por SSIM.
      Cerrado 2026-10-13: `docs/notes/evidencia/fase-4/4.5-desktop-renderer.md`
      (59,0 s / 60 s objetivo, 1920×1080, h264+aac; teselas de MapTiler
      opcionales por `--tiles`, pendiente de probar con la clave del usuario).
- [ ] 4.6 Minivideo de 1 min en el teléfono: motor Media3 mínimo
      (título, fotos con Ken Burns, mapa estático por etapa, subtítulos,
      música, créditos) desde la pantalla de fin de etapa.  (est: 2.5)
      Aceptación: video de 1 min generado en el Pixel 8 Pro en menos de
      3 minutos y reproducible; evidencia en `docs/PROGRESS.md`.

### Fase 5 — Video completo en el dispositivo y extras  (est: 10 días)
- [ ] 5.1 Motor Media3 completo (mapa animado, clips, vista previa del
      plan editable) bajo el gestor de tareas largas.  (est: 4)
      Aceptación: video de 3 min generado en el Pixel 8 Pro; el mismo
      `VideoPlan` rinde en escritorio y teléfono.
- [ ] 5.2 Cámara integrada (CameraX) con EXIF GPS y nota sobre la foto.  (est: 1.5)
      Aceptación: foto tomada en el emulador queda vinculada con
      confianza 1,0.
- [ ] 5.3 Widget, mosaico de Configuración rápida y accesos directos.  (est: 1.5)
      Aceptación: nota de voz iniciada desde el mosaico en el emulador.
- [ ] 5.4 Mapas offline con MapLibre (descarga de región).  (est: 1.5)
      Aceptación: con red desactivada en el emulador el mapa de la
      región descargada se muestra.
- [ ] 5.5 Zonas de privacidad aplicadas al exportar.  (est: 1)
      Aceptación: test: puntos dentro del radio no aparecen en el ZIP y
      el conteo de excluidos se reporta.
- [ ] 5.6 Google Fotos (Picker e importación / subida a álbum propio).
      DESEABLE A FUTURO (2026-10-11, usuario): requiere proyecto en
      Google Cloud con OAuth y el Picker no entrega ubicación.

### Fase 6 — Pulido y publicación  (est: 5 días)
POSTPUESTO — ON-HOLD (2026-10-11, usuario): uso personal por
instalación directa; se retoma solo si se decide publicar (D4).
- [ ] 6.1 Accesibilidad, traducciones, iconografía y onboarding final.  (est: 2)
- [ ] 6.2 Declaraciones de Play Console, seguridad de los datos y
      política de privacidad; migrar el mapa del video a PMTiles.  (est: 3)

### Calendario
Suma de estimaciones activas (Fases 0 a 5): **62,5 días de trabajo de
agente**, sin contar los tiempos de revisión del usuario ni las pruebas en
la calle. A un ritmo de proyecto personal de 3 a 4 sesiones por semana,
eso ocupa unas 20 semanas: **Fin propuesto 2027-03-12**. Hitos
intermedios proyectados: MVP 1 (recorrido) hacia mediados de noviembre
de 2026; MVP 2 (fotos y voz) hacia fin de diciembre; exportación a fin de
enero; videos a comienzos de marzo. Si hay un viaje con fecha, se
reordena para llegar con MVP 2. El Gantt (`reports/gantt.xlsx`) reparte
las fechas; las reales las toma de los commits.

## 5. Decisiones divergentes

- Decisión: fotos desde la galería local, no desde Google Fotos · Pedido
  original: "la app accede a Google Fotos" · Razón: la API de Google Fotos
  ya no permite leer la biblioteca del usuario (31/03/2025); las fotos de
  la cámara están en la galería local igual. Confirmado por el usuario
  2026-10-11.
- Decisión: mapa en la app con MapLibre + MapTiler; "mapa en Google Maps"
  vía exportación KML a My Maps · Pedido original: Google Maps · Razón:
  la clave de Google exige facturación y sus mapas no pueden usarse en el
  video; Google no tiene API para crear mapas en My Maps. Google Maps
  queda como implementación futura detrás de la abstracción.
- Decisión: componer teselas de MapTiler en los cuadros del video ·
  Práctica: los términos de MapTiler prohíben guardar su mapa como imagen
  estática fuera de su API · Razón: uso personal sin publicación
  (decisión usuario 2026-10-11); si se publica, PMTiles.
- Decisión: ventana de interpolación foto-recorrido de 5/10 min
  configurable por medio · Práctica común: 30 min (exiftool) · Razón:
  con registro continuo los huecos largos son raros; la ventana queda
  configurable y los valores de la spec son el default.
- Decisión: extensiones GPX propias y de Garmin a la vez · Práctica
  común: solo Garmin · Razón: compatibilidad con herramientas sin perder
  campos propios (precisión).
- Decisión: modelo whisper elegido midiendo con notas reales · Práctica
  común: `small` para español · Razón: los benchmarks públicos son solo
  en inglés; se decide con dato propio en 3.4.
- Decisión: integración continua en GitHub Actions · Convención del
  cerebro: GitLab CI · Razón: proyecto personal alojado en GitHub.

## 6. Riesgos y pendientes

- PENDIENTE: decidir publicación en Google Play (D4) — bloquea Fase 6
  (en pausa, no bloquea nada más).
- Riesgo: entorno WSL2 con 4 CPUs y 7 GB de RAM; Gradle + emulador
  simultáneos pueden ir lentos. Mitigación: emulador headless con RAM
  acotada; pruebas en el Pixel 8 Pro por depuración inalámbrica.
- Riesgo: el Pixel 8 Pro corre Android 17 y la spec compila contra SDK 36.
  Compatible hacia adelante; verificar en 0.1 si el SDK 37 ya está
  publicado y registrar cambios de comportamiento relevantes.
- Riesgo: un servicio que vive semanas será matado por el sistema al
  menos una vez; la recuperación (1.5) es de Fase 1, no opcional.
- Riesgo: las APIs de Media3 cambian rápido; el minivideo adelanta ese
  riesgo a Fase 4. Mitigación: versión fijada, motor mínimo primero.
- Riesgo: APIs comunitarias (Nominatim, Overpass) sobrecargadas o con
  cambio de políticas; la Wikipedia migra su API de resúmenes en 2026-27.
  Mitigación: abstracción, caché, backoff, instancia cambiable.
- Riesgo: Whisper inventa texto en silencio o ruido exterior.
  Mitigación: VAD obligatorio y confianza por segmento (3.4).
- Acciones del usuario: crear clave gratuita de MapTiler antes de 1.6;
  clave de la API de Claude solo si quiere 4.3; pruebas en la calle en
  1.7, 2.8, 3.7 y 4.6.

## 7. Fuera de alcance

- Red social, compartir en vivo, seguidores (§1.4).
- Sincronización multi-dispositivo en tiempo real; versión iOS.
- Navegación turn-by-turn; map matching a la red vial (futuro: OSRM/Valhalla).
- Narración por voz sintética (TTS); subida directa a Google Drive por API.
- Libro impreso y planificador de viaje (lo que ofrece la alternativa
  comercial; no es el objetivo).
- Google Fotos Picker / Library (5.6, deseable a futuro); implementación
  Google Maps del `MapRenderer` (futura); transcripción en la nube
  (prevista en la interfaz, no se implementa).
- Permiso de ubicación en segundo plano (`ACCESS_BACKGROUND_LOCATION`)
  en la versión 1; se reconsidera solo si el consumo en vehículo lo exige.
- Publicación en Google Play (Fase 6, en pausa).

## 8. Oportunidades fuera de alcance

Evidencia en `docs/notes/estado-del-arte.md` §5. Aceptadas como ítems:
detector de paradas robusto a huecos (1.2) y corrección de altitud con
modelo de elevación en escritorio (4.5).

- Oportunidad: cartografía propia PMTiles/Protomaps · Capacidad: mapas
  offline y video sin términos de terceros · Costo: medio · Fuente:
  MapLibre Android `pmtiles://` · Estado: backlog (se activa si se publica).
- Oportunidad: vista previa real del video con `CompositionPlayer` ·
  Capacidad: ver el video antes de renderizar · Costo: bajo-medio tras
  5.1 · Fuente: Media3 1.9+ · Estado: backlog.
- Oportunidad: reescritura con ML Kit GenAI en el dispositivo · Capacidad:
  textos de bitácora sin clave ni red · Costo: bajo-medio · Fuente: ML
  Kit GenAI (Gemini Nano) · Estado: backlog.
- Oportunidad: nombre de localidad en vivo sin red (GeoNames) · Costo:
  bajo-medio · Estado: backlog.
- Oportunidad: selección estética de fotos y recorte 9:16 que respete
  caras · Costo: medio · Fuente: NIMA, ML Kit · Estado: backlog.
- Oportunidad: segundo motor de transcripción multimodelo (`sherpa-onnx`)
  · Costo: medio · Estado: backlog, depende de la medición de 3.4.
- Oportunidad: sobrevuelo 3D del mapa · Estado: descartada (no viable en
  MapLibre Native hoy).
