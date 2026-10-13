# Bitácora de Viaje — Esquema funcional

> Flujograma del sistema al cierre de la Fase 5 (2026-10-13). Derivado de `PLAN.md` y del
> código; se regenera con `/document`.

```mermaid
flowchart TD
    subgraph Entrada["Entrada (en el teléfono, sin red)"]
        GPS[(FusedLocation GPS)]
        GAL[(Galería MediaStore)]
        CAM[Cámara propia CameraX]
        MIC[Micrófono: notas de voz]
        TXT[Notas de texto / marcadores / destacados]
        QS[Notificación · mosaicos · widget · accesos directos]
    end

    GPS --> FIL[Filtro de puntos core/geo]
    FIL --> SVC[TrackingService: lotes a Room, estado en vivo, reposo automático, corte diario]
    QS --> SVC
    QS --> MIC
    SVC --> DB[(Room v9: trip · segment · track_point · stop · media_item · voice_note · text_note · place · enrichment)]
    CAM --> REG[CameraCapture: EXIF GPS + fila vinculada 1,0]
    REG --> DB
    MIC --> REC[VoiceRecorder M4A] --> DB
    TXT --> DB

    SVC -- Terminar etapa --> FIN{Cierre de etapa}
    FIN --> STATS[Estadísticas y paradas core/geo] --> DB
    FIN --> LINK[LinkMediaWorker: MediaScanner + linkMedia por EXIF / recorrido]
    GAL --> LINK --> DB
    FIN --> BK[BackupWorker: trip.json comprimido] --> DB
    DB --> TR[TranscribeWorker: whisper local o reconocedor del sistema] --> DB
    FIN -. con red .-> ENR[EnrichPlacesWorker: Nominatim → Wikipedia → Wikidata → Overpass → ranking]
    ENR --> DB
    DB -. clave del usuario .-> LLM[ClaudeRewriter + RewriteGuard] --> DB

    DB --> TL[buildTimeline core/timeline]
    TL --> UI[Detalle del viaje: línea de tiempo · mapa · fotos (revisión) · lugares]
    MAP[(MapTiler / regiones sin red)] --> UI

    UI --> EXP{Exportar}
    EXP --> PRIV[PrivacyFilter: zonas de privacidad] --> STG[StagedExport reanudable con huella]
    STG --> ZIP[ZIP: LEEME · HTML/MD · GPX/KML/KMZ/GeoJSON · CSV · trip.json · media/audio · SRT]
    ZIP --> PC[(PC · Google Earth · My Maps)]
    ZIP --> IMP[ImportWorker en otro teléfono] --> DB

    UI --> PLAN[Planner core/videoplan ⇄ planner.py]
    PLAN --> MINI[RenderWorker + Media3: video 1 min / completo] --> MOV[(Movies/BitacoraViaje)]
    PLAN --> DESK[desktop-renderer: FFmpeg] --> MP4[(MP4 en la PC)]
    ZIP --> DESK
```

## Etapas

- **Entrada**: todo se captura en el teléfono y sin red. El GPS llega por
  `FusedLocationProvider` con el perfil de la etapa; la galería se consulta por rango de
  tiempo; la cámara propia escribe la foto con la posición del momento; las notas de voz se
  graban en M4A; los accesos rápidos (notificación, mosaicos, widget, accesos directos)
  disparan las mismas acciones que la pantalla de sesión.
- **Filtro y servicio**: `core/geo` descarta puntos imposibles (precisión, saltos, tiempo
  duplicado) y los acepta o marca como filtrados; `TrackingService` los guarda en lotes,
  mantiene el estado en vivo que observan la UI, el widget y los mosaicos, y aplica el reposo
  automático y el corte diario. Nunca se pierde un punto: el buffer se vacía al pausar,
  detener o destruir el servicio.
- **Cierre de etapa**: estadísticas y paradas (puro), vinculación de fotos y videos por EXIF o
  por el recorrido (con revisión manual de lo dudoso), respaldo del viaje, transcripción en
  cola y, con red, enriquecimiento de lugares con fuentes citadas (y reescritura con IA solo
  si el usuario cargó su clave).
- **Bitácora**: `buildTimeline` ordena puntos, pausas, paradas, medios, notas y lugares en
  eventos por etapa y día; la misma función alimenta la pantalla, el HTML/Markdown exportado
  y el planificador del video. El mapa usa MapTiler o las regiones descargadas.
- **Exportación**: el viaje pasa por el filtro de privacidad (zonas) y por el staging
  reanudable, y sale como un ZIP abierto que la PC, Google Earth, My Maps y otro teléfono
  (importación) pueden leer.
- **Video**: un planificador determinista (idéntico en Kotlin y Python) convierte la
  bitácora en escenas; el teléfono rinde con Media3 (1 minuto al cerrar la etapa, o el video
  completo con parámetros y plan editable) y la PC con FFmpeg a partir del paquete exportado.

## Puntos de decisión

| Punto | Opciones | Dónde se decide |
|---|---|---|
| Modo de registro | Completo / Económico (y paso automático a Económico con batería < 15 %) | Nueva sesión · Ajustes · `TrackingService` |
| Reposo automático | 3 / 5 / 10 / 15 min quieto | Ajustes · `SessionPolicy` |
| Vinculación de un medio | EXIF 1,0 · EXIF lejos 0,5 (revisión) · interpolado 0,8 · cercano 0,7·f · NONE (revisión) · manual | `linkMedia` (core/geo) · pantalla Revisar |
| Transcripción | whisper local (base/small) · reconocedor del sistema · automático | Ajustes · `TranscribeWorker` |
| Lugares | buscar o no · redondear coordenadas · usar en el video / descartar / editar / reescribir | Ajustes · pestaña Lugares |
| Exportar | medios · audios · puntos filtrados · zonas de privacidad · GPS en las copias · destino | Diálogo de exportación |
| Mapa sin red | zoom 12 / 14 · una región o varias por etapa | Diálogo "Usar sin red" · `OfflineMaps.plan` |
| Video | duración · formato · voces · lugares · mapa animado · clips · música · plan editable · teléfono o PC | Pantalla Crear video · `Planner` |
