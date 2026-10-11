# Bitácora de Viaje — paquete de arranque para Claude Code

Este paquete contiene la especificación y las skills para que una sesión de **Claude Code en Linux** construya la app.

## Contenido

```
bitacora-viaje/
├── README.md                 ← este archivo
├── CLAUDE.md                 ← instrucciones del proyecto (Claude Code lo lee automáticamente)
├── docs/
│   ├── DISENO.md             ← documento de diseño completo (la especificación)
│   └── PROGRESS.md           ← registro de avance (lo mantiene Claude Code)
└── .claude/skills/           ← skills técnicas, una carpeta por área
    ├── android-linux-env/
    ├── android-app-architecture/
    ├── location-tracking/
    ├── geo-algorithms/
    ├── media-linking/
    ├── voice-journal/
    ├── trip-export/
    ├── place-enrichment/
    ├── trip-video/
    ├── android-testing/
    └── play-policy-privacy/
```

## Cómo usarlo

1. Descomprimí el ZIP en tu máquina Linux. La carpeta `bitacora-viaje/` será la raíz del proyecto.
2. Entrá a la carpeta, inicializá git y abrí Claude Code:
   ```bash
   cd bitacora-viaje
   git init && git add . && git commit -m "Initial spec and skills"
   claude
   ```
3. Primer mensaje sugerido:
   > Leé CLAUDE.md y docs/DISENO.md. Después cargá la skill android-linux-env, verificá mi entorno y empezá con la Fase 0. Antes de instalar algo, decime qué vas a instalar.

4. Al terminar cada fase, Claude Code te mostrará la evidencia (pruebas y capturas). Revisala y pedile que siga con la siguiente.

## Qué vas a necesitar tener a mano

| Qué | Para qué | Cuándo |
|---|---|---|
| Linux con KVM habilitado (o un teléfono Android con depuración USB) | Emulador / pruebas | Fase 0 |
| ~15 GB libres de disco | SDK, emulador, Gradle | Fase 0 |
| Un teléfono Android real | Pruebas en la calle (batería, GPS real) | Fase 1 en adelante |
| Clave de MapTiler (gratuita) **o** clave de Google Maps (requiere facturación en Google Cloud) | Mapa en la app y en el video | Fase 1 |
| Clave de API de un LLM (opcional) | Reescribir notas de lugares | Fase 4 |
| Proyecto de Google Cloud con OAuth (opcional) | Importar/subir a Google Fotos | Fase 5 |

## Decisiones pendientes (ver `docs/DISENO.md` §20)
- Nombre definitivo y `applicationId`.
- Proveedor de mapa por defecto (recomendado: MapLibre + MapTiler).
- Si se usará transcripción en la nube.
- Si se publicará en Google Play o será de uso personal.
