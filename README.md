# Bitácora de Viaje

App Android (Kotlin + Jetpack Compose) que registra un viaje: recorrido
GPS, fotos y videos vinculados al lugar y la hora, notas de voz
transcriptas y notas de texto. Arma una bitácora cronológica, la exporta
en formatos abiertos y genera un video resumen. Incluye un renderizador
de video de escritorio en Python.

Proyecto personal.

- Especificación: `docs/incoming/DISENO.md`
- Avance: `docs/PROGRESS.md`
- Instrucciones para Claude Code: `CLAUDE.md`
- Skills técnicas por área: `.claude/skills/`

## Requisitos (ver `docs/incoming/README-paquete-arranque.md`)

Linux con KVM (o teléfono Android con depuración USB), ~15 GB libres,
JDK 17, Android SDK command-line tools, Python 3.11+ con `uv`, ffmpeg.
El entorno se verifica e instala con la skill `android-linux-env`
en la Fase 0.
