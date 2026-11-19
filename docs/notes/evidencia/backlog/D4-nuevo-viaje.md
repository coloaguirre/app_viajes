# Evidencia D4 — "Nuevo viaje" con componentes (2026-11-19)

Pedido del usuario (backlog deseable, `docs/notes/backlog-deseables.md` §4): que en la pantalla
inicial haya una única opción "viaje" y que etapas, excursiones y trekkings sean componentes que
se le agregan, en vez de tres registros sueltos. Prioridad fijada por el usuario: usarlo en un
viaje real al día siguiente (2026-11-20), así que el camino rápido no podía empeorar.

## Qué hace
- **Mis viajes**: sin viaje en curso, el botón grande es **Nuevo viaje**; con uno en curso es
  **Seguir registrando**, que con un toque vuelve a la etapa abierta (reiniciando el servicio
  si murió) o, si no hay ninguna, arranca una etapa nueva del viaje activo con el medio y el
  modo de su última etapa. Sin formulario. Una tarjeta "Viaje en curso: «X»" lleva un **Nuevo
  viaje** secundario para cambiar de viaje.
- **Nuevo viaje** (formulario): nombre del viaje, intención, medio, modo y la tarjeta de permisos
  de fotos; sin tarjetas de tipo (la primera etapa es implícita). Si otro viaje está en curso avisa
  "El viaje en curso «X» se cierra (y su etapa, si está grabando)".
- **Agregar al viaje** (botón + en el detalle del viaje): tarjetas **Etapa / Excursión o visita /
  Trekking**, nombre de la etapa, medio, modo. Una excursión o trekking se anida en la etapa en
  curso (que queda en pausa); una etapa cierra la anterior. Si el viaje estaba terminado o
  archivado, se reabre; si otro viaje estaba en curso, ese se cierra primero (solo uno graba).
- **Acceso directo, mosaico y widget**: la misma entrada de un toque (`Route.NewSession()` pelada):
  etapa abierta → la sesión; viaje activo → etapa nueva en el acto; nada → formulario "Nuevo
  viaje". El mosaico y el widget dicen **Seguir registrando** cuando hay un viaje en curso; el
  acceso directo estático pasa a llamarse **Registrar**.
- El modelo no cambia (viaje + segmentos con `parent_segment_id`): sin migración.

## Cómo
- `TrackingRepository.startSession(..., tripId, newTrip)`: `newTrip` siempre crea un viaje;
  `tripId` apunta al viaje elegido y lo reabre (`status = ACTIVE`, `ended_utc_ms = null`,
  `archived = 0`); si el viaje activo es otro, `finishTripInternal` lo cierra con sus etapas
  abiertas dentro de la misma transacción. El anidado y el cierre de etapas quedan como estaban.
- `TrackingController.continueRecording()`: etapa abierta → su id; si no, `startSession(STAGE,
  medio y modo de la **última etapa de primer nivel** (`lastStageOf`, nunca de una excursión
  anidada: una caminata al final de un día en auto no puede volver "a pie" la etapa del día
  siguiente, porque el filtro a pie descartaría todos los puntos en movimiento), tripId = activo)`.
  Al cambiar de viaje también se vacía el buffer del servicio antes de la transacción. Si la etapa
  abierta quedó sin servicio (proceso muerto), la entrada de un toque manda a Home, cuyo diálogo
  ofrece Reanudar / Cerrar etapa (DISENO §6.9), en vez de reanudar a ciegas.
- Todo lo que `startSession` cierra de paso (la etapa abierta y, al cambiar de viaje, el viaje
  anterior) recibe los mismos trabajos que un cierre explícito: vinculación de fotos, respaldo,
  nombre de ruta, lugares y clima (`ImplicitlyClosed` devuelto por el repositorio).
- Home: "Seguir registrando" tiene guarda contra el doble toque; si falla con un viaje en curso abre
  "Agregar al viaje" para ese viaje (nunca el formulario que lo cierra). La tarjeta "Registro en
  curso" ofrece **Excursión o trekking** con un toque (abre "Agregar al viaje" del viaje activo).
- La entrada de un toque nunca crashea: cualquier error cae al formulario con un aviso.
- `NewSessionViewModel.resolveMode()`: la ruta explícita fija el modo; la pelada intenta
  `continueRecording()` solo si hay permiso de ubicación y viaje activo; si no, abre el formulario
  que corresponda. Mientras decide, la pantalla muestra un spinner (nunca un formulario a medio armar).
- Home deriva `activeTrip` de la lista visible (un viaje con borrado pendiente no cuenta).

## Pruebas
- `TrackingRepositoryTest`: un viaje nuevo cierra el activo y su etapa; agregar a un viaje
  terminado y archivado lo reabre, lo desarchiva y cierra el otro viaje activo; una excursión
  agregada al viaje activo sigue anidando bajo la etapa abierta.
- `ScreensComposeTest`: formulario "Agregar al viaje" (destino nombrado, tarjetas, Iniciar
  habilitado tras elegir); formulario "Nuevo viaje" (sin tarjetas, nombre del viaje, aviso de
  cierre, "Empezar el viaje"); spinner mientras resuelve; Home con viaje en curso (Seguir
  registrando + Nuevo viaje secundario) y sin viaje (Nuevo viaje).
- `DetailScreensComposeTest`: el botón "Agregar al viaje". `QuickActionsTest` y
  `BitacoraWidgetTest`: etiquetas con viaje activo.
- Gate: `./gradlew test` ✅ · `lint assembleDebug` ✅ · 16 KB ✅ · `ruff`/`pytest` ✅.

## E2E en el emulador (API 36, con el viaje "Patagonia" en curso y sin etapa abierta)
1. Home: tarjeta "Viaje en curso: Patagonia" con **Nuevo viaje** y botón grande **Seguir
   registrando** (`D4-home.png`).
2. Un toque en **Seguir registrando** → pantalla de sesión con GPS activo (etapa nueva, sin
   formulario). Atrás → Home muestra "Registro en curso"; otro toque vuelve a la misma sesión.
3. Terminar → "Terminar etapa" → Home vuelve a mostrar la tarjeta y **Seguir registrando**.
4. Detalle del viaje → **+** → "Agregar al viaje" con "Se agrega al viaje «Patagonia»" y las tres
   tarjetas (`D4-agregar.png`).
5. **Nuevo viaje** desde la tarjeta → formulario con el aviso de cierre y el nombre del viaje
   (`D4-nuevo-viaje.png`); "Prueba D4" → **Empezar el viaje** → sesión activa; Home lista
   "Prueba D4 · Viaje en curso" y "Patagonia · Terminado". Sin crashes en el log.
6. Con la versión corregida tras la revisión: reinstalar con una etapa abierta mostró el diálogo de
   recuperación de Home (Reanudar / Cerrar etapa), como debe; la tarjeta "Registro en curso" ofrece
   **Excursión o trekking** y **Volver a la sesión** (`D4-registro-en-curso.png`); "Seguir
   registrando" vuelve a la misma sesión; la excursión desde la tarjeta abre "Agregar al viaje" ya
   dirigido a «Prueba D4» y arranca anidada (la etapa queda en pausa y la tarjeta deja de ofrecer
   excursión mientras hay una abierta); un doble toque en "Seguir registrando" no crea segmentos
   de más (base: una etapa en pausa y una excursión activa). Sin crashes.

## Revisión de código
Primera pasada (2026-11-19, informe en el PR): 1 bloqueante (el medio heredado de una excursión
anidada), 8 importantes y 8 menores. Corregidos en el segundo commit: medio y modo de la última
etapa de primer nivel (test); trabajos post-cierre también para cierres implícitos (test de que la
etapa cerrada conserva puntos y estadísticas); catch en la entrada de un toque y en Iniciar; fallback
a "Agregar al viaje" en vez de "Nuevo viaje"; guarda de doble toque; excursión desde la tarjeta de
registro en curso; la entrada de un toque con etapa interrumpida va a Home; `_events` antes del
`init`; viaje destino borrado → vuelve; manual dice que también desarchiva; recursos sin uso
eliminados; tests nuevos de Compose (banner con excursión; agregar a otro viaje con excursión
anidada abierta). Queda registrado y aceptado: el mosaico y el widget arrancan una etapa sin
confirmación (es el diseño pedido: un toque), y no hay tests de ViewModel ni de controller (la
lógica de decisión se probó en el repositorio y en el emulador).

## No incluido / límites
- El cierre automático de un viaje activo al empezar otro no tiene confirmación propia: el aviso
  está en el formulario. Un viaje cerrado así se reabre desde **Agregar al viaje**.
- El acceso directo estático no puede cambiar de nombre según el estado; dice "Registrar".
