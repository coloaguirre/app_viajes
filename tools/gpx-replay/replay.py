# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Replay a GPX track into the Android emulator as GPS fixes.

    uv run tools/gpx-replay/replay.py tools/sample-trips/trekking/track.gpx --speed 20

Sends `geo fix <lon> <lat> <alt>` through the emulator console via `adb emu`,
honouring the GPX timestamps divided by --speed. Needs a running emulator and
`adb` on PATH (or ANDROID_HOME set).

Choosing --speed: the app stamps each fix with the emulator's real clock, so an
accelerated replay multiplies the implied speed. The point filter rejects fixes
faster than the profile's maximum (walk 4 m/s, vehicle 70 m/s), which is exactly
what it must do with real GPS. Keep `speed * real_speed < max`: a 1.1 m/s hike
tolerates at most 3x; a 20 m/s road trip tolerates 3x too. Measured 2026-10-12:
an 8x hike replay was rejected almost entirely as IMPOSSIBLE_JUMP.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime

NS = {"g": "http://www.topografix.com/GPX/1/1"}


def adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Android/Sdk")
    return os.path.join(home, "platform-tools", "adb")


def read_gpx(path: str):
    root = ET.parse(path).getroot()
    pts = []
    for p in root.iterfind(".//g:trkpt", NS):
        t = p.find("g:time", NS)
        e = p.find("g:ele", NS)
        ts = (
            datetime.fromisoformat(t.text.replace("Z", "+00:00")).timestamp()
            if t is not None
            else None
        )
        pts.append(
            (
                float(p.get("lon")),
                float(p.get("lat")),
                float(e.text) if e is not None else 0.0,
                ts,
            )
        )
    return pts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("gpx")
    ap.add_argument(
        "--speed", type=float, default=10.0, help="time acceleration factor"
    )
    ap.add_argument(
        "--serial", default=None, help="adb -s <serial> (default: first emulator)"
    )
    ap.add_argument(
        "--max-interval",
        type=float,
        default=30.0,
        help="cap seconds of real wait between fixes",
    )
    ap.add_argument(
        "--limit", type=int, default=None, help="replay only the first N fixes"
    )
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    pts = read_gpx(a.gpx)
    if a.limit:
        pts = pts[: a.limit]
    if not pts:
        print("no trkpt in GPX", file=sys.stderr)
        return 2
    adb = [adb_path()] + (["-s", a.serial] if a.serial else [])
    print(f"replaying {len(pts)} fixes at {a.speed}x", file=sys.stderr)
    t_prev = None
    started = time.monotonic()
    for i, (lon, lat, alt, ts) in enumerate(pts):
        if t_prev is not None and ts is not None:
            wait = min((ts - t_prev) / a.speed, a.max_interval)
            if wait > 0:
                time.sleep(wait)
        t_prev = ts
        cmd = adb + ["emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}", f"{alt:.1f}"]
        if a.dry_run:
            print(" ".join(cmd))
        else:
            r = subprocess.run(cmd, capture_output=True, text=True)
            if r.returncode != 0 or "KO" in r.stdout:
                print(
                    f"fix {i} failed: {r.stdout.strip()} {r.stderr.strip()}",
                    file=sys.stderr,
                )
                return 1
        if i % 100 == 0:
            print(
                f"  {i}/{len(pts)} ({time.monotonic() - started:.0f}s)", file=sys.stderr
            )
    print(f"done in {time.monotonic() - started:.0f}s", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
