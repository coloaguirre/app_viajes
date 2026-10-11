# Gap analysis — kickoff Bitácora de Viaje

Fecha: 2026-10-11. Fuentes: `docs/incoming/DISENO.md`,
`docs/incoming/pedido-original.md`, `docs/incoming/README-paquete-arranque.md`.
Checklists: `common.md` @ 869667b78e5f; `agent-system.md` @ e98697a05a25
(recorrido R1, ver abajo). Tipo de proyecto: **otro** (app móvil Android
de uso personal + componente Python de escritorio). No hay checklist de
tipo cercano en el cerebro: es el primer proyecto de software de producto
no-datos; los ítems [EXTRA] de abajo son candidatos para un checklist
`mobile-app.md` vía retro.

Marcas: RESPONDIDO / INFERIBLE (default propuesto) / GAP.

## Pasada a — checklist common.md

### Negocio
- Decisión de negocio y quién la toma — **NO APLICA** (proyecto personal;
  el usuario es el único interesado y decide todo).
- Métrica de éxito y umbral — **INFERIBLE**. La spec no define una métrica
  única; define objetivos O1-O7 (§1.3) y criterios de aceptación por fase
  (§19), más umbrales técnicos: distancia ±2 % vs GPX de referencia,
  batería ≤ 5-8 %/h en perfil WALK, video de 1 min en < 5 min en gama
  media. Default: éxito del proyecto = un viaje real registrado de punta
  a punta que produce el ZIP exportado y el video resumen. Se pregunta si
  hay un viaje con fecha que fije el hito.
- KPI a reproducir — **NO APLICA**.
- Deadline — **GAP**: sin fecha de fin (el usuario pidió que se proponga
  a partir de las estimaciones). Pregunta: ¿hay un viaje planificado?
- Fuera de alcance — **RESPONDIDO** (§1.4): red social, sincronización
  multi-dispositivo, navegación turn-by-turn, iOS. Futuras: map matching
  (§6.8), TTS (§13.7), subida a Drive por API (§12.4).

### Datos y accesos
- Fuentes identificadas — **RESPONDIDO**: GPS del dispositivo, MediaStore,
  micrófono, APIs públicas (Nominatim, Wikipedia, Wikidata, Overpass),
  proveedor de teselas. Accesos a conseguir: clave de mapa (D2), clave LLM
  (opcional, D-LLM), proyecto Google Cloud con OAuth (opcional, fase 5).
- Diccionario de datos — **RESPONDIDO**: modelo de datos completo §5.2.
- Confidencialidad / datos personales — **RESPONDIDO** (§15.3): todo local,
  sin servidor ni cuenta; zonas de privacidad; opción de redondear
  coordenadas a ~1 km en el enriquecimiento. Son datos del propio usuario.
- Datos que salen del dispositivo — **RESPONDIDO**: coordenadas al
  enriquecer; audio solo si se activa transcripción en la nube.
- Hora de completitud del warehouse — **NO APLICA**.
- Proceso en planilla del cliente — **NO APLICA**.
- Definición canónica de métricas — **INFERIBLE**: distancia, tiempo en
  movimiento, desnivel (histéresis 3 m) y velocidad máxima (p98) están
  definidas en §6.5. Default: `core/geo` es la fuente única, con tests;
  exportación y video consumen esos valores, nunca los recalculan.

### Entrega y operación
- **[BLOQUEANTE] Nivel de calidad** — **GAP**, se pregunta. Análisis por
  consecuencia: un recorrido mal capturado o perdido es irreversible (no
  se puede volver a caminar el sendero); exportación y video son
  regenerables. Recomendación: **Nivel 2**, con significado adaptado a una
  app: tests JVM obligatorios en `core/*` y migraciones de Room probadas
  (regla "nunca perder datos"); "contratos de datos" = validación de la
  exportación contra JSON Schema y XSD de GPX en cada corrida de tests;
  "auto-chequeo del entregable" = conciliación de conteos al exportar
  (puntos, medios, notas en la base = en el ZIP, con faltantes reportados).
  Nivel 1 contradice la regla de nunca perder datos; Nivel 3 no aplica
  (nada actúa sobre un negocio).
- Formato del entregable — **RESPONDIDO**: APK instalado por sideload,
  CLI Python `bitacora-render`, paquete ZIP de exportación.
- Comportamiento LLM no determinista — **RESPONDIDO**: una llamada LLM
  opcional (§11.4) que reescribe un extracto de Wikipedia restringida al
  texto fuente, con aprobación del usuario antes de usarse. No hay agente
  ni decisión dinámica → checklist agent-system recorrido **R1**: "no
  necesita agentes", registrado; el resto del checklist no se carga.
- Dónde corre y quién mantiene — **RESPONDIDO**: teléfono y PC del
  usuario; mantiene el usuario con Claude Code.
- Frecuencia de ejecución / reentrenamiento — **NO APLICA**.
- Interlocutor técnico — **RESPONDIDO**: el usuario.

### Proyecto
- Stack — **RESPONDIDO** (§3) y volcado al CLAUDE.md del proyecto.
- Frontend — **RESPONDIDO**: Jetpack Compose; convenciones en CLAUDE.md.
- Definition of done por fase — **RESPONDIDO** (§19). Observación: varios
  criterios exigen prueba manual del usuario (importar KML en My Maps,
  batería en teléfono real, render en gama media). Quedan como ítems con
  aceptación "evidencia aportada por el usuario", explícitamente.

## Pasada b — consideraciones [EXTRA] de este proyecto

- [EXTRA-1] **Entorno WSL2 con 4 CPUs y 7 GB de RAM.** KVM está presente
  (emulador viable), hay 946 GB de disco, Python 3.12 y uv. Faltan JDK,
  Android SDK y ffmpeg (los instala la Fase 0). Riesgo: Gradle + emulador
  simultáneos con 7 GB es justo; mitigación: emulador headless con RAM
  acotada, Gradle con `org.gradle.jvmargs` moderado, y teléfono físico
  por depuración inalámbrica (`adb connect`, funciona desde WSL2 sin
  usbipd) como vía alternativa de pruebas.
- [EXTRA-2] **Pedido original vs spec**: el usuario pidió "accede a Google
  Fotos" y "crea mapa en Google Maps". La spec resuelve con MediaStore
  local (restricción de la API desde 2025-03-31) y con exportación KML a
  My Maps más MapLibre por defecto. Se presenta como DIVERGENCIA respecto
  del pedido, para confirmación explícita.
- [EXTRA-3] **Decisiones abiertas de la spec** D1-D4 (§20): applicationId,
  proveedor de mapa, transcripción en la nube, Play vs personal. Van a la
  tanda como importantes; las que no se decidan quedan PENDIENTE con la
  fase que bloquean (D2 bloquea el mapa de Fase 1; D4 bloquea Fase 6).
- [EXTRA-4] **Alcance de Fase 5**: concentra seis funcionalidades grandes
  (Media3, CameraX, Google Fotos Picker/Library, widgets, mapas offline,
  zonas de privacidad). Es la fase con mayor riesgo de estimación.
  Pregunta de alcance: mantener completa, recortar o reordenar por valor.
- [EXTRA-5] **Teléfono de prueba**: marca y versión de Android del teléfono
  del usuario condicionan la guía anti-ahorro de batería (§16.1) y qué
  permisos de medios aplican (Android 13/14+). Se pregunta.
- [EXTRA-6] **Integración continua**: §17.5 la deja opcional. Las
  convenciones del cerebro exigen tests verdes antes de mergear; el hook
  local ya lo garantiza. Default: GitHub Actions con `./gradlew test` y
  `pytest` desde Fase 0 (bajo costo, segunda red de seguridad).
- [EXTRA-7] **Convención de commits**: la spec pide "inglés imperativo";
  el cerebro, conventional commits. Compatible; resuelto en CLAUDE.md del
  proyecto (conventional commits en inglés). No bloquea.
- [EXTRA-8] **Dependencias Python del renderizador** (§13.5) deben
  gestionarse con `uv` en `desktop-renderer/pyproject.toml`; el root del
  repo es Gradle. Ya en CLAUDE.md.
- [EXTRA-9] **Pruebas E2E con fixtures sintéticas** (§17.2-17.3) son
  coherentes con la regla del cerebro (tests contra fixtures, nunca contra
  datos reales). Los viajes de ejemplo en `tools/sample-trips/` se
  versionan; las fotos sintéticas se generan por script, no se commitean
  binarios grandes.

## Pasada c — hallazgos del researcher
(Se integra al recibir el informe: `docs/notes/estado-del-arte.md`.)

## Adenda 1 del usuario (2026-10-11) — lectura técnica

Fuente: `docs/incoming/pedido-original-adenda-1.md`. Los tres cambios son
compatibles con la arquitectura de la spec; ninguno agrega componentes
nuevos. Impacto por cambio:

- [EXTRA-10] **Viaje continuo sin inicio diario.** La spec modela el
  viaje como contenedor de etapas que el usuario inicia a mano
  ("Continuar etapa", §1.5, §14.1). El pedido convierte el registro en
  continuo desde "Iniciar viaje" hasta "Terminar viaje". Cambios:
  (a) el servicio de registro vive durante todo el viaje, días incluidos;
  (b) las etapas diarias se crean solas al cruzar la medianoche local
  (§9 ya inserta `DayChange`; pasa a cortar etapa);
  (c) estado **reposo automático**: tras N minutos quieto (stay point en
  curso) el muestreo baja al mínimo y vuelve a subir al detectar
  movimiento, sin intervención — el servicio sigue vivo, así que no hace
  falta el permiso de ubicación en segundo plano;
  (d) excursión y trekking siguen siendo declarables por el usuario como
  sub-etapas (cambian el perfil y la narrativa), pero dejan de ser
  obligatorios para que el registro exista;
  (e) la recuperación ante fallos (§6.9) pasa de deseable a crítica: en
  20 días el sistema va a matar el servicio al menos una vez (Doze,
  fabricante, reinicio). La notificación "se interrumpió, tocá para
  reanudar" y el reinicio automático con `START_STICKY` son de Fase 1.
  Efecto en el plan: la Fase 1 crece (reposo automático y corte diario
  eran de Fase 3 como "modo adaptativo" opcional y pasan a Fase 1,
  obligatorios). Pregunta a confirmar en la tanda: si el registro sigue
  de noche en el alojamiento (en reposo automático casi no consume) o
  si el usuario prefiere un botón "dormir hasta mañana".
- [EXTRA-11] **Modo full / económico elegido por el usuario.** La spec ya
  tiene perfiles por medio y un perfil `SAVER` automático con batería
  baja (§6.2). Se agrega un selector de dos posiciones en la sesión y en
  Ajustes: *Full* = perfiles de la spec; *Económico* = prioridad
  balanceada, intervalo 30-60 s, distancia mínima 50 m, agrupación de
  actualizaciones, reposo automático agresivo. Trade-off a documentar en
  la UI: en económico un trekking con zigzags pierde detalle y la
  distancia se subestima (orden de 5-10 %). Costo: bajo (Fase 1).
- [EXTRA-12] **Minivideo de 1 minuto a demanda en el teléfono.** El motor
  en el dispositivo (Media3) está en Fase 5. Para tener el minivideo
  antes se propone partirlo: un motor mínimo en Fase 4 (título, fotos
  con Ken Burns, imagen estática del mapa por etapa, subtítulos de voz,
  música, créditos; sin mapa animado ni clips) que entrega el preset
  "resumen rápido 1 min" desde la pantalla de fin de etapa; el motor
  completo queda en Fase 5. Reordena trabajo, no lo agrega. Riesgo R5
  (APIs de Media3 cambiantes) se adelanta una fase.
