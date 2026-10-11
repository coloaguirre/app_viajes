# Comparativa: requerimientos de Bitácora de Viaje vs Polarsteps

Fecha: 2026-10-11. Fuente propia: `docs/incoming/DISENO.md` y
`docs/incoming/pedido-original.md`. Fuentes de Polarsteps: sitio oficial,
centro de ayuda (Trip Reels, Travel Tracker), página de Polarsteps Plus,
guías y reseñas de 2026 (enlaces al pie). Lo que no pude confirmar
queda marcado como "sin dato".

| Dimensión | Bitácora de Viaje (spec) | Polarsteps | Lectura |
|---|---|---|---|
| Modelo | App personal, sin cuenta ni servidor; todo local | Servicio en la nube con cuenta; 22M+ usuarios; gratis con suscripción Plus (8,99 €/mes o 29,99 €/año) | Diferencia de fondo: datos propios vs plataforma |
| Registro GPS | Servicio en primer plano, perfiles por medio (3 s a 60 s), filtros, puntos completos (velocidad, altitud, precisión) | Tracker automático en segundo plano, de baja frecuencia; pensado para la traza del viaje, no para el detalle del tramo | La spec apunta a precisión de actividad (trekking); Polarsteps a la traza del viaje |
| Batería | Objetivo ≤ 5-8 %/h en perfil caminata | Declara < 4 % por día | Polarsteps gana por muestreo mucho más espaciado; es el precio de la precisión |
| Permiso de ubicación en segundo plano | No se pide en v1 (servicio visible iniciado desde la UI) | Requiere ubicación en segundo plano siempre | La spec evita el permiso más conflictivo de Play |
| Tipos de sesión | Viaje, etapa, excursión, trekking; medio de transporte; desnivel | Un viaje con "steps" (paradas) | Concepto de excursión/trekking anidado no existe en Polarsteps |
| Detección de paradas | Automática (stay point, radio 50 m, 5 min) + geocodificación | Steps automáticos por lugar visitado y manuales | Equivalente en idea; la spec guarda radio e intervalo |
| Fotos | Vinculación automática por hora/EXIF desde la galería local; revisión de dudosas; corrección de desfase de cámara; cámara propia opcional | El usuario elige fotos de la galería y las agrega a cada step a mano (no hay sugerencia automática confirmada) | Ventaja clara de la spec: cero trabajo manual en el caso normal |
| Videos | Vinculados igual que fotos; clips recortados en el video resumen | Se agregan a los steps; entran en los Trip Reels | Paridad |
| Notas de voz | Botón, grabación desde notificación/pantalla bloqueada, transcripción local (whisper) con audio conservado | Sin dato: solo notas de texto documentadas | Diferenciador principal de la spec |
| Notas de texto / marcadores | Notas, marcadores, destacados | Notas de texto por step | Paridad en texto; destacados no existen |
| Línea de tiempo | Unificada: recorrido, fotos, voz, notas, lugares, cambios de día; sincronizada con el mapa | Feed cronológico por steps | Similar; la spec es más granular |
| Mapa en la app | MapLibre u Google Maps; capas por velocidad/medio; mapas offline (fase 5) | Mapa propio; 3D y estilos extra solo con Plus | Paridad; offline en ambos |
| Enriquecimiento de lugares | Wikipedia, Wikidata, Nominatim, Overpass; tarjetas con atribución; LLM opcional | Nombres de lugares y consejos de viaje (planificador con IA desde 2025) | La spec enriquece la bitácora; Polarsteps enriquece la planificación |
| Video resumen | Plan declarativo; duración 1 a 10 min; 16:9 o 9:16; mapa animado, Ken Burns, subtítulos de voz, tarjetas de lugar, música propia, créditos; motor escritorio + dispositivo | Trip Reels automáticos de hasta 60 s, editables hasta 3 min 15 s; selección automática de medios; se comparte o descarga | La spec es más larga y narrativa; Polarsteps es inmediato y social |
| Exportación | ZIP por viaje desde el teléfono: GPX, KML/KMZ, GeoJSON, CSV, JSON con esquema, HTML autocontenido, SRT, medios y audios | GPX por viaje; ZIP completo de la cuenta solo desde la web, por pedido, demora 2-72 h, enlace válido 7 días; JSON + GeoJSON + fotos; sin KML, sin HTML, sin videos de reel | Ventaja fuerte de la spec: exportación inmediata, local y rica |
| Importación | trip.json propio y GPX externos | Sin importación de trazas externas documentada | Ventaja de la spec |
| Estadísticas | Distancia, tiempo en movimiento, desnivel con histéresis, velocidad p98, por etapa | Básicas gratis; desnivel, perfiles de elevación y medios de transporte solo con Plus | Lo que Polarsteps cobra, la spec lo trae de base |
| Compartir / social | Fuera de alcance v1 | Núcleo del producto: seguidores, enlace público, hasta 10 "travel buddies" con Plus | Decisión consciente de la spec |
| Libro impreso | No | Sí, de pago | Fuera de alcance |
| Planificador de viaje | No | Sí, con itinerarios asistidos por IA | Fuera de alcance |
| Privacidad | Datos nunca salen del teléfono salvo coordenadas aproximadas al enriquecer; zonas de privacidad | Tres niveles de visibilidad; exposición de datos reportada en septiembre de 2026 que afectó viajes privados | Argumento a favor del diseño local de la spec |
| Plataformas | Solo Android, instalación directa (sideload) al inicio | Android e iOS, tiendas oficiales | Polarsteps gana en alcance; irrelevante para uso personal |
| Colaboración | No | Limitada (una persona edita; buddies con Plus) | Paridad práctica |

## Qué se desprende para el kickoff

1. **Lo que justifica construir en vez de usar Polarsteps**: notas de voz
   transcriptas, vinculación automática de fotos, exportación inmediata y
   completa en formatos abiertos, precisión de actividad (trekking) y
   propiedad total de los datos. Son exactamente los objetivos O2, O3 y
   O5 del pedido original.
2. **Donde Polarsteps marca una vara**: batería (< 4 %/día) y la
   inmediatez del Trip Reel (un botón, 60 s). La spec debería ofrecer un
   preset "resumen rápido de 1 minuto" en el dispositivo para no perder
   esa gratificación inmediata, además del video largo de escritorio.
3. **Oportunidad de alcance**: el modo "play" (reproducir el viaje de
   principio a fin sobre el mapa) que Polarsteps vende en Plus ya está
   casi incluido en el mapa animado del video de la spec; exponerlo en
   la app cuesta poco una vez hecho el motor de cuadros.
4. **Confirmación de alcance**: lo social, el libro y el planificador
   quedan fuera, como ya dice la spec §1.4.

## Fuentes
- https://www.polarsteps.com/
- https://support.polarsteps.com/hc/en-us/articles/29738453390866-What-are-Trip-Reels
- https://www.polarsteps.com/plus
- https://www.ventureout.life/blog/export-polarsteps-data
- https://thousandtravelmiles.nl/en/travel-tip/polarsteps-app-manual
- https://www.wandrly.app/reviews/polarsteps
- https://trippytaless.com/blogs/is-polarsteps-worth-it-2026/
- https://news.polarsteps.com/news/polarsteps-summer-2025-release-is-here
