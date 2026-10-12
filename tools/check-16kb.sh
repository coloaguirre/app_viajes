#!/usr/bin/env bash
# Verify an APK is 16 KB page-size compatible: every uncompressed native
# library must be aligned to 16 KB and ELF segments must use 16 KB alignment.
# Google Play requires this for new apps/updates since Nov 2025; Android 16+
# warns on sideloaded apps that are not compatible. Exit 1 on any failure.
#   ./tools/check-16kb.sh app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
APK="${1:?uso: check-16kb.sh <apk>}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ZIPALIGN=$(ls -d "$SDK"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1)
[ -x "$ZIPALIGN" ] || { echo "zipalign no encontrado en $SDK/build-tools"; exit 2; }

echo "[16kb] zipalign -c -P 16 -v 4 $APK"
"$ZIPALIGN" -c -P 16 -v 4 "$APK" >/dev/null || { echo "[16kb] FALLA: alineación de zip"; exit 1; }

libs=$(unzip -Z1 "$APK" 'lib/*/*.so' 2>/dev/null || true)
if [ -z "$libs" ]; then
  echo "[16kb] OK: el APK no contiene librerías nativas"
  exit 0
fi
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$APK" 'lib/*/*.so' -d "$tmp"
READELF=$(command -v readelf || ls "$SDK"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf 2>/dev/null | tail -1)
[ -n "$READELF" ] || { echo "[16kb] readelf no disponible: instalar binutils o el NDK"; exit 2; }
rc=0
for so in $libs; do
  case "$so" in lib/arm64-v8a/*|lib/x86_64/*) ;; *) continue ;; esac
  align=$("$READELF" -lW "$tmp/$so" | awk '/LOAD/{print $NF}' | sort -u)
  bad=$(echo "$align" | grep -vE '^0x(4000|8000|10000)$' || true)
  if [ -n "$bad" ]; then echo "[16kb] FALLA: $so LOAD align $bad"; rc=1; else echo "[16kb] ok: $so ($align)"; fi
done
[ $rc -eq 0 ] && echo "[16kb] OK: todas las librerías de 64 bits usan alineación de 16 KB"
exit $rc
