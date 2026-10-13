# bitacora-render

Renderizador de escritorio del video resumen de **Bitácora de Viaje** (DISENO §13.5). Toma la
carpeta exportada por la app (con `trip.json`) y produce un MP4 (H.264 + AAC) a partir de un
`video_plan.json`, el mismo plan declarativo que entiende el motor del teléfono.

## Requisitos
- Python 3.11+ y [`uv`](https://docs.astral.sh/uv/).
- `ffmpeg` y `ffprobe` en el PATH, con `libx264`, `aac` y `libass` (subtítulos). La build
  estática de johnvansickle.com y los paquetes de Ubuntu sirven.

## Uso
```bash
# 1. Plan: mismas reglas que la app (duración 60/180/300/600 s, 16:9 o 9:16, voz como subtítulos/audio/ambos)
uv run bitacora-render plan  carpeta-exportada/ --duration 180 --aspect 16:9 -o plan.json
# 2. Render completo
uv run bitacora-render render carpeta-exportada/ plan.json -o resumen.mp4 --jobs 4
# 3. Un cuadro de una escena, para revisar rápido
uv run bitacora-render preview carpeta-exportada/ plan.json --scene 3 -o cuadro.png
```
Opciones útiles:
- `--preview-scale 0.25` en `render`: prueba rápida en baja resolución.
- `--tiles 'https://api.maptiler.com/maps/outdoor-v2/256/{z}/{x}/{y}.png?key=TU_CLAVE'`: mapa con
  teselas de un proveedor con clave (se cachean en `~/.cache/bitacora-render/tiles`). Sin esta
  opción el mapa es un fondo liso con el recorrido: no se usan teselas de `tile.openstreetmap.org`.
- `--music cancion.mp3` en `plan`: música del usuario, atenuada bajo las voces.
- `--dem` en `plan`: corrige las altitudes con un modelo de elevación (OpenTopoData, SRTM 30 m)
  antes de calcular el desnivel de las tarjetas de estadísticas. Requiere internet.
- `--voice audio|both` en `render`: mezcla el audio original de las notas de voz.

## Cómo funciona
Cada escena del plan se renderiza a un clip intermedio normalizado (`scene_###.mp4`), se
concatenan con el *concat demuxer*, se queman los subtítulos `.ass` y por último se mezclan
voces y música. Las fotos usan `zoompan` (Ken Burns) sobre una copia a 2×; las verticales se
apoyan sobre su propia versión desenfocada. El mapa se dibuja con Pillow cuadro a cuadro y se
envía a ffmpeg por una tubería (nunca miles de PNG). La fuente DejaVu Sans va incluida.

## Pruebas
```bash
uv run pytest -q           # planificador (conformidad con tools/plan-fixtures), tarjetas, mapa, render en baja resolución
UPDATE_REFS=1 uv run pytest -q   # acepta a propósito un cambio visual en tests/refs/
```
