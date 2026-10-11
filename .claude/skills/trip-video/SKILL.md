---
name: trip-video
description: Generate the trip summary video for Bitácora de Viaje — engine-independent VideoPlan JSON and its planner, animated route map frames, Ken Burns photos, video clips, place cards, voice-note subtitles, music ducking, credits; desktop renderer in Python + FFmpeg (built first, testable on Linux) and on-device renderer with Media3 Transformer. Use for desktop-renderer/, the VideoPlan planner, RenderWorker or the video creation screen.
---

# Video resumen

Especificación: `docs/DISENO.md` §13. Orden de trabajo: **planificador → renderizador de escritorio → motor Media3**.

## 1. VideoPlan (contrato)
- Esquema en `docs/video-plan.schema.json`; ejemplo en `DISENO.md` §13.3.
- Tipos de escena: `title`, `map_route` (estilos `overview` | `follow`), `chapter`, `photo`, `video_clip`, `voice_overlay`, `place_card`, `stats`, `credits`.
- Las referencias (`media`, `segments`, `place`, `voice`) son IDs del `trip.json`.
- El plan es **determinista**: la misma entrada y los mismos parámetros dan el mismo plan (sin aleatoriedad, o con una semilla fija).

## 2. Planificador (lógica pura)
Implementar **dos veces con las mismas reglas**: en Kotlin (`core:timeline`, para el teléfono) y en Python (`desktop-renderer`). Para evitar divergencias, compartir **pruebas de conformidad**: casos en `tools/plan-fixtures/*.json` (entrada + parámetros + plan esperado) que ambas implementaciones deben pasar.

Algoritmo:
1. Presupuesto total `T` (segundos). Reservar lo fijo: título 5 s, mapa general 7 s, créditos 6 s, y por etapa un capítulo de 2,5 s, un mapa de 5 s y estadísticas de 4 s.
2. Repartir el resto entre las etapas según su riqueza: `r = 1·fotos_elegibles + 2·destacados + 1,5·notas_voz + 2·lugares_aprobados`, con un piso mínimo por etapa.
3. Dentro de cada etapa, candidatos en orden cronológico; elegibles = no excluidos, no duplicados (pHash), nitidez sobre el umbral. Prioridad: favoritas > cerca de un destacado (±2 min) > con nota de voz cercana > nitidez. Elegir con **dispersión temporal**: dividir la etapa en `k` ventanas iguales y tomar la mejor de cada una.
4. Duraciones: foto 3,5 s (2,5 s si hay muchas); clip de video máximo 6 s, tomando el tramo central o el de mayor movimiento; tarjeta de lugar 5 s.
5. Cada nota de voz se asocia a la foto más cercana en el tiempo (±3 min) como subtítulo; si no hay foto, va un `voice_overlay` sobre el mapa.
6. Ken Burns: alternar direcciones (acercar/alejar, paneo izq./der.), con zoom de 1,0 a 1,15.
7. Si sobran segundos, alargar las fotos; si faltan, quitar las de menor puntaje.

## 3. Renderizador de escritorio (Python) — fase 4
```
desktop-renderer/
├── pyproject.toml          # [project.scripts] bitacora-render = "bitacora_render.cli:app"
├── bitacora_render/
│   ├── cli.py              # typer: comandos plan, render, preview
│   ├── model.py            # pydantic: TripJson, VideoPlan (validan los esquemas)
│   ├── planner.py
│   ├── mapframes.py        # mapa base + cuadros animados
│   ├── cards.py            # títulos, capítulos, tarjetas de lugar, estadísticas, créditos (Pillow)
│   ├── kenburns.py
│   ├── subtitles.py        # .ass con estilo
│   ├── audio.py            # música + voces + ducking
│   ├── ffmpeg.py           # wrappers y verificación de la versión de ffmpeg
│   └── fonts/              # fuente libre (p.ej. Inter u Open Sans, OFL) incluida
└── tests/
```
Dependencias: `pydantic`, `typer`, `Pillow`, `numpy`, `staticmap` (o `contextily` + `matplotlib`), `requests`; pruebas: `pytest`. Sistema: `ffmpeg` con `libx264` y `aac`.

### Estrategia de render
Renderizar **cada escena a un clip intermedio** (`work/scene_###.mp4`, mismo códec, resolución, fps y `pix_fmt yuv420p`, con pista de audio silenciosa si no tiene audio) y después concatenar con el *concat demuxer*. Agrega audio general y subtítulos en un pase final. Ventajas: paralelizable, se puede reintentar por escena y depurar.

### Comandos FFmpeg de referencia
Foto con Ken Burns (1920×1080, 3,5 s a 30 fps = 105 cuadros):
```bash
ffmpeg -y -loop 1 -i foto.jpg -f lavfi -i anullsrc=r=48000:cl=stereo -t 3.5 \
  -filter_complex "[0:v]scale=3840:-2,crop=3840:2160,zoompan=z='min(1+0.15*on/105,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=105:s=1920x1080:fps=30,format=yuv420p[v]" \
  -map "[v]" -map 1:a -c:v libx264 -preset medium -crf 20 -c:a aac -shortest scene_010.mp4
```
- Escalar a 2× antes de `zoompan` reduce el temblor.
- Fotos verticales en 16:9: fondo con la misma foto desenfocada (`boxblur`) y la foto encima, centrada.
- Respetar la orientación EXIF (`ImageOps.exif_transpose` de Pillow antes de pasarla a ffmpeg).

Clip de video recortado y normalizado:
```bash
ffmpeg -y -ss 2.0 -to 8.0 -i VID.mp4 -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=30,format=yuv420p" \
  -c:v libx264 -crf 20 -c:a aac -ar 48000 -ac 2 scene_011.mp4
```
Concatenación:
```bash
ffmpeg -y -f concat -safe 0 -i scenes.txt -c copy video_sin_musica.mp4
```
Música con *ducking* bajo la voz:
```bash
ffmpeg -y -i video_sin_musica.mp4 -i musica.mp3 -filter_complex \
 "[1:a]volume=0.4,aloop=loop=-1:size=2e9[m];[m][0:a]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=400[duck];[duck][0:a]amix=inputs=2:duration=first:normalize=0[a]" \
 -map 0:v -map "[a]" -c:v copy -c:a aac -shortest final.mp4
```
Subtítulos quemados: `-vf "subtitles=notas.ass"` (requiere ffmpeg con `libass`). Generar `.ass` con un estilo legible (fuente incluida, borde, fondo semitransparente).

### Mapa animado (`mapframes.py`)
1. Bounding box de los puntos de la escena + 10 % de margen, ajustado a la relación de aspecto.
2. Mapa base: con `staticmap`, componer teselas de un **proveedor con clave** (configurable por URL de plantilla, p.ej. MapTiler) con caché en disco `~/.cache/bitacora-render/tiles`. **No usar** `tile.openstreetmap.org` ni teselas de Google. Sin clave: modo "sin teselas" (fondo liso + contorno, opcional con Natural Earth) para que las pruebas funcionen offline.
3. Proyección Web Mercator para convertir lat/lon → píxeles del mapa base.
4. Para el cuadro `i` de `N`: el tiempo `t_i` es la interpolación entre el inicio y el fin del tramo (con *ease-in-out*); dibujar con Pillow (`ImageDraw.line`, ancho 6 px, antialiasing por sobremuestreo 2×) la polilínea hasta `t_i` (interpolando el último segmento), un marcador en la punta y, en `follow`, recortar y escalar una ventana que sigue la punta con suavizado.
5. Escribir los cuadros en un pipe a ffmpeg (`-f rawvideo -pix_fmt rgb24 -s 1920x1080 -r 30 -i -`) en lugar de guardar miles de PNG.
6. Atribución del mapa siempre visible en una esquina.

### Tarjetas (`cards.py`)
Pillow con la fuente incluida. Título: foto de portada desenfocada + texto grande. Tarjeta de lugar: panel semitransparente con el nombre, 2-3 líneas de texto (con ajuste de línea) y la fuente en letra chica. Estadísticas: íconos y cifras con formato local (`8,3 km`, `1.204 m`).

### CLI
```bash
bitacora-render plan   EXPORT_DIR --duration 180 --aspect 16:9 [--music musica.mp3] -o plan.json
bitacora-render render EXPORT_DIR plan.json -o resumen.mp4 [--jobs 4] [--keep-work]
bitacora-render preview EXPORT_DIR plan.json --scene 12 -o cuadro.png   # un cuadro, para revisar rápido
```

### Pruebas
- `tests/fixtures/`: un export sintético pequeño (generado por `tools/sample-trips`) con 10 fotos, 1 video corto (generado con `ffmpeg -f lavfi -i testsrc`), 2 notas de voz (generadas con `sine`) y 2 lugares.
- Validar el plan contra el esquema; pruebas de conformidad con `tools/plan-fixtures`.
- Render completo en **baja resolución** (`--preview-scale 0.25`) en las pruebas, para que sean rápidas.
- `ffprobe -v error -show_entries format=duration:stream=codec_type,width,height -of json` → verificar la duración (±5 %), la resolución y que hay video y audio.
- Cuadros clave (`preview`) comparados con imágenes de referencia por SSIM ≥ 0,95 (actualizar las referencias a propósito, con un flag).

## 4. Motor en el dispositivo (Media3 Transformer) — fase 5
> **La API de Media3 cambia entre versiones.** Fijar la versión, leer la guía y el changelog de esa versión, y adaptar los fragmentos de abajo, que son orientativos.

- Dependencias: `androidx.media3:media3-transformer`, `media3-effect`, `media3-common` (y `media3-exoplayer` para la vista previa).
- Construcción:
  ```kotlin
  val photo = EditedMediaItem.Builder(
          MediaItem.Builder().setUri(photoUri).setImageDurationMs(3500).build())
      .setFrameRate(30)
      .setEffects(Effects(listOf(), listOf(kenBurns(fromRect, toRect), Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP), captionOverlay)))
      .build()
  val clip = EditedMediaItem.Builder(
          MediaItem.Builder().setUri(videoUri)
              .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(2000).setEndPositionMs(8000).build())
              .build())
      .setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT))))
      .build()
  val videoSeq = EditedMediaItemSequence(listOf(titleCard, mapClip, photo, clip /* ... */))
  val musicSeq = EditedMediaItemSequence(listOf(musicItem), /* isLooping = */ true)
  val composition = Composition.Builder(listOf(videoSeq, musicSeq)).build()
  Transformer.Builder(context)
      .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(listener).build()
      .start(composition, outputPath)
  ```
- **Ken Burns:** un `MatrixTransformation` que devuelve una `Matrix` según `presentationTimeUs` (escala y traslación interpoladas).
- **Textos:** `OverlayEffect` con `TextOverlay` (`SpannableString` con estilo) o `BitmapOverlay` (tarjetas dibujadas con `Canvas`), con `OverlaySettings` para la posición.
- **Tarjetas y mapa animado:** prerenderizar con `Canvas` y codificar a un MP4 corto con `MediaCodec` + `MediaMuxer` (o una secuencia de imágenes); después entran como `EditedMediaItem` comunes. Mapa base: `MapSnapshotter` de MapLibre (offline si la región está descargada).
- **Subtítulos de voz:** `TextOverlay` con una ventana de tiempo (overlay que devuelve texto vacío fuera de su intervalo).
- **Progreso:** `transformer.getProgress(ProgressHolder)` consultado cada 500 ms → `setProgress` del Worker.
- **RenderWorker:** `setForeground` con el tipo `mediaProcessing` (Android 15+; **verificar** el límite de tiempo y la declaración del permiso `FOREGROUND_SERVICE_MEDIA_PROCESSING`) o `dataSync` en versiones anteriores. Cancelable.
- **Salida:** `Movies/BitacoraViaje/<trip>_<fecha>.mp4` vía MediaStore.
- **Vista previa:** `CompositionPlayer` de Media3 (si está disponible en la versión fijada) o reproducir el MP4 resultante.
- **Rendimiento:** objetivo de 1 min de video en < 5 min en gama media; si no se alcanza, bajar a 720p por defecto.

## 5. Pantalla "Crear video"
Parámetros (§13.2) → **Generar plan** → lista de escenas editable (arrastrar para reordenar, quitar, cambiar duración, cambiar foto) → **Renderizar** → progreso → reproductor + **Compartir** / **Guardar**. Opción "Exportar para renderizar en la PC" (export ZIP + `plan.json`).

## 6. Derechos y atribución
- Sin música incluida: solo un archivo elegido por el usuario.
- Créditos al final con todas las atribuciones de `trip.json.attributions[]` + el proveedor de teselas.
