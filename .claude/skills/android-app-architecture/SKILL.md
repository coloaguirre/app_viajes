---
name: android-app-architecture
description: Project structure and coding conventions for the Bitácora de Viaje Android app — Gradle modules, version catalog, Hilt, Room, Compose navigation, ViewModel/state patterns, WorkManager, error handling. Use when creating modules, screens, repositories, DAOs, Workers, or reviewing code structure.
---

# Arquitectura y convenciones

## Módulos
```
:app                Android application — UI, services, workers, DI
:data               Android library — Room DB, DAOs, repositories, DataStore
:core:model         Kotlin JVM — domain models, enums (no Android imports)
:core:geo           Kotlin JVM — geo algorithms (see skill geo-algorithms)
:core:timeline      Kotlin JVM — timeline builder + VideoPlan planner
:core:export        Kotlin JVM — exporters (GPX/KML/GeoJSON/JSON/CSV/HTML/SRT)
```
Regla: **`core:*` no importa nada de `android.*`**. Si necesitás algo de Android (Uri, Context), la conversión se hace en `:data` o `:app`.

Dependencias permitidas: `app → data → core:*`, y `core:timeline → core:geo → core:model`, `core:export → core:model, core:geo`.

## Catálogo de versiones (`gradle/libs.versions.toml`)
Bibliotecas previstas (fijar versiones **verificadas** al crear el proyecto):
- Kotlin, KSP, Android Gradle Plugin
- Compose BOM, Material 3, Navigation Compose, Lifecycle (runtime-compose, viewmodel-compose, service)
- Hilt (+ hilt-navigation-compose, hilt-work)
- Room (runtime, ktx, compiler vía KSP, testing)
- WorkManager (work-runtime-ktx)
- DataStore (preferences)
- Play Services Location
- Mapas: `maplibre-android-sdk` y/o `maps-compose` + `play-services-maps`
- CameraX (fase 5)
- Media3 (transformer, effect, exoplayer para reproducir) (fase 5)
- ExifInterface (androidx.exifinterface)
- Coil (miniaturas)
- kotlinx-serialization-json
- kotlinx-datetime (en `core`, para zonas horarias sin java.time de Android si hace falta; minSdk 26 ya tiene java.time)
- OkHttp + (Retrofit opcional) para las APIs web
- Pruebas: JUnit 4/5, Truth o kotest-assertions, Turbine, MockK, Robolectric, androidx.test, Compose UI test

## Room
- Una `@Database` en `:data` con `exportSchema = true` y `room.schemaLocation` → `data/schemas/` (va al repositorio).
- Cada cambio de esquema implica una **migración** y una prueba con `MigrationTestHelper`. **Nunca usar `fallbackToDestructiveMigration`.**
- Las entidades de Room (`*Entity`) son distintas de los modelos de dominio (`core:model`); mapear con funciones de extensión `toDomain()` / `toEntity()`.
- Inserción de puntos en lote: `@Insert suspend fun insertAll(points: List<TrackPointEntity>)`.
- Consultas reactivas con `Flow` para la UI; consultas `suspend` para Workers.
- Activar WAL (por defecto en Room) y claves foráneas.

## Patrón de pantalla (Compose + ViewModel)
```kotlin
data class ActiveSessionUiState(
    val segmentTitle: String = "",
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedMps: Double? = null,
    val gpsAccuracyM: Float? = null,
    val recentEvents: List<TimelineItemUi> = emptyList(),
    val isPaused: Boolean = false,
)

sealed interface ActiveSessionAction {
    data object TogglePause : ActiveSessionAction
    data object StartVoiceNote : ActiveSessionAction
    data class AddMarker(val text: String?) : ActiveSessionAction
    data object Finish : ActiveSessionAction
}

@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    private val trackingRepo: TrackingRepository,
    private val timelineRepo: TimelineRepository,
) : ViewModel() {
    val state: StateFlow<ActiveSessionUiState> = combine(
        trackingRepo.liveState, timelineRepo.recentEventsForActiveSegment()
    ) { live, events -> /* map */ }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveSessionUiState())

    fun onAction(a: ActiveSessionAction) { /* ... */ }
}

@Composable
fun ActiveSessionRoute(vm: ActiveSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ActiveSessionScreen(state, vm::onAction)
}

@Composable
fun ActiveSessionScreen(state: ActiveSessionUiState, onAction: (ActiveSessionAction) -> Unit) { /* stateless */ }
```
- Las pantallas `*Screen` son **sin estado** y tienen `@Preview`.
- Los efectos únicos (navegar, mostrar un snackbar) van por un `Channel<UiEvent>` expuesto como `Flow`.

## Navegación
- Navigation Compose con rutas tipadas (`@Serializable` data objects/classes).
- Grafo: `Onboarding → Home → NewSession → ActiveSession → FinishSegment`; `Home → TripDetail(tripId) → {Timeline, Map, Media, Places, Stats}`; `TripDetail → MediaReview | Export | VideoCreate`; `Settings`.

## Servicios y estado compartido
- `TrackingService` publica en un `@Singleton TrackingStateHolder` (`MutableStateFlow<LiveTrackingState>`). La UI no hace bind al servicio; observa el holder.
- Los comandos al servicio se envían por `Intent` con acciones (`ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`).

## WorkManager
- Workers con `@HiltWorker` + `CoroutineWorker`.
- Nombres únicos por entidad: `transcribe-<voiceNoteId>`, `enrich-<segmentId>`, `export-<jobId>`, `render-<renderId>`. Usar `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP)`.
- Los trabajos largos llaman a `setForeground()` con el tipo de servicio correcto.
- El progreso se publica con `setProgress()` y la UI lo observa con `getWorkInfoByIdFlow`.

## Errores y logs
- Errores de dominio como `sealed interface`. Usar `Result`/`runCatching` en los bordes y no tragarse excepciones.
- Log con un wrapper (`Log.d("Bitacora", ...)`, o Timber si se agrega). **Nunca loguear coordenadas en builds release.**

## Estilo
- Kotlin oficial (`kotlin.code.style=official`); ktlint o detekt opcionales.
- Inyección por constructor; nada de service locators.
- Funciones puras siempre que se pueda; el tiempo se inyecta (`Clock`) para poder probar.
- Unidades explícitas en los nombres: `distanceM`, `timeUtcMs`, `speedMps`, `durationMs`.
