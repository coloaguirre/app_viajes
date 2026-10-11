---
name: media-linking
description: Find, read and link the user's photos and videos to the trip track in Bitácora de Viaje — MediaStore queries and ContentObserver, Android 13/14 media permissions and partial access, ACCESS_MEDIA_LOCATION with setRequireOriginal, EXIF time/GPS parsing, video metadata, linking algorithm, duplicates and sharpness, CameraX capture, Google Photos Picker/Library constraints. Use for the media package, media review screen, or anything touching photos.
---

# Fotos y videos

Especificación: `docs/DISENO.md` §7.

## Lo primero: Google Fotos NO es la fuente principal
Desde el 31/03/2025 la **Library API** de Google Fotos solo lista y lee lo que **creó la propia app**. Para elegir fotos de toda la biblioteca está la **Picker API**, que requiere selección manual del usuario. Por eso:
- **Fuente principal: MediaStore** (las fotos de la cámara están en el teléfono).
- Picker de Google Fotos: solo para importar fotos de otros dispositivos (fase 5).
- Library API: solo para **subir** los medios a un álbum creado por la app (fase 5).

## Permisos (manifiesto)
```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED"/>  <!-- Android 14+ acceso parcial -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION"/>
```
Pedido en tiempo de ejecución:
```kotlin
val perms = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION)
    else -> arrayOf(READ_EXTERNAL_STORAGE)
}
```
Estado de acceso:
- `FULL`: `READ_MEDIA_IMAGES` concedido.
- `PARTIAL` (API 34+): solo `READ_MEDIA_VISUAL_USER_SELECTED` concedido. Mostrar un aviso: "Con acceso parcial no puedo encontrar automáticamente las fotos del viaje" y un botón para volver a pedir (el sistema muestra "Seleccionar más fotos" o "Permitir todo").
- `NONE`: la app funciona igual, sin fotos automáticas; queda la cámara integrada.

**No usar el Photo Picker del sistema para vincular automáticamente:** siempre elimina la ubicación EXIF, incluso con `ACCESS_MEDIA_LOCATION`.

## Consulta por rango de tiempo
```kotlin
suspend fun queryImages(fromUtcMs: Long, toUtcMs: Long): List<MediaCandidate> = withContext(io) {
    val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val proj = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.ORIENTATION, MediaStore.Images.Media.RELATIVE_PATH,
    )
    // DATE_TAKEN puede ser 0/null en algunos archivos → también por DATE_ADDED (segundos)
    val sel = "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
              "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)"
    val args = arrayOf("$fromUtcMs", "$toUtcMs", "${fromUtcMs / 1000}", "${toUtcMs / 1000}")
    resolver.query(uri, proj, sel, args, "${MediaStore.Images.Media.DATE_TAKEN} ASC")?.use { c -> /* mapear */ }
        ?: emptyList()
}
```
Análogo para `MediaStore.Video.Media` (+ `DURATION`). Filtrar por `RELATIVE_PATH` las carpetas excluidas (Screenshots, WhatsApp, Download; configurable).

## Observador en vivo
```kotlin
val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) { scope.launch { scanSince(lastScanUtcMs) } }
}
resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
```
- Registrarlo en el `TrackingService` (vive mientras dura la sesión). Aplicar *debounce* de 2 s: una ráfaga dispara muchos eventos.
- Las fotos recién guardadas pueden tener `IS_PENDING = 1`: reintentar.
- **Siempre** hacer el barrido de reconciliación al terminar la etapa.

## Leer EXIF con ubicación
```kotlin
fun readExif(contentUri: Uri): ExifData? {
    val original = if (Build.VERSION.SDK_INT >= 29 && hasMediaLocation()) MediaStore.setRequireOriginal(contentUri) else contentUri
    return try {
        resolver.openInputStream(original)?.use { ins ->
            val exif = ExifInterface(ins)
            val latLong = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }   // (0,0) = redactado
            ExifData(
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),  // "yyyy:MM:dd HH:mm:ss"
                offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), // "-03:00"
                subsec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                lat = latLong?.get(0), lon = latLong?.get(1),
                altitude = exif.getAltitude(Double.NaN).takeUnless { it.isNaN() },
                make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL),
            )
        }
    } catch (e: SecurityException) { null /* sin ACCESS_MEDIA_LOCATION: reintentar sin original y sin GPS */ }
}
```
Videos: `MediaMetadataRetriever.setDataSource(context, uri)`, `extractMetadata(METADATA_KEY_LOCATION)` → ISO-6709 (`+40.7128-074.0060/`); parsear con regex `([+-]\d+\.\d+)([+-]\d+\.\d+)`. `METADATA_KEY_DATE` → `yyyyMMdd'T'HHmmss.SSS'Z'` (UTC).

## Hora de la foto (prioridad)
1. `DateTimeOriginal` + `OffsetTimeOriginal` → `EXIF_WITH_OFFSET`
2. `DateTimeOriginal` sin offset → hora local, convertida con `tzId` de la etapa vigente → `EXIF_LOCAL`
3. `DATE_TAKEN` → `MEDIASTORE`
4. `DATE_MODIFIED * 1000` → `FILE_MTIME` (baja confianza)

Probar especialmente que la conversión de `EXIF_LOCAL` usa la zona de **la etapa**, no la del teléfono en el momento del escaneo.

## Algoritmo de vinculación
En `core:geo` (función pura `linkMedia(candidate, segments, pointsBySegment)`), con las reglas de `DISENO.md` §7.6. Resumen:

| Caso | `link_method` | Confianza |
|---|---|---|
| EXIF GPS + recorrido < 2 min, distancia < 500 m | `EXIF` | 1,0 |
| EXIF GPS + recorrido cercano pero distancia ≥ 500 m | `EXIF`, marcado para revisión | 0,5 |
| EXIF GPS sin recorrido cerca | `EXIF` | 0,9 |
| Sin GPS, interpolable (hueco < 5 min) | `TRACK_INTERPOLATED` | 0,8 |
| Sin GPS, punto más cercano < 10 min | `TRACK_NEAREST` | 0,7 × (1 - gap/10min) |
| Nada | `NONE` → revisión | 0 |

Pruebas unitarias para **cada fila**, más: foto justo en el borde de la etapa, foto de una excursión anidada, cambio de huso horario y cámara con desfase de +1 h.

## Huella, duplicados y nitidez (para el video)
- `sha256`: calcularlo de forma diferida (Worker), por streaming; solo para reencontrar el archivo.
- **pHash:** escalar a 32×32 en gris, DCT 2D, tomar los 8×8 de baja frecuencia (sin el DC), comparar con la mediana → 64 bits. Distancia de Hamming ≤ 6 = duplicado. Implementar en `core` sobre un array de luminancia (la decodificación del bitmap va en `:app`).
- **Nitidez:** varianza del laplaciano (kernel 3×3) sobre la luminancia de una miniatura de 512 px. Umbral relativo: descartar el 20 % menos nítido de cada ráfaga.
- Miniaturas: `ContentResolver.loadThumbnail(uri, Size(512, 512), null)` (API 29+).

## Cámara integrada (CameraX, fase 5)
- `ImageCapture` + `VideoCapture<Recorder>`; salida a MediaStore en `Pictures/BitacoraViaje/<trip>/` con `ContentValues` (`RELATIVE_PATH`).
- Antes de guardar, `ImageCapture.Metadata().apply { location = lastLocation }` (escribe el EXIF GPS).
- Registrar el `media_item` de inmediato con `source = APP_CAMERA`, `link_method = EXIF`, confianza 1,0.

## Google Fotos (fase 5)
- **Picker API:** flujo web. Crear una sesión (`sessions.create`), abrir `pickerUri` en el navegador o la app de Google Fotos, consultar el estado hasta `mediaItemsSet = true`, listar los ítems y descargar con `baseUrl` + parámetros (los `baseUrl` **expiran**; descargar enseguida). Requiere OAuth con el scope `photospicker.mediaitems.readonly` y un proyecto de Google Cloud. **Verificar** si los metadatos de ubicación llegan; si no, se vincula solo por hora.
- **Library API (subir):** `uploads` + `mediaItems.batchCreate` en un álbum creado por la app. Scopes `photoslibrary.appendonly` y relacionados. **Verificar** los scopes vigentes en https://developers.google.com/photos.

## Pantalla de revisión
- Lista de medios con `link_method = NONE` o confianza < 0,6, o marcados para revisión.
- Acciones: arrastrar al mapa para ubicar, "Usar la hora del recorrido", "Excluir", "Corregir el reloj de esta cámara" (skill `geo-algorithms`).
