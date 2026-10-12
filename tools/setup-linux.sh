#!/usr/bin/env bash
# Verify or install the Android build environment on Linux (WSL2 friendly),
# entirely in user space: no sudo needed except for the KVM group (printed
# at the end if missing). Idempotent: re-run to verify.
#
#   ./tools/setup-linux.sh          # verify + install what is missing
#   ./tools/setup-linux.sh --check  # verify only, exit 1 if something is missing
#
# Pinned versions (update deliberately, record in docs/PROGRESS.md):
JDK_MAJOR=17
CMDLINE_TOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
PLATFORM="android-36"          # emulator image
COMPILE_PLATFORM="android-37.0" # compileSdk (current AndroidX requires API 37)
BUILD_TOOLS="36.0.0"
SYSTEM_IMAGE="system-images;${PLATFORM};google_apis;x86_64"
AVD_NAME="bitacora36"
FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"

set -euo pipefail
CHECK_ONLY=0; [ "${1:-}" = "--check" ] && CHECK_ONLY=1
missing=0
OPT="$HOME/.local/opt"; BIN="$HOME/.local/bin"
export JAVA_HOME="${JAVA_HOME:-$OPT/jdk$JDK_MAJOR}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$BIN:$PATH"
mkdir -p "$OPT" "$BIN"

ok()   { printf '  [ok]   %s\n' "$*"; }
miss() { printf '  [FALTA] %s\n' "$*"; missing=1; }
step() { printf '\n== %s\n' "$*"; }

step "Sistema"
echo "  $(uname -sr) · $(. /etc/os-release && echo "$PRETTY_NAME")"
echo "  CPUs: $(nproc) · RAM: $(free -g | awk '/Mem/{print $2}') GB · disco libre en ~: $(df -h ~ | awk 'NR==2{print $4}')"
if [ -e /dev/kvm ]; then ok "/dev/kvm presente"; else miss "/dev/kvm ausente: habilitar virtualización anidada"; fi
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then ok "acceso a /dev/kvm"; else KVM_GROUP_MISSING=1; miss "sin acceso a /dev/kvm (falta grupo kvm)"; fi

step "JDK $JDK_MAJOR (Temurin, user space)"
if [ -x "$JAVA_HOME/bin/java" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q "\"$JDK_MAJOR\."; then
  ok "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
elif [ $CHECK_ONLY -eq 1 ]; then miss "JDK $JDK_MAJOR en $JAVA_HOME"
else
  echo "  instalando JDK $JDK_MAJOR en $OPT ..."
  curl -sSL -o "$OPT/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  dir=$(tar -tzf "$OPT/jdk.tar.gz" | head -1 | cut -d/ -f1)
  tar -xzf "$OPT/jdk.tar.gz" -C "$OPT" && rm "$OPT/jdk.tar.gz"
  ln -sfn "$OPT/$dir" "$OPT/jdk$JDK_MAJOR"
  ok "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
fi

step "ffmpeg (build estático, user space)"
if command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null; then ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"
elif [ $CHECK_ONLY -eq 1 ]; then miss "ffmpeg/ffprobe"
else
  curl -sSL -o "$OPT/ffmpeg.tar.xz" "$FFMPEG_URL"
  dir=$(tar -tJf "$OPT/ffmpeg.tar.xz" | head -1 | cut -d/ -f1)
  tar -xJf "$OPT/ffmpeg.tar.xz" -C "$OPT" && rm "$OPT/ffmpeg.tar.xz"
  ln -sf "$OPT/$dir/ffmpeg" "$BIN/ffmpeg"; ln -sf "$OPT/$dir/ffprobe" "$BIN/ffprobe"
  ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"
fi

step "Android SDK command-line tools"
if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  ok "cmdline-tools $(grep Pkg.Revision "$ANDROID_HOME/cmdline-tools/latest/source.properties" | cut -d= -f2)"
elif [ $CHECK_ONLY -eq 1 ]; then miss "sdkmanager"
else
  mkdir -p "$ANDROID_HOME/cmdline-tools"; tmp="$ANDROID_HOME/cmdline-tools/$CMDLINE_TOOLS_ZIP"
  curl -sSL -o "$tmp" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  echo "$CMDLINE_TOOLS_SHA256  $tmp" | sha256sum -c -
  (cd "$ANDROID_HOME/cmdline-tools" && unzip -q "$CMDLINE_TOOLS_ZIP" && mv cmdline-tools latest && rm "$CMDLINE_TOOLS_ZIP")
  ok "cmdline-tools instaladas"
fi

step "Paquetes del SDK"
pkgs=("platform-tools" "platforms;$PLATFORM" "platforms;$COMPILE_PLATFORM" "build-tools;$BUILD_TOOLS" "emulator" "$SYSTEM_IMAGE")
if command -v sdkmanager >/dev/null; then
  installed=$(sdkmanager --list_installed 2>/dev/null || true)
  to_install=()
  for p in "${pkgs[@]}"; do
    if echo "$installed" | grep -q "^  $p "; then ok "$p"; else to_install+=("$p"); fi
  done
  if [ ${#to_install[@]} -gt 0 ]; then
    if [ $CHECK_ONLY -eq 1 ]; then for p in "${to_install[@]}"; do miss "$p"; done
    else
      yes | sdkmanager --licenses >/dev/null 2>&1 || true
      echo "  instalando: ${to_install[*]} ..."
      sdkmanager --install "${to_install[@]}" >/dev/null
      for p in "${to_install[@]}"; do ok "$p"; done
    fi
  fi
fi

step "AVD $AVD_NAME"
if command -v avdmanager >/dev/null && avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then ok "AVD existe"
elif [ $CHECK_ONLY -eq 1 ]; then miss "AVD $AVD_NAME"
elif command -v avdmanager >/dev/null; then
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_7 >/dev/null
  # RAM moderada: la máquina de desarrollo tiene 7 GB compartidos con Gradle.
  cfg="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
  sed -i 's/^hw.ramSize=.*/hw.ramSize=2048/' "$cfg" 2>/dev/null || echo "hw.ramSize=2048" >> "$cfg"
  grep -q '^hw.gpu.enabled' "$cfg" || echo "hw.gpu.enabled=yes" >> "$cfg"
  grep -q '^hw.keyboard' "$cfg" || echo "hw.keyboard=yes" >> "$cfg"
  ok "AVD creado (2 GB RAM, pixel_7)"
fi

step "Variables de entorno"
if grep -q 'ANDROID_HOME' "$HOME/.bashrc"; then ok "~/.bashrc exporta JAVA_HOME/ANDROID_HOME/PATH"
else
  if [ $CHECK_ONLY -eq 1 ]; then miss "~/.bashrc sin ANDROID_HOME"
  else
    cat >> "$HOME/.bashrc" <<BRC

# --- Android dev (app_viajes) ---
export JAVA_HOME="\$HOME/.local/opt/jdk$JDK_MAJOR"
export ANDROID_HOME="\$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
BRC
    ok "~/.bashrc actualizado (abrir una shell nueva)"
  fi
fi

step "Python (renderizador y herramientas)"
command -v uv >/dev/null && ok "$(uv --version)" || miss "uv (https://docs.astral.sh/uv/)"
command -v python3 >/dev/null && ok "$(python3 --version)" || miss "python3"

step "Resumen"
if [ "${KVM_GROUP_MISSING:-0}" = "1" ]; then
  cat <<MSG
  Para que el emulador use KVM hace falta pertenecer al grupo kvm (requiere sudo, una sola vez):
      sudo usermod -aG kvm "\$USER"
  y abrir una shell nueva (en WSL: cerrar la terminal y abrir otra, o 'wsl --shutdown').
MSG
fi
if [ $missing -eq 0 ]; then echo "  Entorno completo."; exit 0; else echo "  Faltan ítems (ver [FALTA])."; exit 1; fi
