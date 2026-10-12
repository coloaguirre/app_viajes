#!/usr/bin/env bash
# Verify or install the Android build environment on Linux (WSL2 friendly),
# entirely in user space: no sudo needed except for the KVM group (printed
# at the end if missing). Idempotent: re-run to verify.
#
#   ./tools/setup-linux.sh          # verify; asks before installing each missing piece
#   ./tools/setup-linux.sh --yes    # verify + install without asking (CI, automation)
#   ./tools/setup-linux.sh --check  # verify only, exit 1 if something is missing
#
# Every download is pinned to an exact file and verified with SHA-256.
# Pinned versions (update deliberately, record in docs/PROGRESS.md):
JDK_MAJOR=17
JDK_VERSION="17.0.20.1+1"
JDK_TARBALL="OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/$JDK_TARBALL"
JDK_SHA256="3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e"   # published by Adoptium
CMDLINE_TOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
PLATFORM="android-36"          # emulator image
COMPILE_PLATFORM="android-37.0" # compileSdk (current AndroidX requires API 37)
BUILD_TOOLS="36.0.0"
SYSTEM_IMAGE="system-images;${PLATFORM};google_apis;x86_64"
AVD_NAME="bitacora36"
FFMPEG_VERSION="7.0.2"
FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
# The publisher only offers an MD5 and a floating "release" URL; this SHA-256 was computed
# locally on 2026-10-12 for ffmpeg-7.0.2-amd64-static (trust on first use). If the
# publisher rolls the release, the check fails loudly instead of installing another version.
FFMPEG_SHA256="c2201adb4146f65f879226e77cc60407ea8ecc4c51bc426a4d83e3f71b7fd0a6"

set -euo pipefail
CHECK_ONLY=0; AUTO_YES=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --yes|-y) AUTO_YES=1 ;;
    *) echo "uso: $0 [--check] [--yes]"; exit 2 ;;
  esac
done
missing=0
OPT="$HOME/.local/opt"; BIN="$HOME/.local/bin"
export JAVA_HOME="${JAVA_HOME:-$OPT/jdk$JDK_MAJOR}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$BIN:$PATH"
mkdir -p "$OPT" "$BIN"

ok()   { printf '  [ok]   %s\n' "$*"; }
# Ask before installing (PLAN 0.1: "verifica o instala avisando antes"); --yes skips.
confirm() {
  [ $AUTO_YES -eq 1 ] && return 0
  printf '  Instalar %s? [s/N] ' "$1"; read -r ans
  case "$ans" in s|S|y|Y) return 0 ;; *) echo "  omitido"; return 1 ;; esac
}
fetch() { # fetch <url> <dest> <sha256>
  curl -sSfL --retry 3 -o "$2" "$1"
  echo "$3  $2" | sha256sum -c - >/dev/null || { echo "  CHECKSUM INVÁLIDO: $2"; rm -f "$2"; exit 1; }
}
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
elif confirm "JDK Temurin $JDK_VERSION en $OPT (190 MB)"; then
  fetch "$JDK_URL" "$OPT/$JDK_TARBALL" "$JDK_SHA256"
  dir=$(tar -tzf "$OPT/$JDK_TARBALL" | head -1 | cut -d/ -f1)
  tar -xzf "$OPT/$JDK_TARBALL" -C "$OPT" && rm "$OPT/$JDK_TARBALL"
  ln -sfn "$OPT/$dir" "$OPT/jdk$JDK_MAJOR"
  ok "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
else missing=1; fi

step "ffmpeg (build estático, user space)"
if command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null; then ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"
elif [ $CHECK_ONLY -eq 1 ]; then miss "ffmpeg/ffprobe"
elif confirm "ffmpeg $FFMPEG_VERSION estático en $OPT (42 MB)"; then
  fetch "$FFMPEG_URL" "$OPT/ffmpeg.tar.xz" "$FFMPEG_SHA256"
  dir=$(tar -tJf "$OPT/ffmpeg.tar.xz" | head -1 | cut -d/ -f1)
  case "$dir" in *"$FFMPEG_VERSION"*) ;; *) echo "  versión inesperada: $dir"; exit 1 ;; esac
  tar -xJf "$OPT/ffmpeg.tar.xz" -C "$OPT" && rm "$OPT/ffmpeg.tar.xz"
  ln -sf "$OPT/$dir/ffmpeg" "$BIN/ffmpeg"; ln -sf "$OPT/$dir/ffprobe" "$BIN/ffprobe"
  ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"
else missing=1; fi

step "Android SDK command-line tools"
if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  ok "cmdline-tools $(grep Pkg.Revision "$ANDROID_HOME/cmdline-tools/latest/source.properties" | cut -d= -f2)"
elif [ $CHECK_ONLY -eq 1 ]; then miss "sdkmanager"
elif confirm "Android command-line tools en $ANDROID_HOME (180 MB)"; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"; tmp="$ANDROID_HOME/cmdline-tools/$CMDLINE_TOOLS_ZIP"
  fetch "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" "$tmp" "$CMDLINE_TOOLS_SHA256"
  (cd "$ANDROID_HOME/cmdline-tools" && unzip -q "$CMDLINE_TOOLS_ZIP" && mv cmdline-tools latest && rm "$CMDLINE_TOOLS_ZIP")
  ok "cmdline-tools instaladas"
else missing=1; fi

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
    elif confirm "paquetes del SDK (acepta sus licencias): ${to_install[*]} (hasta 4 GB)"; then
      yes | sdkmanager --licenses >/dev/null 2>&1 || true
      sdkmanager --install "${to_install[@]}" >/dev/null
      for p in "${to_install[@]}"; do ok "$p"; done
    else missing=1; fi
  fi
fi

step "AVD $AVD_NAME"
if command -v avdmanager >/dev/null && avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then ok "AVD existe"
elif [ $CHECK_ONLY -eq 1 ]; then miss "AVD $AVD_NAME"
elif command -v avdmanager >/dev/null && confirm "AVD $AVD_NAME (pixel_7, $SYSTEM_IMAGE)"; then
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_7 >/dev/null
  # RAM moderada: la máquina de desarrollo tiene 7 GB compartidos con Gradle.
  cfg="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
  if grep -q '^hw.ramSize=' "$cfg"; then sed -i 's/^hw.ramSize=.*/hw.ramSize=2048/' "$cfg"; else echo "hw.ramSize=2048" >> "$cfg"; fi
  grep -q '^hw.keyboard' "$cfg" || echo "hw.keyboard=yes" >> "$cfg"
  ok "AVD creado (2 GB RAM, pixel_7)"
else missing=1; fi

step "Variables de entorno"
if grep -q 'ANDROID_HOME' "$HOME/.bashrc"; then ok "~/.bashrc exporta JAVA_HOME/ANDROID_HOME/PATH"
else
  if [ $CHECK_ONLY -eq 1 ]; then miss "~/.bashrc sin ANDROID_HOME"
  elif confirm "agregar JAVA_HOME/ANDROID_HOME/PATH a ~/.bashrc"; then
    cat >> "$HOME/.bashrc" <<BRC

# --- Android dev (app_viajes) ---
export JAVA_HOME="\$HOME/.local/opt/jdk$JDK_MAJOR"
export ANDROID_HOME="\$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
BRC
    ok "~/.bashrc actualizado (abrir una shell nueva)"
  else missing=1; fi
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
