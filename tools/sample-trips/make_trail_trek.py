# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Build the sample trek that follows a REAL OpenStreetMap trail (PLAN 3.6 acceptance).

    uv run tools/sample-trips/make_trail_trek.py

Reads the recorded Overpass answer used by the tests
(app/src/test/resources/enrichment/overpass_chalten_trails.json: way 31348131
"Sendero Madre e Hija", El Chaltén) and writes tools/sample-trips/trekking-sendero/
track.gpx: the trail's vertices walked at ~1.1 m/s with deterministic GPS jitter
(±8 m), one fix every 10 s. The synthetic trek of tools/sample-trips/trekking/ does
not follow any mapped path, so it can never receive a trail name; this one must.
"""

from __future__ import annotations

import json
import math
import random
from datetime import UTC, datetime, timedelta
from itertools import pairwise
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "app/src/test/resources/enrichment/overpass_chalten_trails.json"
OUT = ROOT / "tools/sample-trips/trekking-sendero/track.gpx"
SPEED_MPS = 1.1
STEP_S = 10
JITTER_M = 8.0


def haversine(lat1, lon1, lat2, lon2):
    r = 6_371_008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def main() -> int:
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    way = max(
        (e for e in data["elements"] if e.get("type") == "way"),
        key=lambda e: len(e.get("geometry", [])),
    )
    name = way["tags"]["name"]
    geom = [(g["lat"], g["lon"]) for g in way["geometry"]]
    rng = random.Random(31348131)
    t = datetime(2026, 3, 14, 12, 0, 0, tzinfo=UTC)
    pts = []
    # Resample along the trail at SPEED_MPS * STEP_S metres per fix.
    step_m = SPEED_MPS * STEP_S
    carry = 0.0
    for (lat1, lon1), (lat2, lon2) in pairwise(geom):
        seg = haversine(lat1, lon1, lat2, lon2)
        pos = -carry
        while pos + step_m <= seg:
            pos += step_m
            f = pos / seg
            lat = lat1 + (lat2 - lat1) * f
            lon = lon1 + (lon2 - lon1) * f
            dlat = rng.uniform(-JITTER_M, JITTER_M) / 111_320
            dlon = rng.uniform(-JITTER_M, JITTER_M) / (
                111_320 * math.cos(math.radians(lat))
            )
            pts.append((lat + dlat, lon + dlon, t))
            t += timedelta(seconds=STEP_S)
        carry = seg - pos if seg > pos else 0.0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write(
            '<gpx version="1.1" creator="make_trail_trek.py" '
            'xmlns="http://www.topografix.com/GPX/1/1">\n'
        )
        f.write(f"  <metadata><name>Trek de ejemplo sobre {name}</name></metadata>\n")
        f.write(f"  <trk><name>{name} (ida)</name><type>hiking</type><trkseg>\n")
        for lat, lon, ts in pts:
            f.write(
                f'    <trkpt lat="{lat:.7f}" lon="{lon:.7f}"><ele>500.0</ele>'
                f"<time>{ts.strftime('%Y-%m-%dT%H:%M:%SZ')}</time></trkpt>\n"
            )
        f.write("  </trkseg></trk>\n</gpx>\n")
    print(f"{OUT.relative_to(ROOT)}: {len(pts)} fixes along '{name}'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
