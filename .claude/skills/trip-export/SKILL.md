---
name: trip-export
description: Export and import trips in Bitácora de Viaje — ZIP package layout, trip.json with JSON Schema, GPX 1.1 with extensions and waypoints, KML/KMZ compatible with Google My Maps and Earth, GeoJSON, CSV for Excel, Markdown and self-contained HTML diary with Leaflet, SRT subtitles, Storage Access Framework, sharing, and importing trip.json or external GPX. Use for core:export, ExportWorker, or import features.
---

# Exportación e importación

Especificación: `docs/DISENO.md` §12. **El `trip.json` es el contrato con el renderizador de escritorio** (skill `trip-video`): cualquier cambio de formato sube `schema_version` y actualiza `docs/trip.schema.json` y el renderizador.

## Arquitectura
- `core:export` (Kotlin JVM puro): una interfaz por formato que escribe a un `OutputStream`/`Writer` y recibe un `TripExport` (modelo de dominio completo, ya cargado).
  ```kotlin
  interface TripWriter { val path: String; fun write(trip: TripExport, out: OutputStream) }
  ```
- `:app` → `ExportWorker`: carga desde Room, arma `TripExport`, crea el ZIP con `ZipOutputStream` escribiendo **en streaming** (no armar el ZIP en memoria), copia los medios desde los `content://` URI y reporta el progreso.
- XML con `javax.xml.stream.XMLStreamWriter` (o un escritor propio con escape correcto). **Nunca concatenar XML a mano sin escapar** `& < > " '`.
- JSON con `kotlinx.serialization`.

## Estructura del ZIP
Ver `DISENO.md` §12.1. Nombres de archivo: sin espacios ni caracteres problemáticos (normalizar a ASCII con guiones), fecha ISO al principio para que se ordenen bien.

## trip.json
- Campos: `schema_version`, `app_version`, `exported_utc` (ISO 8601), `trip`, `segments[]` (con `stops`, `media`, `voice_notes`, `text_notes`, `stats`, `points` opcional o `points_file`), `places[]`, `enrichments[]`, `attributions[]`.
- Tiempos: ISO 8601 con offset (`2026-10-04T09:12:33-03:00`) **y** `*_utc_ms`, para comodidad de ambos lados.
- Rutas a archivos relativas al ZIP (`media/fotos/2026-10-04/IMG_1234.jpg`).
- `docs/trip.schema.json` (JSON Schema draft 2020-12) generado y mantenido a mano. Prueba: la salida del exportador valida contra el esquema (usar una librería de validación JSON Schema solo en las dependencias de prueba).

## GPX 1.1
```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Bitacora de Viaje"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:bv="https://bitacoraviaje.app/gpx/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata><name>Patagonia 2026</name><time>2026-10-02T12:00:00Z</time></metadata>
  <wpt lat="-49.3315" lon="-72.8861"><time>2026-10-04T13:12:00Z</time><name>Foto</name>
       <desc>Laguna de los Tres</desc><link href="../media/fotos/2026-10-04/IMG_1234.jpg"/><type>photo</type></wpt>
  <trk><name>Día 3 · Trekking</name><type>hiking</type>
    <trkseg>
      <trkpt lat="-49.3310" lon="-72.8860"><ele>412.3</ele><time>2026-10-04T12:00:05Z</time>
        <extensions><bv:speed>1.21</bv:speed><bv:hacc>4.0</bv:hacc></extensions></trkpt>
    </trkseg>
  </trk>
</gpx>
```
- Orden de los elementos según el XSD: `metadata`, `wpt*`, `rte*`, `trk*`, `extensions`. Dentro de `trkpt`: `ele`, `time`, …, `extensions`.
- Un `<trkseg>` nuevo en cada pausa.
- Excluir los puntos con `is_filtered_out` salvo que se pida lo contrario.
- Coordenadas con 7 decimales; elevación con 1; tiempo UTC con `Z`.
- **Prueba:** validar contra el XSD oficial de GPX 1.1 (guardar una copia en `core/export/src/test/resources/gpx.xsd`) con `javax.xml.validation`.

## KML / KMZ
- Una `Document` con `Style` por etapa (color de línea distinto por etapa; formato de color KML `aabbggrr`).
- Una `Folder` por etapa: `Placemark` con `LineString` (`<tessellate>1</tessellate>`, coordenadas `lon,lat,alt`, **longitud primero**) usando el recorrido **simplificado a 30 m**.
- `Placemark` de fotos, notas y paradas con `<description><![CDATA[ html ]]></description>` (texto, hora local, miniatura en KMZ).
- Compatibilidad con **Google My Maps**: limitar la cantidad de capas y elementos (simplificar el recorrido y agrupar fotos si hace falta). **Verificar los límites vigentes de My Maps.** My Maps no muestra imágenes embebidas del KMZ; en el KML para My Maps, poner solo texto.
- **KMZ** = ZIP con `doc.kml` + `files/` (miniaturas de 512 px). Para Google Earth.
- Generar los dos: `viaje.kml` (para My Maps) y `viaje.kmz` (para Earth).

## GeoJSON
`FeatureCollection` según RFC 7946: coordenadas `[lon, lat, alt]`, `LineString` por etapa con `properties` (tipo, nombre, estadísticas), `Point` por evento con `properties.kind`.

## CSV
- UTF-8 **con BOM** (`﻿`) para que Excel detecte la codificación.
- Separador `,`, comillas dobles y escape `""` según RFC 4180.
- Columnas de tiempo: `time_utc` (ISO) y `time_local` (ISO con offset).
- `puntos.csv`, `medios.csv`, `notas.csv` (voz y texto juntas, con columna `tipo`), `lugares.csv`.

## Markdown y HTML
- `bitacora.md`: un `#` por viaje, `##` por día, `###` por etapa, y eventos en orden con hora local, texto y enlace a la foto relativa.
- `bitacora.html`: **un solo archivo**; Leaflet desde CDN (cdnjs o unpkg); datos embebidos en un `<script type="application/json">`; mapa con el recorrido y marcadores; línea de tiempo al costado; fotos con `<img src="media/...">` relativas; teselas de un proveedor con atribución (si no hay clave, OSM estándar para la *visualización interactiva liviana* está permitida con atribución, pero no para descargas masivas). Debe abrirse con doble clic (`file://`).
- Pie con las atribuciones (Wikipedia CC BY-SA, OSM ODbL).

## SRT
```
1
00:12:03,000 --> 00:12:15,500
Llegamos a la laguna, el viento es tremendo.
```
Tiempo relativo al inicio de la etapa. Usar los `segments` de la transcripción si existen; si no, toda la nota en un bloque.

## Destinos (Storage Access Framework)
```kotlin
val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    uri?.let { vm.export(it) }
}
createZip.launch("Patagonia-2026_2026-10-18.zip")
```
- Compartir: `FileProvider` + `Intent.ACTION_SEND` con `FLAG_GRANT_READ_URI_PERMISSION`.
- Carpeta `Download/BitacoraViaje/` con `MediaStore.Downloads` (API 29+) y `RELATIVE_PATH = "Download/BitacoraViaje"`.
- Verificar el espacio libre antes de empezar (tamaño estimado = suma de los medios + 10 %).
- ExportWorker con `setForeground` (tipo `dataSync`; **verificar** sus límites de tiempo en Android 15+ y, si molestan, usar trabajo agendado sin servicio en primer plano o dividir en partes).

## Importación
- **trip.json + ZIP:** validar `schema_version`, crear entidades nuevas (o reemplazar si el `trip.id` existe, preguntando al usuario) y copiar los medios a `Pictures/BitacoraViaje/<trip>/` vía MediaStore.
- **GPX externo:** cada `<trk>` es una etapa; los `<trkseg>`, pausas; los `<wpt>`, marcadores. Tolerar archivos sin `<time>` (sin tiempos, la etapa no se puede vincular con fotos; avisar).
- Parser XML tolerante (`XmlPullParser` en Android; en `core`, StAX).

## Pruebas
- Fixture `TripExport` sintético → escribir todos los formatos → validar GPX (XSD), JSON (Schema), KML (bien formado + chequeos de coordenadas `lon,lat`), CSV (re-parseo y conteo de filas), HTML (contiene el JSON embebido y parsea).
- **Ida y vuelta:** exportar → importar → comparar el modelo (iguales salvo los IDs regenerados, si corresponde).
- Caracteres especiales en títulos y notas (`ñ á & < " emoji`).
