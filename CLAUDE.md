# Proyecto: Bitácora de Viaje (app_viajes)

> Este archivo complementa las convenciones globales (~/.claude/CLAUDE.md).
> Leelo completo al empezar cada sesión.

## Contexto
- **Proyecto personal**, no de la consultora. No hay cliente ni grupo
  GitLab. NO aplican las reglas de cliente de las convenciones globales
  (confidencialidad entre clientes, PLAN.md como contrato comercial,
  `connections/`, `sql/`, `data/raw`, contratos de datos, GitLab CI).
  SÍ aplican: idioma, git (ramas y conventional commits), tests antes
  de mergear, `uv` para Python, redacción clara en informes.
- Remote: GitHub (`origin`). Ramas de entorno `main` y `dev`; trabajo en
  ramas `feat/...` y `fix/...` cortas que mergean a `dev`.

## Qué es este proyecto
App Android (Kotlin + Jetpack Compose) que registra un viaje: recorrido
GPS, fotos y videos vinculados al lugar y la hora, notas de voz
transcriptas y notas de texto. Arma una bitácora cronológica, la exporta
en formatos abiertos para la PC y genera un video resumen. Incluye un
renderizador de video de escritorio en Python.

**La especificación completa está en `docs/incoming/DISENO.md`** (inmutable:
nunca editarla; versiones nuevas entran como archivo nuevo). Ante
cualquier duda de comportamiento, ese documento manda. Si encontrás una
contradicción o un hueco, preguntá al usuario antes de inventar.

## Stack
- Android nativo: Kotlin, Jetpack Compose + Material 3, Hilt, Room (WAL),
  Coroutines + Flow, WorkManager, FusedLocationProvider, CameraX, Media3.
- Build: Gradle (Kotlin DSL) con catálogo `gradle/libs.versions.toml`.
  Gradle wrapper en el repo; nunca un Gradle global.
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`. JDK 17 (verificar si el
  AGP vigente pide 21).
- Módulos: `app/`, `core/{model,geo,timeline,export}` (Kotlin JVM puro),
  `data/` (Room; es un módulo Gradle, NO está gitignorado),
  `desktop-renderer/` y `tools/` (Python). Detalle en DISENO.md §4.2.
- Python (`desktop-renderer/`, `tools/`): cada subproyecto con su propio
  `pyproject.toml` gestionado con `uv`. Correr todo vía `uv run`.
  Lint y formato: `ruff check` y `ruff format`. Tests: `pytest`.
- Mapas: abstracción `MapRenderer`; por defecto MapLibre + MapTiler
  (decisión D2 abierta), Google Maps como opción.

## Idioma
- Hablá con el usuario en **español**.
- Código (identificadores, comentarios técnicos, commits) en **inglés**.
- Textos visibles para el usuario en `res/values/strings.xml` en
  **español**, nunca en duro en el código.

## Cómo trabajar
1. **Seguí las fases de `docs/incoming/DISENO.md` §19 en orden.** No
   empieces una fase sin que la anterior cumpla sus criterios de
   aceptación. Al terminar cada fase, mostrá al usuario la evidencia:
   salida de pruebas y capturas del emulador.
2. **Antes de tocar un área, cargá su skill** (tabla de abajo).
3. **Lógica pura en los módulos `core/*`** (Kotlin JVM, sin dependencias
   de Android), con pruebas unitarias. Lo que se puede probar sin
   emulador, se prueba sin emulador.
4. **Versiones de dependencias:** nunca de memoria. Consultá la última
   versión estable (Maven Google / Maven Central / docs oficiales) y
   fijala en `gradle/libs.versions.toml`. Registrala en `docs/PROGRESS.md`.
5. **No agregues dependencias** fuera de las previstas en el diseño sin
   explicar por qué.
6. **Commits pequeños**, uno por unidad de trabajo coherente, en inglés
   con conventional commits (`feat:`, `fix:`, `docs:`, `test:`,
   `refactor:`, `chore:`, `build:`).
7. Mantené `docs/PROGRESS.md` actualizado: qué se hizo, qué falta,
   decisiones tomadas y problemas conocidos. Notas y resúmenes propios
   van a `docs/notes/`.
8. Si algo requiere acción del usuario (una clave de API, una prueba en
   la calle, un permiso en el teléfono), pedilo claramente y seguí con
   lo que no dependa de eso.
9. Antes de instalar algo en el sistema (SDK, JDK, paquetes), decile al
   usuario qué vas a instalar.

## Skills del proyecto (`.claude/skills/`)

| Skill | Cuándo cargarla |
|---|---|
| `android-linux-env` | Configurar el entorno, compilar, emulador, adb, problemas de build |
| `android-app-architecture` | Estructura de módulos, Hilt, Room, navegación, ViewModels, convenciones |
| `location-tracking` | Servicio en primer plano, FusedLocation, perfiles, notificación, recuperación |
| `geo-algorithms` | Distancias, filtros, paradas, Douglas-Peucker, interpolación, zonas horarias |
| `media-linking` | MediaStore, permisos de medios, EXIF, vinculación foto↔recorrido, CameraX, Google Fotos |
| `voice-journal` | Grabación de notas, actividad desde notificación, transcripción (whisper.cpp y otros) |
| `trip-export` | GPX, KML/KMZ, GeoJSON, CSV, JSON, HTML, SRT, ZIP, SAF, importación |
| `place-enrichment` | Nominatim, Wikipedia, Wikidata, Overpass, LLM, caché, licencias |
| `trip-video` | VideoPlan, renderizador de escritorio (Python/FFmpeg), Media3 Transformer, mapa animado |
| `android-testing` | Pruebas unitarias e instrumentadas, emulador, reproducción de GPX, fixtures, capturas |
| `play-policy-privacy` | Permisos, declaraciones de Play, seguridad de datos, privacidad, claves de API |

## Comandos frecuentes
```bash
./gradlew assembleDebug                 # compilar
./gradlew test                          # todas las pruebas JVM
./gradlew :core:geo:test                # pruebas de un módulo
./gradlew connectedDebugAndroidTest     # pruebas instrumentadas (emulador o teléfono)
./gradlew lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Bitacora:* AndroidRuntime:E
adb exec-out screencap -p > /tmp/screen.png   # luego leer la imagen para verificar la UI
uv run --project tools/gpx-replay python tools/gpx-replay/replay.py tools/sample-trips/trekking/track.gpx --speed 20
cd desktop-renderer && uv run pytest
uv run --with openpyxl python tools/gantt.py   # regenera reports/gantt.xlsx desde PLAN.md + git
```

## Reglas que no se negocian
- **Nunca perder datos de un viaje.** Escrituras en transacciones; las
  migraciones de Room se prueban; nada se borra sin confirmación.
- **Los medios originales del usuario no se modifican nunca.** Se leen;
  si hay que transformarlos, se trabaja sobre copias.
- **Offline primero:** nada de la sesión activa depende de internet.
- **Hora en UTC (epoch ms)** en la base, con la zona horaria guardada
  aparte. Se convierte a hora local solo para mostrar.
- **Claves y secretos fuera del repositorio** (`local.properties`, `.env`,
  DataStore cifrado). Revisá antes de cada commit.
- **No usar la Library API de Google Fotos para leer la biblioteca del
  usuario**: ya no lo permite desde el 31/03/2025. Ver skill `media-linking`.
- **No pedir `ACCESS_BACKGROUND_LOCATION`** en la versión 1. El servicio
  en primer plano iniciado desde la UI no lo necesita.
- **Atribución obligatoria** de Wikipedia (CC BY-SA), OSM (ODbL) y el
  proveedor de teselas en la bitácora exportada y los créditos del video.
- **PROHIBIDO mergear a `dev` o `main` con tests fallando**
  (`./tools/run-tests.sh`: corre `./gradlew test` y los `pytest` de los
  subproyectos Python; el hook de git lo ejecuta antes de cada push).

## Identificadores
- `applicationId` / paquete: `com.bitacoraviaje.app` (provisorio, decisión D1).
- Tag de log: `Bitacora`.
- Nombre visible: "Bitácora de Viaje".

## Decisiones abiertas (DISENO.md §20)
D1 nombre y `applicationId` · D2 proveedor de mapa · D3 transcripción en
la nube · D4 publicar en Play o uso personal. Registrarlas en
`docs/PROGRESS.md` cuando se decidan.
