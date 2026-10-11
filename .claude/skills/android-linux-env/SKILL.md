---
name: android-linux-env
description: Set up and troubleshoot the Android build environment on Linux for Bitácora de Viaje — JDK, SDK command-line tools, emulator with KVM, adb, Gradle, NDK, Python/ffmpeg. Use before the first build, when a build or emulator fails, or when connecting a physical phone.
---

# Entorno Android en Linux

## Objetivo
Dejar una máquina Linux capaz de compilar, instalar y probar la app **sin Android Studio**, solo con la línea de comandos. Android Studio es opcional para el usuario.

## 1. Verificar antes de instalar
Ejecutá este diagnóstico y mostrale el resultado al usuario **antes** de instalar nada:

```bash
uname -a; cat /etc/os-release | head -3
java -version 2>&1 | head -1
echo "ANDROID_HOME=$ANDROID_HOME"
command -v sdkmanager adb emulator
ls -l /dev/kvm 2>/dev/null && groups | grep -q kvm && echo "KVM OK (grupo kvm)" || echo "KVM: revisar"
egrep -c '(vmx|svm)' /proc/cpuinfo   # >0 = virtualización por hardware
free -h; df -h ~
python3 --version; ffmpeg -version 2>/dev/null | head -1
```

## 2. Instalación (Debian/Ubuntu; adaptar en Fedora/Arch)

```bash
# JDK 17 (verificar en la doc del Android Gradle Plugin si la versión actual exige 21)
sudo apt-get install -y openjdk-17-jdk unzip curl git python3 python3-venv python3-pip ffmpeg

# KVM para el emulador
sudo apt-get install -y qemu-kvm
sudo usermod -aG kvm "$USER"    # requiere cerrar y abrir sesión

# SDK command-line tools: descargar el ZIP "Command line tools only" desde
# https://developer.android.com/studio#command-line-tools-only  (verificar la URL vigente)
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
# descomprimir y mover a $ANDROID_HOME/cmdline-tools/latest
```

Agregar a `~/.bashrc` (o `~/.zshrc`):
```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Paquetes del SDK (consultar las versiones disponibles con `sdkmanager --list`):
```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "emulator" \
           "system-images;android-36;google_apis;x86_64"
# Fase 3 (whisper.cpp):
sdkmanager "ndk;<versión estable>" "cmake;<versión>"
```

> **Importante:** la imagen tiene que ser `google_apis` (o `google_apis_playstore`). Las imágenes AOSP puras no traen Google Play services, y sin ellas **FusedLocationProvider no funciona**.

## 3. Emulador

```bash
avdmanager create avd -n bitacora36 -k "system-images;android-36;google_apis;x86_64" -d pixel_7
# Con ventana (si hay escritorio):
emulator -avd bitacora36 &
# Sin ventana (servidor / sesión remota):
emulator -avd bitacora36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
```

- Ubicación simulada: `adb emu geo fix <lon> <lat> [alt]`. Ojo: primero longitud, después latitud.
- Micrófono en el emulador: requiere `-allow-host-audio` y que el host tenga audio. Sin audio, probá las notas de voz con un archivo inyectado (ver skill `android-testing`).
- Cámara: el emulador ofrece una escena virtual (`-camera-back virtualscene`).

## 4. Dispositivo físico
1. En el teléfono: Opciones de desarrollador → Depuración USB (o Depuración inalámbrica).
2. USB: `adb devices` debe listarlo como `device`, no como `unauthorized`. Si figura como `unauthorized`, aceptar el diálogo en el teléfono.
3. Inalámbrica: `adb pair IP:PUERTO` (con el código que muestra el teléfono) y después `adb connect IP:PUERTO`.
4. Si hay emulador y teléfono a la vez, usar `adb -s <serial> ...` o `ANDROID_SERIAL=<serial>`.

## 5. Gradle
- Siempre `./gradlew`; nunca un Gradle global.
- En `gradle.properties`:
  ```
  org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
  org.gradle.parallel=true
  org.gradle.caching=true
  org.gradle.configuration-cache=true
  android.useAndroidX=true
  kotlin.code.style=official
  ```
- `local.properties` (no va al repositorio): `sdk.dir=/home/<user>/Android/Sdk`, `MAPS_API_KEY=...`, `MAPTILER_KEY=...`.

## 6. Verificar la UI sin ver la pantalla
```bash
adb exec-out screencap -p > /tmp/s.png         # leer /tmp/s.png con la herramienta de lectura de imágenes
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
adb shell input tap X Y; adb shell input text "hola"; adb shell input keyevent KEYCODE_BACK
adb shell am start -n com.bitacoraviaje.app/.MainActivity
adb shell pm grant com.bitacoraviaje.app android.permission.ACCESS_FINE_LOCATION   # para pruebas
```

## 7. Problemas comunes

| Síntoma | Causa probable | Solución |
|---|---|---|
| `emulator: ERROR: x86_64 emulation currently requires hardware acceleration` | Sin KVM | Habilitar la virtualización en BIOS, instalar KVM, agregar el usuario al grupo `kvm` |
| `SDK location not found` | Falta `local.properties` | Crear con `sdk.dir=` |
| `Unsupported class file major version` | JDK incorrecto | Usar el JDK que pide el AGP; `JAVA_HOME` |
| Gradle se queda sin memoria | `jvmargs` bajo | Subir `-Xmx` |
| La ubicación nunca llega en el emulador | Imagen sin Google APIs, o falta `geo fix` | Usar la imagen `google_apis`; enviar `geo fix` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Firma distinta | `adb uninstall com.bitacoraviaje.app` |
| `adb: no permissions` en USB | Reglas udev | Instalar el paquete `android-sdk-platform-tools-common` o agregar reglas udev |

## 8. Python (renderizador y herramientas)
```bash
cd desktop-renderer
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
```
