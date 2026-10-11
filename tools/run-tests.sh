#!/usr/bin/env bash
# Project test suite. Run by the git guard hook (H3) before any push/merge
# to dev/main, and manually before opening a MR.
#   - Gradle JVM tests when the wrapper exists (Phase 0 onwards).
#   - pytest for each Python subproject with its own pyproject.toml.
# Exits non-zero on the first failure. Prints what it ran so a green run
# with nothing to test is visible, not silent.
set -euo pipefail
cd "$(dirname "$0")/.."

ran=0
if [ -x ./gradlew ]; then
  echo "[run-tests] ./gradlew test"
  ./gradlew test --quiet
  ran=1
fi

for proj in desktop-renderer tools/gpx-replay; do
  if [ -f "$proj/pyproject.toml" ]; then
    echo "[run-tests] uv run pytest -q ($proj)"
    (cd "$proj" && uv run pytest -q)
    ran=1
  fi
done

if [ "$ran" -eq 0 ]; then
  echo "[run-tests] nothing to test yet (no gradlew, no Python subprojects)"
fi
