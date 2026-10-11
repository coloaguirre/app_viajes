# Registro de avance

> Este archivo lo mantiene Claude Code. Actualizarlo al terminar cada tarea relevante.

## Estado actual
- Fase: **0 — no iniciada**
- Última actualización: —

## Fases
| Fase | Estado | Evidencia de aceptación |
|---|---|---|
| 0 — Esqueleto | ⬜ Pendiente | |
| 1 — Recorrido | ⬜ Pendiente | |
| 2 — Fotos, voz y línea de tiempo | ⬜ Pendiente | |
| 3 — Exportación y transcripción local | ⬜ Pendiente | |
| 4 — Enriquecimiento y video de escritorio | ⬜ Pendiente | |
| 5 — Video en el dispositivo y extras | ⬜ Pendiente | |
| 6 — Pulido y publicación | ⬜ Pendiente | |

## Decisiones tomadas
| Fecha | Decisión | Motivo |
|---|---|---|
| 2026-10-11 | Nivel de calidad 2 (ver PLAN.md) | Perder un recorrido es irreversible |
| 2026-10-11 | D1: `com.bitacoraviaje.app`, nombre "Bitácora de Viaje" | Default de la spec, confirmado |
| 2026-10-11 | D2: MapLibre + MapTiler en v1; Google Maps futuro | Clave gratuita, mapas offline, uso en video |
| 2026-10-11 | D3: transcripción solo local; modelo se elige midiendo | Privacidad y uso sin red |
| 2026-10-11 | D4: uso personal (sideload); Fase 6 en pausa | Decisión del usuario |
| 2026-10-11 | Registro continuo del viaje con reposo automático y corte diario | Adenda 1 del usuario |
| 2026-10-11 | Modo Full / Económico elegido por el usuario | Adenda 1 del usuario |
| 2026-10-11 | Minivideo de 1 min en el teléfono adelantado a Fase 4 | Adenda 1 del usuario |
| 2026-10-11 | Teselas MapTiler en el video aceptadas para uso personal | Zona gris de términos; PMTiles si se publica |

## Versiones fijadas
| Componente | Versión | Verificada el |
|---|---|---|

## Problemas conocidos
-

## Pendientes del usuario
- [x] Definir nombre y `applicationId` (D1) — default confirmado 2026-10-11
- [ ] Crear clave gratuita de MapTiler y ponerla en `local.properties` (antes del ítem 1.6)
