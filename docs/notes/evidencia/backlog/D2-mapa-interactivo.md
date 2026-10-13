# Evidencia D2 — mapa interactivo (2026-10-13)

Pedido del usuario (backlog deseable, `docs/notes/backlog-deseables.md`): que en el mapa del viaje se
puedan tocar los puntos y el recorrido para ver qué pasó ahí (foto, nota, km acumulados, hora).

## Qué hace
- En la pestaña **Mapa** del detalle del viaje, tocar un **marcador** (foto, video, nota de voz,
  nota de texto, destacado, lugar) abre una tarjeta al pie del mapa con el evento (título, y para
  fotos una fila de miniaturas; para lugares el nombre) y una línea de datos: **hora local de la
  etapa · metros/km recorridos de la etapa hasta ese instante · velocidad · altitud**. El mapa se
  acerca al marcador y la línea de tiempo lo resalta (misma clave de evento que ya usaba el foco).
- Tocar la **línea del recorrido** (lejos de un marcador) abre la misma tarjeta para el punto
  registrado más cercano ("Punto del recorrido"), con su hora, progreso, velocidad y altitud.
- Tocar el mapa en cualquier otro lado, o la ✕, cierra la tarjeta. Tocar un evento en la línea de
  tiempo sigue llevando al mapa y ahora también abre su tarjeta.
- Los datos salen de `TrackLocator` (`core/geo`, Kotlin puro): punto más cercano por haversine,
  posición en un instante por interpolación lineal entre los dos fijos vecinos (acotada al primero
  y al último), distancia acumulada sumando tramos y **saltando los tramos que cruzan una pausa**
  (misma regla que las estadísticas de la etapa, sin el filtro de ruido en reposo: puede diferir
  unos metros del total), y velocidad promediada en ±30 s alrededor del punto (suaviza el ruido
  del GPS; si no hay ventana, la velocidad del propio fijo).
- Hit-test en MapLibre (`MapLibreRenderer.hitTest`): `queryRenderedFeatures` sobre la capa de
  marcadores con una caja de ±14 px (los círculos ya miden 8 dp) y, si no hay marcador, sobre la
  capa de la línea con ±24 px. El renderizador expone `onTap: (MapTap) -> Unit`
  (`Marker(id)` / `Track(punto)` / `Empty(punto)`) además del `onMapClick` que usa la revisión
  de fotos; la abstracción `MapRenderer` sigue siendo intercambiable.
- Las fotos y videos siguen siendo solo lectura; la tarjeta usa el `contentUri` como la grilla.

## Pruebas
- `TrackLocatorTest` (`core/geo`): lista vacía; distancia acumulada por tramo y con pausa; punto
  más cercano con índice, progreso, desplazamiento (~9,1 m para 0,0001° de longitud a 34,6° S) y
  velocidad; `atTime` interpola a mitad de camino y acota fuera del registro; altitud interpolada
  solo con ambos vecinos; velocidad en ventana y caída al fijo aislado.
- `DetailScreensComposeTest`: la tarjeta de un marcador de foto muestra hora, progreso, velocidad
  y altitud y reporta el cierre; la de un punto del recorrido no tiene evento y omite los datos
  desconocidos.
- Gate: `./gradlew test` ✅ · `lint assembleDebug` ✅ · 16 KB ✅ · `ruff`/`pytest` ✅.

## E2E en el emulador (API 36, viaje importado desde un respaldo con 6 medios y 2 notas de voz)
1. Marcador de nota de voz (violeta) → tarjeta "🎙 Mirador del cerro, viento fuerte · 20:37 ·
   9 m de la etapa · 0.0 km/h · 25 m de altitud" y el mapa se acerca (`D2-marcador-voz.png`).
2. Marcador de fotos → "📷 4 fotos y videos · 16:53 · 84 m de la etapa · 3.8 km/h · 25 m de
   altitud" con las miniaturas (grises en el emulador: los archivos del respaldo no existen ahí)
   (`D2-marcador-fotos.png`). ✕ cierra la tarjeta.
3. Línea del recorrido entre dos marcadores → "📍 Punto del recorrido · 16:53 · 125 m de la
   etapa · 3.9 km/h · 25 m de altitud" (`D2-punto-recorrido.png`). Con la caja original de
   ±28 px los marcadores vecinos ganaban siempre; por eso la caja de marcadores bajó a ±14 px.
4. Toque en el mapa vacío → la tarjeta desaparece (log `map tap … marker=null track=false`).
