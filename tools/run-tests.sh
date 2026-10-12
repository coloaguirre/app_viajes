#!/usr/bin/env bash
# Project quality gate. Run by the Claude Code guard hook before any push or
# merge to the environment branches, and manually before opening a MR. It
# mirrors .github/workflows/ci.yml so the local gate and CI agree:
#   - Gradle lint + JVM tests + debug APK + 16 KB page-size check.
#   - ruff + pytest for each Python subproject with its own pyproject.toml.
# Exits non-zero on the first failure. Prints what it ran so a green run
# with nothing to test is visible, not silent.
set -euo pipefail
cd "$(dirname "$0")/.."

# Hooks run with a minimal environment: resolve the toolchain the same way
# tools/setup-linux.sh installs it (user space), unless already configured.
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"

ran=0
if [ -x ./gradlew ]; then
  echo "[run-tests] ./gradlew lint test assembleDebug"
  # --no-daemon: the gate runs from a hook; a resident daemon starves the emulator.
  ./gradlew --no-daemon lint test assembleDebug --quiet
  echo "[run-tests] tools/check-16kb.sh"
  ./tools/check-16kb.sh app/build/outputs/apk/debug/app-debug.apk
  ran=1
fi

for proj in desktop-renderer tools/gpx-replay; do
  if [ -f "$proj/pyproject.toml" ]; then
    echo "[run-tests] ruff + pytest ($proj)"
    (cd "$proj" && uv run --with ruff ruff check . && uv run pytest -q)
    ran=1
  fi
done

if [ "$ran" -eq 0 ]; then
  echo "[run-tests] nothing to test yet (no gradlew, no Python subprojects)"
fi
