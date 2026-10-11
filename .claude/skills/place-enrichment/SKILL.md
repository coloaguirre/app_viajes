---
name: place-enrichment
description: Enrich visited places in Bitácora de Viaje with internet information — reverse geocoding (Nominatim / Android Geocoder), Wikipedia GeoSearch and page summaries, Wikidata facts, Overpass POIs and hiking trails, ranking, optional LLM rewriting constrained to the source text, caching, rate limits, offline queueing and license attribution. Use for the enrichment package, EnrichWorker, places screen, or trail identification.
---

# Enriquecimiento con información de internet

Especificación: `docs/DISENO.md` §11 y §6.8.

## Reglas
- **Solo hechos de fuentes citadas.** Cada texto guarda `source_name`, `source_url` y `license`.
- **Política de uso de cada servicio:** User-Agent identificable (`BitacoraViaje/<versión> (<contacto del usuario o del proyecto>)`), máximo 1 pedido/s a Nominatim, y caché agresiva en la base.
- Todo en `EnrichWorker` con `NetworkType.CONNECTED`; idempotente (si ya existe el `place`/`enrichment`, no repetir).
- Opción de privacidad: redondear las coordenadas enviadas a 2 decimales (~1 km).

## Cliente HTTP
OkHttp con un interceptor que agrega el User-Agent, timeouts de 15 s y un `RateLimiter` por host (token bucket: Nominatim 1/s, Wikipedia 5/s, Overpass 1 cada 2 s con un único pedido concurrente). Parseo con `kotlinx.serialization` (`ignoreUnknownKeys = true`).

## 1. Geocodificación inversa (Nominatim)
```
GET https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=-49.33&lon=-72.89&zoom=14&accept-language=es&extratags=1&namedetails=1
```
- `zoom`: 10 (ciudad), 14 (barrio/localidad), 16-18 (calle/punto de interés).
- Usar: `name`, `display_name`, `address.{city|town|village|hamlet, state, country, country_code, road}`, `osm_type`, `osm_id`, `extratags.wikidata`, `extratags.wikipedia` (formato `es:Título`).
- Qué geocodificar: las paradas (zoom 16), y para cada etapa unas 20 muestras equiespaciadas del recorrido (zoom 10 y 14) para obtener las localidades y rutas atravesadas.
- Alternativa sin red de terceros: `android.location.Geocoder` (`getFromLocation` con callback en API 33+); resultados variables según el dispositivo.
- La política de Nominatim prohíbe el uso masivo. Para muchas consultas, considerar una instancia propia o un proveedor comercial (configurable).

## 2. Wikipedia: artículos cercanos
```
GET https://es.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=-49.33|-72.89&gsradius=5000&gslimit=10&format=json&formatversion=2
```
- `gsradius` máximo 10000 m. Respuesta: `query.geosearch[] {pageid, title, lat, lon, dist}`.
- Si no hay resultados en `es`, repetir en `en` y marcar `lang = en`.

## 3. Wikipedia: resumen
```
GET https://es.wikipedia.org/api/rest_v1/page/summary/{título URL-encoded}
```
- Usar `extract` (texto plano), `thumbnail.source`, `content_urls.desktop.page`, `wikibase_item` (QID) y `type` (descartar `disambiguation`).
- Licencia del texto: **CC BY-SA 4.0**. La imagen tiene licencia propia (Commons): **no** usarla en el video salvo que se verifique su licencia; en v1 no usar imágenes de Wikipedia en el video.

## 4. Wikidata: datos curiosos
```
GET https://www.wikidata.org/wiki/Special:EntityData/{QID}.json
```
Propiedades útiles: P31 (instancia de: ciudad, montaña, lago...), P1082 (población, con su fecha), P2044 (altitud), P571 (fundación), P1435 (patrimonio), P17 (país), P131 (ubicado en). Wikidata es **CC0**.
Con esto se arman frases como "Altitud: 3.405 m" o "Fundada en 1985". Siempre con el dato literal; nada inferido.

## 5. Overpass: puntos de interés y senderos
```
POST https://overpass-api.de/api/interpreter
data=[out:json][timeout:25];
(
  node(around:300,-49.33,-72.89)[tourism~"viewpoint|attraction|museum"];
  node(around:300,-49.33,-72.89)[historic];
  node(around:1000,-49.33,-72.89)[natural=peak];
);
out tags center 20;
```
**Senderos** de un trekking (para "identificar el trekking"):
```
[out:json][timeout:25];
(
  way(around:30, <polyline simplificada como lat,lon,lat,lon,...>)[highway~"path|footway|track"][name];
  relation(around:30, ...)[route=hiking];
);
out geom;
```
- Usar la polilínea simplificada a 30 m y, si es larga, partirla en tramos.
- Coincidencia: porcentaje de puntos del recorrido a < 30 m de la geometría del sendero (usar `geo-algorithms`). ≥ 60 % → nombre del trekking.
- Overpass tiene límites de uso y servidores alternativos; respetar `timeout`, manejar 429/504 con backoff.

## Ranking (cuáles mostrar)
```
score = w1·dwell_norm + w2·hasPhotos + w3·hasNotes + w4·log(1 + sitelinks) - w5·dist_norm
```
- `dwell_norm`: tiempo de permanencia normalizado.
- `sitelinks`: cantidad de idiomas con artículo (de Wikidata; indica notoriedad).
- Descartar los artículos más lejos que el radio por tipo (300 m para un punto de interés, 5 km para una localidad, 10 km para un pico o lago).
- Límite por etapa (por defecto 5) y deduplicación por QID.

## Reescritura con LLM (opcional)
- Solo si el usuario configuró una clave. Modelo configurable.
- Prompt (resumido):
  > Sos el redactor de una bitácora de viaje. Reescribí el TEXTO FUENTE en español rioplatense neutro, en 2 o 3 oraciones (máximo 60 palabras), en tono cálido. Usá EXCLUSIVAMENTE la información del texto fuente; no agregues datos, fechas ni cifras que no estén ahí. Si el texto fuente no tiene nada interesante, respondé exactamente: SIN_DATOS.
  > TEXTO FUENTE: «extract» · DATOS: «hechos de Wikidata»
- Validación posterior: los números del resultado deben aparecer en la fuente; si no, descartar la reescritura y usar el `extract` recortado.
- `llm_rewritten = 1`; siempre conservar la URL de la fuente.

## Pantalla de lugares
Tarjetas con el nombre, la distancia, el resumen, la fuente y un enlace. Acciones: **Usar en el video** (`user_approved = 1`), **Descartar**, **Editar texto**.

## Atribuciones (obligatorias)
- Bitácora exportada y créditos del video: "Textos: Wikipedia (CC BY-SA 4.0) — <títulos y URLs>", "Datos de mapa © OpenStreetMap contributors (ODbL)", "Datos: Wikidata (CC0)" y el proveedor de teselas.
- Guardar la lista en `trip.json.attributions[]`.

## Pruebas
- Respuestas grabadas (fixtures JSON reales en `src/test/resources/`) para cada API, con MockWebServer.
- Pruebas del ranking, el radio por tipo, la deduplicación, la validación de números del LLM y el comportamiento sin red (queda en la cola).
