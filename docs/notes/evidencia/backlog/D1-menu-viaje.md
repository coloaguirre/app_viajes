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
  snackbar ofrece **Deshacer** mientras está visible (10 s). Cuando el snackbar se va sin tocar
  Deshacer, la pantalla encola un trabajo único de WorkManager (`DeleteTripWorker` →
  `TripDeleter`) que: (1) rechaza si una etapa del viaje está grabando; (2) **escribe el respaldo**
  `files/backup/<id>.json.gz` (el mismo que lista Ajustes → Respaldos) y, **si falla o queda vacío,
  no borra nada**: la fila vuelve con el aviso "No se borró «X»: no se pudo guardar el respaldo";
  (3) mueve las grabaciones de voz a `files/backup/audio/<id>/` (se conservan junto al respaldo,
  excluidas del respaldo en la nube por el tope de 25 MB); (4) borra el viaje en una transacción
  (las claves foráneas arrastran etapas, puntos, pausas, paradas, medios, notas y lugares; los
  `place` huérfanos se limpian); (5) borra el plan de video guardado y la carpeta de staging de
  exportación. Varios viajes pueden estar pendientes a la vez, cada uno con su snackbar. Si el
  usuario sale de Home con un snackbar abierto, el borrado se encola igual (lo pidió y confirmó);
  si el proceso muere antes, el viaje simplemente sigue ahí.
- Borrar se deshabilita solo para el viaje que tiene una etapa grabándose; un viaje "en curso"
  sin etapa abierta (entre un día y otro) sí puede borrarse. Archivar se deshabilita para todo
  viaje en curso (es adonde va la próxima grabación); el repositorio rechaza ambas cosas como
  segunda red, y `getActive()` ignora los archivados.
- Limitaciones: `archived` no viaja en el `trip.json` (restaurar un respaldo desarchiva); las
  regiones de mapa sin red descargadas para el viaje no se borran (se administran desde Ajustes).

## Pruebas
- `MigrationTest`: 9→10 conserva los viajes y `archived` arranca en 0; versión 10 con 9 migraciones.
- `TrackingRepositoryTest`: renombrar recorta espacios y rechaza el título vacío; archivar no toca
  el estado y se rechaza en un viaje en curso; `deleteTrip` rechaza un viaje con etapa abierta y,
  cerrado, elimina viaje, etapa y puntos.
- `TripDeleterTest` (Robolectric, Room en memoria, respaldo real): etapa grabando → `IN_USE` sin
  tocar nada; respaldo nulo / excepción / archivo vacío → `BACKUP_FAILED`, el viaje y sus audios
  siguen; respaldo escrito → filas borradas, `.json.gz` presente y los `.m4a` movidos a
  `backup/audio/<id>/`; segunda corrida → `NOT_FOUND`; grabación que arranca durante el respaldo →
  `IN_USE` con los audios en su lugar; `restoreKeptAudio` recupera los `.m4a` al importar.
- `BackupRulesTest`: `backup/audio/` excluido del respaldo en la nube.
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
   `trip deleted` en el log ni trabajo encolado.
6. Borrar sin deshacer → al cerrarse el snackbar corre el worker: el viaje desaparece, el log
   registra `trip deleted id=… backup=<id>.json.gz`, queda `files/backup/<id>.json.gz` recién
   escrito y los dos `.m4a` del viaje están en `files/backup/audio/<id>/` (ya no en `files/audio/`).

## Revisión de código
Dos pasadas del code-reviewer (2026-10-13), informes completos en el PR.
- Primera: 1 bloqueante (si fallaba el respaldo se borraba igual), 6 importantes (reloj del
  Deshacer distinto al del snackbar, slot único de pendientes, audios borrados sin respaldo,
  `runBlocking` en `onCleared`, archivar un viaje en curso, sin test del borrado) y 8 menores.
- Segunda (sobre el arreglo): bloqueante y 4 importantes resueltos; 3 hallazgos nuevos
  importantes, corregidos en el tercer commit: el commit del borrado se dispara también si el
  usuario navega mientras el snackbar está abierto (`finally`); los audios se mueven recién
  después de la transacción que borra (una grabación que arranca durante el respaldo rechaza el
  borrado y no toca nada; test con la etapa abierta dentro del respaldo); sin `pruneWork()` global
  (borraba los resultados de exportación/video de otras pantallas). Además: importar el respaldo
  de un viaje borrado recupera sus grabaciones desde `backup/audio/<id>/` (test).
- Quedan declarados y aceptados: `archived` no viaja en el `trip.json`; las regiones de mapa sin
  red no se borran con el viaje; la papelera de audios no tiene tope ni pantalla propia; si el
  proceso muere antes de encolar el trabajo, el viaje sigue ahí (nunca se pierde); no hay test
  unitario del `HomeViewModel` (el borrado en sí está cubierto por `TripDeleterTest`).
