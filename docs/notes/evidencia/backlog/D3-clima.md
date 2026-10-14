# Evidencia D3 — clima por hora a lo largo del recorrido (2026-10-14)

Pedido del usuario (backlog deseable, `docs/notes/backlog-deseables.md` §1): capturar el clima de
mañana, tarde y noche y sumarlo al registro, u otro esquema horario si hay algo mejor.

## Qué hace
- Al **cerrar una etapa** (y cuando hay red; si no, queda en cola como los lugares) se consulta el
  clima de **cada hora donde estuvo el usuario**: `WeatherSampling` (`core/geo`) toma la posición
  del recorrido en el centro de cada hora (interpolada hasta 2 h de hueco, o el fijo más cercano a
  45 min; las horas sin posición se saltan) y la redondea a 2 decimales (~1 km). Lo único que sale
  del teléfono son esas coordenadas redondeadas y la fecha, nunca el recorrido.
- **Open-Meteo** (`OpenMeteoClient`): gratuito, sin clave, CC BY 4.0. Una sola llamada por día UTC
  con todas las posiciones de ese día (coordenadas separadas por coma); el endpoint de pronóstico
  sirve el pasado reciente (unos 92 días) y el archivo ERA5 lo anterior (se usa para días de más de
  60 días). Variables: temperatura, sensación térmica, precipitación, viento, nubosidad y código
  WMO. Pasa por `OsmHttp` (User-Agent identificable, 15 s, caché, backoff en 429/5xx).
- Las horas se guardan en la tabla **`weather_sample`** (Room v11, migración 10→11 con test), una
  fila por hora y etapa, en el centro de la hora (HH:30). `WeatherRepository.replace` reemplaza las
  horas de una etapa en una transacción. La tabla **`weather_fetch`** (v12, migración 11→12 con
  test) marca cada etapa consultada por completo, aun sin horas útiles, para no re-consultarla
  (salvo `force`); una consulta parcial (un día sin red, una hora cuyo centro todavía no pasó) no
  marca y el worker reintenta. El worker de una etapa recién cerrada espera a que termine su última
  hora (+15 min) para que el modelo conteste con observaciones y no con un pronóstico.
- **Bitácora** (`WeatherDigest`, `core/timeline`): bloques del día local **Mañana (6–12) / Tarde
  (12–20) / Noche (20–6)** con rango de temperatura, lluvia acumulada, viento máximo y el tiempo
  dominante (la familia más severa que cubra ≥ 30 % de las horas, si no la más frecuente), como
  fila propia de la línea de tiempo ("⛅ Tarde: Nublado, 15–16 °C"); y la hora de cada **foto,
  parada y nota de voz** como sufijo ("4 fotos y videos · ⛅ 16 °C"). La tarjeta del mapa
  interactivo (D2) también lo muestra.
- **Exportación**: `trip.json` lleva `weather[]` por etapa (esquema `docs/trip.schema.json`
  actualizado; campo opcional, los paquetes viejos siguen siendo válidos); `tablas/clima.csv`;
  la bitácora Markdown y HTML; `LEEME.txt` ("Clima: N horas con datos de Open-Meteo") y la
  atribución obligatoria "Datos meteorológicos: Open-Meteo — CC BY 4.0". Importar un paquete
  restaura las horas.
- **Video**: la tarjeta de capítulo muestra "Despejado, 8–17 °C" (`PlanSegment.weather`,
  `Chapter.weather` en el esquema del plan) y los créditos citan Open-Meteo; implementado en los
  dos planificadores (Kotlin y Python, `desktop-renderer`), con los fixtures de conformidad
  regenerados y la prueba de paridad en verde de ambos lados.
- **Ajustes → "Clima del recorrido"** (activado por defecto): explica qué sale del teléfono.
- Al abrir el detalle de un viaje, las etapas cerradas sin clima lo piden (cubre viajes anteriores
  a esta versión y etapas cerradas sin red).

## Pruebas
- `WeatherSamplingTest` (core/geo): plan vacío; una muestra por hora en el centro, redondeada, con
  ubicaciones deduplicadas; huecos sin fijo saltados; última hora acotada al fin de la etapa; tope
  de horas; dentro de una pausa nunca se interpola a través de ella.
- `WeatherDigestTest` (core/timeline): bloques por día local (las horas antes de las 6 pertenecen a
  la noche del día anterior), rango/lluvia/viento/dominante, `at` dentro de 90 min, `brief`, códigos.
- `TimelineTest`: filas de bloque, foto etiquetada con su hora, sin partir los resúmenes de tramo.
- `MigrationTest` 10→11 (la tabla se borra en cascada con la etapa); `WeatherRepositoryTest`.
- `OpenMeteoClientTest`: respuestas reales grabadas (`app/src/test/resources/weather/`, coordenadas
  genéricas): dos ubicaciones en una llamada, archivo para días viejos, error del servicio.
- `WeatherEnricherTest` (Room en memoria, fuente falsa): una llamada por día, una muestra por hora
  (10–13 UTC de una caminata de 3 h), idempotencia y `force`, sin red → parcial y reintento, apagado
  en Ajustes → ninguna llamada; etapa que cruza dos días UTC con un día caído (se guarda el otro y
  sigue pendiente); hora cuyo centro no pasó (queda para el reintento); rechazo definitivo del
  servicio (marcada sin datos, no se vuelve a pedir, `force` sí); una recarga forzada sin resultado
  no borra las horas guardadas; etapa sin puntos marcada sin ninguna llamada. `WeatherWorkerTest`:
  la espera hasta el fin de la última hora. `OpenMeteoClientTest`: 503 y 403 de portal cautivo
  reintentables, respuesta ilegible como error de red.
- `PrivacyFilterTest`: las horas cuya celda de ~1 km toca una zona salen del paquete y se cuentan en
  el reporte (LEEME); el crédito de Open-Meteo sigue si queda clima e importar un paquete con clima
  marca la etapa como consultada.
- `core/export`: escritor y lector de `trip.json` (validación contra el esquema), `clima.csv`, bloques
  y atribución en Markdown, `PlanInputs` (fixtures); `ImportTest`/`ImportRoundTripTest` con clima en
  la huella; `DetailScreensComposeTest` (fila del bloque, sufijo en la foto, tarjeta del mapa).
- Gate: `./gradlew test` ✅ · `lint assembleDebug` ✅ · 16 KB ✅ · `ruff`/`pytest` ✅.

## E2E en el emulador (API 36, con red real)
1. Abrir el viaje importado "Patagonia" (4 etapas cerradas el 2026-10-12): el detalle encola un
   trabajo por etapa; en ~4 s el log muestra `weather segment=… hours=1/1 requests=1
   partial=false` para cada una (una etapa de 17 min: 2 horas tocadas → 2/2). La tabla queda con
   5 filas (9,9 a 15,6 °C), traídas del endpoint de pronóstico (2 días atrás).
2. Línea de tiempo: fila "⛅ Tarde: Nublado, 15–16 °C" al inicio de la etapa y "4 fotos y videos ·
   ⛅ 16 °C", "Llegada a una parada · ⛅ 15 °C" (`D3-linea-de-tiempo.png`).
3. Mapa: la tarjeta de un marcador de fotos termina en "· ⛅ 15 °C" (`D3-mapa.png`).
4. Exportar a la PC → `tablas/clima.csv` con las 5 horas (hora local de la etapa, código WMO y
   descripción), LEEME "Clima: 5 horas con datos de Open-Meteo", bitácora con "Tarde: Nublado,
   15–16 °C" y `trip.json` con `weather` por etapa. **Hallazgo del E2E**: con "Aplicar zonas de
   privacidad" activo faltaba el crédito de Open-Meteo, porque `PrivacyFilter` recomputaba las
   atribuciones solo a partir de los textos; corregido (y las horas cuya posición cae dentro de una
   zona de privacidad ya no salen en el paquete), con test.
5. Ajustes: fila "Clima del recorrido" con su interruptor (`D3-ajustes.png`).

6. Revisión del código (dos pasadas, informes en el PR): tras el arreglo, instalar la versión con
   Room v12 sobre la base v11 del emulador migró sin error, el detalle encoló una sola vez y dejó 4
   filas en `weather_fetch`, y la exportación **con zonas de privacidad** lleva el crédito de
   Open-Meteo en `trip.json`, LEEME y bitácora (antes lo perdía).

## Decisiones y límites
- Etapa **en curso**: no se consulta hasta cerrarla (offline primero; nada de la sesión activa
  depende de red). Una etapa recién cerrada espera a que pase su última hora. No hay botón para
  volver a consultar (queda `force` interno y `WeatherRepository.clearFetched`).
- Un error definitivo del servicio (4xx) deja la etapa marcada sin datos; un 429/5xx o la falta de
  red reintentan con backoff (hasta 3 intentos del worker, y de nuevo al abrir el detalle).
- Resolución: malla de ~9–11 km del modelo; sirve para "llovió / hizo frío", no para microclimas.
- Los textos de los códigos WMO y de los bloques viven en `core` (en español, como las líneas de la
  bitácora exportada) porque los usan la exportación y el video, no solo la app.
