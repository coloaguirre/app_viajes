# Deseables para después de las pruebas de campo (backlog evaluado, 2026-10-13)

Pedido del usuario al cerrar la Fase 5: una lista de mejoras para encarar cuando terminen las
pruebas en la calle. Acá va cada una con viabilidad, complejidad estimada (días de agente, misma
escala que el PLAN), impacto en el esquema y el orden sugerido. Nada de esto está implementado.

## 1. Clima en el registro  — viable · complejidad media (2,5 d) · esquema v10 (al implementarlo quedó en v12: `weather_sample` + marcador `weather_fetch`)

**Qué pidió**: capturar el clima de mañana, tarde y noche y sumarlo al registro, u otro esquema
horario si hay algo mejor.

**Propuesta**: en vez de tres capturas fijas, guardar **muestras horarias a lo largo del recorrido**
y derivar de ahí los bloques del día. La app ya sabe dónde estuvo el usuario a cada hora, así que:
- Al cerrar la etapa (o en el barrido de enriquecimiento, con red), para cada hora del tramo se
  toma el punto más cercano al centro de esa hora y se consulta **Open-Meteo** (gratuito, sin
  clave, licencia CC BY 4.0; el endpoint de pronóstico admite `past_days` hasta 92, y el archivo
  histórico ERA5 cubre lo anterior con unos días de retraso). Una sola llamada por etapa y día con
  todas las horas, coordenadas redondeadas a 2 decimales como ya se hace con los lugares.
- Se guardan temperatura, sensación térmica, precipitación, viento, nubosidad y el código de
  tiempo (sol, nublado, lluvia…) en una tabla `weather_sample(segment_id, time_utc_ms, lat, lon,
  …, source)`; migración de Room a v10 con su test. (Hecho 2026-10-14: v11 + v12; la caché HTTP de
  `OsmHttp` no guarda respuestas de Open-Meteo porque el servicio no envía cabeceras de caché.)
- De las muestras salen los **bloques mañana (6–12) / tarde (12–20) / noche (20–6)** para la
  bitácora y la tarjeta de estadísticas, y además el clima de cada foto y de cada parada (la hora ya
  está). El HTML/Markdown, el CSV y `trip.json` lo exportan; el renderizador de escritorio lo lee
  del paquete; el video lo muestra en las tarjetas de capítulo ("Soleado, 23 °C").
- Offline primero: sin red queda en cola y se completa después, como los lugares. Atribución
  obligatoria "Datos meteorológicos: Open-Meteo (CC BY 4.0)" en LEEME y créditos.

**Por qué es mejor que tres capturas**: un viaje cruza lugares (el clima de la mañana en la ciudad
y el de la tarde en la montaña son distintos) y las etapas no empiezan a las 6; muestrear por hora
y resumir por bloque da ambas cosas y permite el clima por foto sin costo extra.

**Riesgos**: cobertura del archivo en zonas remotas (ERA5 es malla de ~9 km: suficiente para
"llovió / hizo frío", no para microclimas); una fuente externa más que registrar en la política
de privacidad si algún día se publica (D4).

## 2. Menú de tres puntos por viaje en la pantalla inicial  — viable · complejidad baja (1 d) · esquema v10

**Acciones sugeridas**: Renombrar, Archivar / Desarchivar, Exportar (atajo al diálogo existente),
Crear video, Borrar.
- **Archivar**: campo `trip.archived` (o estado `ARCHIVED`); los archivados salen de la lista
  principal y quedan en una sección plegada "Archivados" al pie. Mismo índice v10 que el clima si
  entran juntos.
- **Borrar**: por la regla "nunca perder datos", borrar solo después de confirmar y **con el
  respaldo del viaje recién escrito** (ya existe `BackupWorker`); borra filas de la base y los
  audios de notas (propios de la app), **nunca** fotos ni videos de la galería; snackbar con
  "Deshacer" durante unos segundos antes de ejecutar. Las regiones de mapa sin red del viaje se
  ofrecen para quitar (ya guardan el `trip_id`).
- **Renombrar**: hoy el título se puede fijar al crear; falta la edición posterior (dos líneas de
  UI + `TrackingRepository`).

**Riesgos**: casi ninguno; cuidar que el viaje activo no se pueda archivar ni borrar sin cerrar la
etapa en curso.

## 3. Mapa interactivo: tocar puntos y ver foto, nota, km y hora  — viable · complejidad media (2 d) · sin esquema

**Propuesta**:
- Tocar un marcador (foto, video, nota de voz, texto, parada, lugar) abre una **hoja inferior** con
  la miniatura o el texto, la hora local, el kilómetro del recorrido en que ocurrió y la velocidad
  en ese tramo; "Abrir en la línea de tiempo" y, para fotos, la galería del sistema.
- Tocar el **recorrido** en cualquier punto muestra el punto más cercano con km acumulados, hora de
  paso y altitud; el mapa deja un marcador temporal.
- Técnica: MapLibre ya expone `queryRenderedFeatures` sobre las capas de la app (los marcadores
  llevan su `id` en las propiedades GeoJSON) y el `onMapClick` existe; los **km acumulados por
  punto** son una función pura nueva en `core/geo` (sumas prefijas de haversine por etapa, con
  reinicio en pausas, como ya hace `distanceM`), con sus tests; el punto más cercano a un toque es
  búsqueda en una polilínea proyectada (ya hay un helper parecido para la revisión de fotos).
- Reutiliza la ficha de la línea de tiempo para no duplicar textos.

**Riesgos**: rendimiento con recorridos largos (decenas de miles de puntos) al buscar el punto más
cercano: hacerlo sobre la polilínea simplificada a 5 m que ya se calcula para el mapa.

## 4. "Nuevo registro" como un viaje al que se le agregan componentes  — viable · complejidad media (2 d) · sin esquema

**Diagnóstico**: el modelo ya es así (un `trip` con `segments` de tipo etapa / excursión / trekking,
y las excursiones cuelgan de una etapa por `parent_segment_id`). Lo que confunde es la pantalla:
"¿Qué estás haciendo? Nueva etapa / Excursión / Trekking" presenta los componentes como si fueran
registros distintos, y además el viaje continuo (adenda 1) crea etapas solas a medianoche.

**Propuesta de pantallas**:
- Home: **"Nuevo viaje"** (nombre, fecha, intención) y, si hay un viaje abierto, un botón grande
  **"Seguir registrando"** que crea o reanuda la etapa del día con un toque (el camino rápido en la
  calle no puede empeorar).
- Dentro del viaje: **"Agregar al viaje"** → Etapa (día o tramo), Excursión o visita, Trekking,
  con el medio de transporte y el modo; una excursión o trekking iniciados con una etapa en curso la
  pausan y la retoman al terminar, como hoy.
- El widget, el mosaico y el acceso directo "Nuevo registro" pasan a "Seguir registrando" cuando hay
  un viaje abierto.

**Riesgos**: es el cambio con más superficie de UI (Home, nueva sesión, sesión activa, cierre de
etapa, accesos rápidos) y toca los tests de Compose de todas esas pantallas; conviene hacerlo
después de las pruebas de campo de las Fases 1–3 para no cambiar lo que se está midiendo.

## Orden sugerido
1. Menú de tres puntos (1 d): barato y destraba el uso diario (archivar lo viejo).
2. Mapa interactivo (2 d): mejora directa de la bitácora sin tocar el modelo.
3. Clima (2,5 d): aporta a bitácora, exportación y video; única con migración y fuente externa nueva.
4. Reestructura de "Nuevo registro" (2 d): la de mayor impacto en pantallas; mejor con las
   pruebas de campo cerradas y con los dos ítems de Fase 6 decididos (D4).

Total estimado: **7,5 días de agente**, sin contar pruebas en la calle. Ninguna de las cuatro
depende de internet durante el registro; las cuatro respetan "nunca perder datos" y "los medios del
usuario no se modifican".
