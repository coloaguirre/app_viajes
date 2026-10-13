# Evidencia D1 — menú de tres puntos por viaje (2026-10-13)

Pedido del usuario (backlog deseable, `docs/notes/backlog-deseables.md`): que cada viaje de la
pantalla inicial tenga un menú con archivar, borrar y acciones afines.

## Qué hace
- Cada fila de "Mis viajes" tiene un botón ⋮ con **Renombrar**, **Archivar/Desarchivar**,
  **Exportar a la PC**, **Crear video** y **Borrar**.
- **Renombrar** abre un diálogo con el título entero seleccionado (escribir reemplaza).
- **Archivar** saca el viaje de la lista principal y lo lleva a una sección plegada
  "Archivados (N)" al final; desde ahí se desarchiva o se opera igual que cualquier viaje.
  Es una columna nueva `archived` en `trip` (migración 9→10, esquema `10.json`).
- **Exportar a la PC** abre el detalle del viaje con el diálogo de exportación ya abierto
  (`Route.TripDetail(openExport = true)`); **Crear video** va directo a la pantalla de video.
- **Borrar** pide confirmación explicando qué se borra (recorrido, paradas, notas, vínculos) y
  qué no (las fotos y videos de la galería nunca se tocan). La fila desaparece al instante y un
  snackbar ofrece **Deshacer** durante 10 s. Pasado ese tiempo: se reescribe el respaldo del
  viaje (`files/backup/<id>.json.gz`, el mismo que lista Ajustes → Respaldos), se borra el viaje
  en una transacción (las claves foráneas arrastran etapas, puntos, pausas, paradas, medios,
  notas y lugares; los `place` huérfanos se limpian), y se borran los audios y el plan de video
  guardado de ese viaje. Si el usuario sale de Home antes de los 10 s, el borrado se completa igual.
- Archivar y Borrar se deshabilitan solo para el viaje que tiene una etapa grabándose. Un viaje
  "en curso" sin etapa abierta (por ejemplo, entre un día y otro) sí puede borrarse o archivarse.
  El repositorio además rechaza el borrado si hay una etapa abierta (segunda red).

## Pruebas
- `MigrationTest`: 9→10 conserva los viajes y `archived` arranca en 0; versión 10 con 9 migraciones.
- `TrackingRepositoryTest`: renombrar recorta espacios y archivar no toca el estado; `deleteTrip`
  rechaza un viaje con etapa abierta y, cerrado, elimina viaje, etapa y puntos.
- `ScreensComposeTest`: dos tests del menú (ítems deshabilitados mientras se graba; renombrar,
  archivar, borrar con confirmación, desarchivar y la sección plegada).
- Gate: `./gradlew test` ✅ · `lint assembleDebug` ✅ · 16 KB ✅ · `ruff`/`pytest` del
  renderizador ✅.

## E2E en el emulador (API 36)
1. Menú del viaje con etapa grabándose: Archivar y Borrar en gris (`D1-menu.png` muestra el menú
   completo de un viaje sin etapa abierta).
2. Renombrar → "Patagonia" (texto previo reemplazado al escribir).
3. Archivar → aparece "Archivados (1)" plegado; al abrirlo, la fila dice "Archivado"
   (`D1-archivados.png`). Desarchivar → vuelve a la lista con "Terminado".
4. Exportar a la PC → detalle del viaje con el diálogo de exportación abierto. Crear video →
   pantalla de duración/estilo del video.
5. Borrar → confirmación → snackbar "Viaje «Patagonia» borrado · Deshacer"
   (`D1-borrar-deshacer.png`); Deshacer → el viaje vuelve y 12 s después sigue ahí, sin
   `trip deleted` en el log.
6. Borrar sin deshacer → a los 10 s el viaje desaparece, el log registra `trip deleted id=…` y
   queda `files/backup/<id>.json.gz` recién escrito.

## Arreglo colateral
Al abrir el detalle de un viaje con dos fotos del mismo milisegundo a más de 100 m la línea de
tiempo crasheaba (`Key "<segmento>-Moment-<t>" was already used`). Las claves de los eventos ahora
incluyen el id del medio/nota/parada (`TripDetailViewModel.eventKey`).
