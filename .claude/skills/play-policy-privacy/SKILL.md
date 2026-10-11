---
name: play-policy-privacy
description: Permissions UX, Google Play policy compliance and privacy for Bitácora de Viaje — runtime permission flows with rationale screens, location prominent disclosure, foreground service type declarations, READ_MEDIA_IMAGES/VIDEO policy, Data safety form, API key and secret handling (local.properties, Secrets Gradle Plugin, Android Keystore-encrypted DataStore), privacy zones, backup rules. Use when adding permissions, handling keys, preparing a release, or touching user data flows.
---

# Permisos, políticas y privacidad

Especificación: `docs/DISENO.md` §15.

## Flujo de permisos (UX)
- **Uno a la vez, en contexto:** se pide cuando el usuario toca la función que lo necesita.
- Antes del diálogo del sistema, una **pantalla propia** que explica en dos líneas qué se pide y por qué, con los botones "Continuar" y "Ahora no".
- Si el usuario lo niega dos veces (`shouldShowRequestPermissionRationale` vuelve a `false`), ofrecer "Abrir ajustes" (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Degradación elegante** (la app nunca se bloquea):
  - Sin ubicación precisa (solo aproximada): avisar que el recorrido será impreciso; usar perfil `SAVER`.
  - Sin notificaciones (Android 13+): el servicio funciona, pero avisar que no verá el estado ni los atajos.
  - Sin fotos: no hay vinculación automática; queda la cámara integrada.
  - Sin micrófono: notas de texto en su lugar.

## Divulgación destacada de ubicación
Antes de pedir la ubicación por primera vez, una pantalla con un texto como:
> "Bitácora de Viaje usa tu ubicación para registrar el recorrido mientras una sesión está activa, incluso con la pantalla apagada. Los datos quedan en tu teléfono. Podés pausar o terminar el registro en cualquier momento desde la notificación."
Debe mencionar que se recolecta la ubicación, para qué y que ocurre con la pantalla apagada. Botones: "Aceptar y continuar" / "No, gracias".

## Google Play (si se publica)
- **targetSdk 36** (obligatorio para apps nuevas y actualizaciones desde el 31/08/2026).
- **Servicios en primer plano:** declarar cada tipo usado (`location`; luego `dataSync`/`mediaProcessing`, y `microphone` si se usa) en la Play Console, con descripción y video que muestre la funcionalidad.
- **Permisos de fotos y videos:** la política exige que `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` sean centrales para la app; las apps de uso ocasional deben usar el Photo Picker. Justificación: "La app encuentra automáticamente las fotos tomadas durante cada etapa del viaje y las ubica en el recorrido mediante sus metadatos; el selector de fotos del sistema elimina la ubicación de las imágenes". Plan B si lo rechazan: cámara integrada + Photo Picker con vinculación solo por hora.
- **`ACCESS_BACKGROUND_LOCATION`:** no declararlo (no hace falta y requiere un proceso de revisión adicional).
- **Data safety:** ubicación precisa (recolectada, no compartida, salvo coordenadas aproximadas a Wikipedia/OSM si el enriquecimiento está activo; declararlo), fotos y videos (accedidos, no recolectados por un servidor), audio (grabaciones locales; compartidas solo si se activa la transcripción en la nube). Cifrado en tránsito: sí (HTTPS). Borrado: el usuario puede borrar los viajes.
- **Política de privacidad pública** (URL) obligatoria si se publica; redactarla según lo de arriba.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` está restringido por la política: **no** usarlo; abrir los ajustes con `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.

## Claves y secretos
**Claves del desarrollador (build):**
- `local.properties` (en `.gitignore`): `MAPS_API_KEY=...`, `MAPTILER_KEY=...`.
- *Secrets Gradle Plugin* de Google (`com.google.android.libraries.mapsplatform.secrets-gradle-plugin`) → `BuildConfig` y `${MAPS_API_KEY}` en el manifiesto. Proveer `local.defaults.properties` (sí va al repositorio) con valores vacíos o falsos para que compile sin claves.
- Restringir la clave de Google Maps en Google Cloud Console por paquete + huella SHA-1 del certificado, y solo a "Maps SDK for Android".

**Claves del usuario (en tiempo de ejecución: LLM, transcripción en la nube, teselas):**
- Guardadas con DataStore + cifrado AES-GCM usando una clave del **Android Keystore** (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`). (La librería `security-crypto` de AndroidX fue deprecada: implementarlo directamente con el Keystore o con Tink.)
- Nunca loguearlas ni incluirlas en exportaciones o respaldos.

**Antes de cada commit:** `git grep -nE "(AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9]{20,}|api[_-]?key\s*=\s*['\"][^'\"]+)"` no debe encontrar nada. Opcional: hook `pre-commit` con gitleaks.

## Privacidad de los datos
- Sin servidor propio; sin analytics ni crash reporting de terceros por defecto (si se agregan, opt-in).
- **Zonas de privacidad:** el usuario define círculos (p.ej. su casa); al exportar, los puntos dentro se eliminan y el recorrido se corta, y las fotos dentro pierden la ubicación en el `trip.json` (el archivo original no se toca, pero se avisa que el EXIF del JPEG exportado conserva el GPS, con la opción "quitar GPS de las fotos exportadas", que trabaja sobre la copia).
- **Redondeo de coordenadas** para el enriquecimiento (opción, por defecto activado a ~1 km para la geocodificación de localidades; desactivado para los puntos de interés, donde la precisión importa; explicarlo en Ajustes).
- **Respaldos:** `res/xml/data_extraction_rules.xml` (API 31+) y `backup_rules.xml`: incluir la base y las preferencias (sin las claves cifradas: la clave del Keystore no viaja, así que no servirían), excluir `audio/`, `models/` y `exports/`.
- **Logs release:** sin coordenadas, sin textos de notas, sin rutas de archivos personales.
- **Borrar un viaje:** base + audios + exportaciones propias; **nunca** los medios de la galería del usuario (mostrar ese aviso en la confirmación).

## Checklist de release
- [ ] `targetSdk`/`compileSdk` 36; `minSdk` 26.
- [ ] R8/minify activado con reglas para kotlinx.serialization, Room, Hilt y whisper JNI.
- [ ] Sin claves en el repositorio; claves restringidas.
- [ ] Textos de divulgación y pantallas de permisos revisados.
- [ ] Declaraciones de la Play Console: servicios en primer plano, fotos y videos, Data safety, política de privacidad.
- [ ] Atribuciones (OSM, Wikipedia, proveedor de teselas, licencias de librerías en "Acerca de").
- [ ] Firma con un keystore propio guardado fuera del repositorio (y respaldado: si se pierde, no se puede actualizar la app).
