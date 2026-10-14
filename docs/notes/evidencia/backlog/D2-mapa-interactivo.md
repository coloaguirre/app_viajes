# Evidencia D2 — mapa interactivo (2026-10-13/14)

Pedido del usuario (backlog deseable, `docs/notes/backlog-deseables.md`): que en el mapa del viaje se
puedan tocar los puntos y el recorrido para ver qué pasó ahí (foto, nota, km acumulados, hora).

## Qué hace
- En la pestaña **Mapa** del detalle del viaje, tocar un **marcador** (foto, video, nota de voz,
  nota de texto, destacado, lugar) abre una tarjeta arriba del mapa con el evento (título, y para
  fotos una fila de miniaturas; para lugares el nombre) y una línea de datos: **etapa (fecha y hora
  de inicio · nombre, solo si el viaje tiene más de una) · hora local de la etapa · metros/km
  recorridos de la etapa hasta ese instante · velocidad · altitud**. El mapa se acerca al marcador
  y la línea de tiempo lo resalta (misma clave de evento que ya usaba el foco).
- Tocar la **línea del recorrido** (lejos de un marcador) abre la misma tarjeta para el punto de la
  línea más cercano al dedo ("Punto del recorrido"), con hora, progreso, velocidad y altitud
  interpolados sobre ese tramo.
- Tocar el mapa en cualquier otro lado, o la ✕, cierra la tarjeta. Tocar un evento en la línea de
  tiempo sigue llevando al mapa y ahora también abre su tarjeta.
- La tarjeta va arriba para no tapar la atribución de MapLibre / MapTiler / OpenStreetMap (abajo a
  la izquierda); el botón de mapa sin red sigue abajo a la derecha.

## Cómo (decisiones)
- **Una sola regla de distancia.** `cumulativeDistanceM` (`core/geo/Metrics.kt`) devuelve la
  distancia acumulada en cada punto con las mismas reglas que las estadísticas de la etapa (tramos
  con tiempo no creciente o en pausa no suman; ruido en reposo no suma) y `computeSegmentStats`
  ahora la consume: la cifra de la tarjeta en el último punto es la del encabezado de la etapa. El
  arreglo se calcula una vez por etapa fuera del hilo principal y viaja en `SegmentTimeline`.
- `TrackLocator` (`core/geo`, puro): `atTime` interpola entre los dos fijos vecinos (búsqueda
  binaria, O(log n)); `nearest` proyecta el toque sobre el tramo más cercano de la polilínea (marco
  equirrectangular local) y devuelve hora, altitud y progreso interpolados sobre ese tramo y la
  distancia del dedo a la línea; `speedAround` promedia ±30 s saltando tramos en pausa.
- **Hit-test sin `queryRenderedFeatures`.** La primera versión preguntaba a MapLibre qué había bajo
  el dedo; esa llamada espera al hilo de render y en el emulador (render por software, el hilo de
  render se trabó escribiendo al pipe de Vulkan) dejó la app en ANR con el hilo principal dentro de
  `AndroidRendererFrontend::queryRenderedFeatures`. Ahora el renderizador proyecta su propia lista de
  marcadores a pantalla (`projection.toScreenLocation`, sin pasar por el render) y el más cercano
  dentro de 8 dp de radio + 6 dp de tolerancia gana; si no hay marcador, entrega un `MapTap.Spot`
  con la tolerancia en metros que cubren 9 dp + media línea al zoom actual
  (`getMetersPerPixelAtLatitude`), y el ViewModel decide en `Dispatchers.Default` si el punto más
  cercano de la línea está dentro (tarjeta) o no (cierra). Tolerancias en dp, no en px.
- La selección vive como claves (`MapSelectionRef`: etapa, clave de evento, instante) y se resuelve
  contra las etapas vigentes en cada emisión: si el evento desaparece, la tarjeta también.
- `MapRenderer` sigue siendo intercambiable: `onTap` es opcional y el `onMapClick` que usa la
  revisión de fotos no cambia. Las fotos y videos siguen siendo solo lectura.

## No incluido (propuesta original del backlog)
Marcador temporal en el punto tocado, botón "Abrir en la línea de tiempo" y abrir la galería desde
la tarjeta. Quedan para cuando el usuario lo pida. La tarjeta tampoco distingue qué foto del
momento se tocó: muestra hasta cuatro miniaturas del momento.

## Pruebas
- `TrackLocatorTest` (`core/geo`): lista vacía; distancia acumulada por tramo, con pausa, igual a
  `computeSegmentStats` (incluido un episodio de reposo), timestamps duplicados e instante dentro de
  una pausa; `nearest` sobre la línea (pie de la perpendicular a mitad de tramo, desplazamiento de
  9,1 m y de 20 m, más allá del final, un solo punto); `atTime` interpola y acota; altitud
  interpolada solo con ambos vecinos; velocidad en ventana, saltando pausas, y caída al fijo aislado;
  arreglo precomputado equivalente.
- `DetailScreensComposeTest`: tarjeta de un marcador de foto (hora, progreso, velocidad, altitud,
  cierre); tarjeta de un punto del recorrido sin datos desconocidos; etiqueta de etapa con varias
  etapas y reenvío del slot `map` a `onMapTap`.
- Gate: `./gradlew test` ✅ · `lint assembleDebug` ✅ · 16 KB ✅ · `ruff`/`pytest` ✅.

## E2E en el emulador (API 36, viaje importado desde un respaldo: 4 etapas, 23 medios, 2 notas de voz)
1. Marcador de nota de voz → "🎙 Mirador del cerro, viento fuerte · lun 12 oct 20:31 · Nueva etapa ·
   20:37 · 9 m de la etapa · 0,0 km/h · 25 m de altitud" y el mapa se acerca
   (`D2-marcador-voz.png`). La nota pertenece a la segunda etapa del día (zona horaria UTC en esa
   etapa, por eso 20:37); las fotos de abajo son de la primera.
2. Marcador de fotos → "📷 4 fotos y videos · lun 12 oct 16:51 · Nueva etapa · 16:53 · 79 m de la
   etapa · 3,8 km/h · 25 m de altitud" con las miniaturas (grises en el emulador: los archivos del
   respaldo no existen ahí) (`D2-marcador-fotos.png`). Otro marcador de la misma etapa: "17:06 ·
   323 m". ✕ cierra la tarjeta.
3. Línea del recorrido entre dos marcadores → "📍 Punto del recorrido · lun 12 oct 16:51 · Nueva
   etapa · 16:58 · 141 m de la etapa · 3,9 km/h · 25 m de altitud" (`D2-punto-recorrido.png`);
   otro toque a 23,6 m de la línea con tolerancia de 59,4 m (zoom 15) → "17:00 · 249 m".
   Las cifras de una misma etapa crecen con la hora (79 m a las 16:53, 141 m a las 16:58, 249 m a
   las 17:00, 323 m a las 17:06).
4. Toque en el mapa vacío (349 m de la línea) → la tarjeta desaparece (log `map spot … offsetM=349.2
   toleranceM=59.4`). Toda la secuencia sin ANR.
