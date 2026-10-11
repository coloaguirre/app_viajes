---
name: geo-algorithms
description: Pure-Kotlin geo algorithms for Bitácora de Viaje in the core:geo module — haversine distance, GPS point filtering, metrics (moving time, elevation gain with hysteresis), stay-point stop detection, Douglas-Peucker simplification, time interpolation for photo linking, camera clock-offset correction, time zones. Use when writing or testing any geometric or temporal computation.
---

# Algoritmos geográficos (`core:geo`)

Todo en **Kotlin JVM puro**, sin `android.*`, con pruebas unitarias exhaustivas. Usar `Double` para las coordenadas.

## Haversine
```kotlin
const val EARTH_RADIUS_M = 6_371_008.8
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}
```
Pruebas: Buenos Aires (-34.6037, -58.3816) → Córdoba (-31.4201, -64.1888) ≈ 646 km (±1 %). Distancia de un punto a sí mismo = 0. Cruce del antimeridiano (179.9 → -179.9) ≈ 22 km en el ecuador, no 40.000 km.

## Filtro de puntos
```kotlin
data class FilterConfig(val maxAccuracyM: Double, val maxSpeedMps: Double, val stillSpeedMps: Double = 0.5, val stillRadiusM: Double = 15.0)
data class FilterVerdict(val accepted: Boolean, val reason: Reason?, val addedDistanceM: Double)
enum class Reason { LOW_ACCURACY, IMPOSSIBLE_JUMP, DUPLICATE_TIME, MOCK }
```
Reglas, en orden: tiempo duplicado o anterior al último → descartar; precisión > umbral → descartar; velocidad implícita respecto del último aceptado > `maxSpeedMps` → descartar (pero si los **3 siguientes** confirman la nueva zona, aceptar: fue un salto real tras un hueco largo); en reposo, distancia sumada = 0 dentro de `stillRadiusM`.

Configuración por perfil: a pie (25 m, 4 m/s), bici (25 m, 20 m/s), vehículo (50 m, 70 m/s), avión (200 m, 300 m/s).

## Métricas
- `distanceM`: suma de haversine entre puntos **aceptados**, reiniciando en cada pausa (no sumar el tramo pausa→reanudación).
- `movingTimeMs`: suma de `Δt` donde la velocidad del tramo supera el umbral (0,5 m/s a pie; 1,5 m/s en vehículo) y `Δt < 120 s` (los huecos no cuentan).
- **Desnivel con histéresis:**
```kotlin
fun elevationGainLoss(alts: List<Double>, threshold: Double = 3.0): Pair<Double, Double> {
    val smooth = movingAverage(alts, window = 5)
    var gain = 0.0; var loss = 0.0; var ref = smooth.firstOrNull() ?: return 0.0 to 0.0
    for (a in smooth.drop(1)) {
        val d = a - ref
        if (d >= threshold) { gain += d; ref = a } else if (d <= -threshold) { loss += -d; ref = a }
    }
    return gain to loss
}
```
- `maxSpeed`: percentil 98 de las velocidades de tramo válidas.
- Bounding box: min/max de lat/lon (cuidado con el antimeridiano: si `maxLon - minLon > 180`, normalizar).

## Detección de paradas (stay points)
```kotlin
fun detectStops(points: List<TrackPoint>, radiusM: Double = 50.0, minDurationMs: Long = 5 * 60_000): List<Stop>
```
Algoritmo clásico: para `i` desde 0, avanzar `j` mientras `haversine(p[i], p[j]) <= radiusM`; si `p[j-1].t - p[i].t >= minDurationMs`, emitir una parada con el centroide de `p[i..j-1]`, su radio máximo y su intervalo, y continuar desde `j`; si no, `i++`. Después **fusionar** paradas contiguas a menos de `radiusM` entre sí y con menos de 2 min de separación.

Los huecos sin puntos (pantalla apagada en interiores, sin señal) con el mismo lugar antes y después **son** paradas: tratar `Δt` grande con distancia pequeña como permanencia.

Pruebas: sintéticas con ruido gaussiano de 10 m alrededor de un punto durante 10 min → 1 parada; caminata continua → 0 paradas; dos cafés separados 30 m → fusionadas en una.

## Douglas-Peucker
Iterativo (no recursivo, para no desbordar la pila con 100.000 puntos). La distancia perpendicular se calcula sobre una proyección **equirectangular local** (x = Δlon·cos(lat₀)·R, y = Δlat·R), suficiente para tramos cortos. Devuelve los índices a conservar. Tolerancias: 5 m (detalle) y 30 m (general/video).

## Interpolación temporal (vinculación de fotos)
```kotlin
sealed interface PositionAt {
    data class Interpolated(val lat: Double, val lon: Double, val gapMs: Long, val confidence: Double) : PositionAt
    data class Nearest(val lat: Double, val lon: Double, val gapMs: Long, val confidence: Double) : PositionAt
    data object None : PositionAt
}
fun positionAt(points: List<TrackPoint> /* ordenados por tiempo */, tUtcMs: Long,
               maxInterpGapMs: Long = 5 * 60_000, maxNearestMs: Long = 10 * 60_000): PositionAt
```
- Búsqueda binaria del primer punto con `t >= tUtcMs`.
- Con anterior y siguiente, y `(next.t - prev.t) <= maxInterpGapMs`: interpolación lineal con `f = (t - prev.t)/(next.t - prev.t)`. Confianza 0,8.
- Si no, el más cercano dentro de `maxNearestMs`: confianza `0,7 * (1 - gap/maxNearestMs)`.
- Si no, `None`.

Lógica completa de vinculación (EXIF vs recorrido, revisión manual): ver la skill `media-linking` y `DISENO.md` §7.6.

## Corrección del desfase del reloj de una cámara
Dada una foto de la cámara C que el usuario ubicó a mano en el punto P: buscar en el recorrido el instante `t*` más cercano en el espacio a P (mínimo de distancia; si hay varios, el más cercano en el tiempo a la hora de la foto). `offset = t* - foto.t`. Aplicar `offset` a todas las fotos de C (mismo `Make`+`Model`) y volver a vincularlas. Mostrar el offset al usuario ("la cámara estaba adelantada 1 h 02 min") antes de aplicarlo.

## Zonas horarias
- La base guarda UTC. Para mostrar: `Instant.ofEpochMilli(t).atZone(ZoneId.of(segment.tzId))`.
- Si un viaje cruza husos, calcular la zona por coordenadas: opción liviana con una librería offline de *timezone lookup* por coordenadas (evaluar tamaño). Alternativa: guardar `TimeZone.getDefault()` del teléfono en cada punto de cambio (el teléfono la ajusta solo con la red).
- La medianoche local define los cambios de día de la línea de tiempo.

## Arco geodésico (vuelos sin puntos)
Interpolación por *slerp* sobre la esfera entre dos aeropuertos, con N puntos intermedios, para dibujar tramos de avión.

## Regla general de pruebas
Cada función con casos normales, de borde (lista vacía, un punto, puntos idénticos, antimeridiano, polos) y propiedades (la distancia es simétrica; la simplificación conserva el primer y el último punto; las paradas no se superponen).
