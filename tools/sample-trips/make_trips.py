# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Generate deterministic synthetic sample trips as GPX 1.1 plus a reference.json
with independently computed ground truth (distance, stops, elevation).

Run:  uv run tools/sample-trips/make_trips.py
The GPX files are committed so JVM tests and the emulator replay do not need
Python. Re-run only when the generator changes; the output is reproducible
(fixed seed, fixed start time).
"""

from __future__ import annotations

import json
import math
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

R = 6_371_008.8
ROOT = Path(__file__).resolve().parent
START = datetime(2026, 3, 14, 9, 0, 0, tzinfo=timezone.utc)


def haversine(lat1, lon1, lat2, lon2):
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (
        math.sin(dlat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(dlon / 2) ** 2
    )
    return 2 * R * math.asin(min(1.0, math.sqrt(a)))


def step(lat, lon, dist_m, bearing_deg):
    """Move dist_m along bearing on a sphere."""
    br = math.radians(bearing_deg)
    d = dist_m / R
    la1, lo1 = math.radians(lat), math.radians(lon)
    la2 = math.asin(
        math.sin(la1) * math.cos(d) + math.cos(la1) * math.sin(d) * math.cos(br)
    )
    lo2 = lo1 + math.atan2(
        math.sin(br) * math.sin(d) * math.cos(la1),
        math.cos(d) - math.sin(la1) * math.sin(la2),
    )
    return math.degrees(la2), math.degrees(lo2)


class Drift:
    """AR(1) positional noise in metres: GPS error drifts slowly instead of jumping
    independently each fix, which is what keeps implied speeds realistic."""

    def __init__(self, rnd: random.Random, sigma_m: float, rho: float = 0.98):
        self.rnd, self.sigma, self.rho = rnd, sigma_m, rho
        self.x = self.y = 0.0

    def next(self):
        inn = self.sigma * math.sqrt(1 - self.rho**2)
        self.x = self.rho * self.x + self.rnd.gauss(0, inn)
        self.y = self.rho * self.y + self.rnd.gauss(0, inn)
        return self.x, self.y


def jitter(lat, lon, dx_m, dy_m):
    return lat + dy_m / 111_320, lon + dx_m / (111_320 * math.cos(math.radians(lat)))


def write_gpx(path: Path, name: str, pts):
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="bitacora-viaje sample generator" xmlns="http://www.topografix.com/GPX/1/1">',
        f"  <trk><name>{name}</name><trkseg>",
    ]
    for p in pts:
        t = p["t"].strftime("%Y-%m-%dT%H:%M:%SZ")
        lines.append(
            f'    <trkpt lat="{p["lat"]:.7f}" lon="{p["lon"]:.7f}"><ele>{p["ele"]:.1f}</ele><time>{t}</time></trkpt>'
        )
    lines += ["  </trkseg></trk>", "</gpx>", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def trekking():
    """Out-and-back mountain hike: 3 km up with 300 m gain, 20 min stop at the top,
    3 km down. 1 fix every 5 s at ~1.1 m/s, gaussian jitter 3 m. Ground truth is the
    ideal path (jitter adds <1%)."""
    rnd = random.Random(42)
    drift = Drift(rnd, sigma_m=2.0)
    lat, lon, ele = -49.2731, -72.8853, 400.0
    pts, truth_dist, truth_gain = [], 0.0, 0.0
    t = START
    heading = 20.0
    dist_per_fix = 1.1 * 5
    n_up = int(3000 / dist_per_fix)
    gain_per_fix = 300.0 / n_up
    stop_center = None
    for phase in ("up", "stop", "down"):
        n = n_up if phase != "stop" else int(20 * 60 / 5)
        for i in range(n):
            if phase == "stop":
                dx, dy = drift.next()
                jlat, jlon = jitter(stop_center[0], stop_center[1], dx, dy)
                pts.append(
                    {"t": t, "lat": jlat, "lon": jlon, "ele": ele + rnd.gauss(0, 0.5)}
                )
            else:
                h = heading + 25 * math.sin(i / 9.0) + (180 if phase == "down" else 0)
                nlat, nlon = step(lat, lon, dist_per_fix, h)
                truth_dist += haversine(lat, lon, nlat, nlon)
                lat, lon = nlat, nlon
                ele += gain_per_fix if phase == "up" else -gain_per_fix
                if phase == "up":
                    truth_gain += gain_per_fix
                dx, dy = drift.next()
                jlat, jlon = jitter(lat, lon, dx, dy)
                pts.append(
                    {"t": t, "lat": jlat, "lon": jlon, "ele": ele + rnd.gauss(0, 0.8)}
                )
            t += timedelta(seconds=5)
        if phase == "up":
            stop_center = (lat, lon)
            stop_start = t
        if phase == "stop":
            stop_end = t
    return pts, {
        "distance_m": round(truth_dist, 1),
        "elevation_gain_m": round(truth_gain, 1),
        "elevation_loss_m": round(truth_gain, 1),
        "stops": [
            {
                "lat": round(stop_center[0], 6),
                "lon": round(stop_center[1], 6),
                "start": stop_start.isoformat(),
                "end": stop_end.isoformat(),
                "duration_min": 20,
            }
        ],
        "duration_s": int((pts[-1]["t"] - pts[0]["t"]).total_seconds()),
        "points": len(pts),
    }


def car():
    """Road trip: 40 km at 20 m/s with a 12 min stop halfway, fix every 10 s."""
    rnd = random.Random(7)
    drift = Drift(rnd, sigma_m=5.0)
    lat, lon = -50.3400, -72.2650
    pts, truth = [], 0.0
    t = START
    per_fix = 20.0 * 10
    n_leg = int(20_000 / per_fix)
    stop = None
    for phase in ("a", "stop", "b"):
        n = n_leg if phase != "stop" else int(12 * 60 / 10)
        for i in range(n):
            if phase == "stop":
                dx, dy = drift.next()
                jlat, jlon = jitter(stop[0], stop[1], dx, dy)
                pts.append({"t": t, "lat": jlat, "lon": jlon, "ele": 250.0})
            else:
                nlat, nlon = step(lat, lon, per_fix, 300 + 10 * math.sin(i / 7.0))
                truth += haversine(lat, lon, nlat, nlon)
                lat, lon = nlat, nlon
                dx, dy = drift.next()
                jlat, jlon = jitter(lat, lon, dx, dy)
                pts.append({"t": t, "lat": jlat, "lon": jlon, "ele": 250.0})
            t += timedelta(seconds=10)
        if phase == "a":
            stop = (lat, lon)
            s0 = t
        if phase == "stop":
            s1 = t
    return pts, {
        "distance_m": round(truth, 1),
        "stops": [
            {
                "lat": round(stop[0], 6),
                "lon": round(stop[1], 6),
                "start": s0.isoformat(),
                "end": s1.isoformat(),
                "duration_min": 12,
            }
        ],
        "duration_s": int((pts[-1]["t"] - pts[0]["t"]).total_seconds()),
        "points": len(pts),
    }


def continuity():
    """E2E fixture for the continuous trip (adenda 1), meant for 1x replay: 2 min walk,
    4 min standing still (rest threshold set to 3 min in Settings), 3 min walk. 9 min total."""
    rnd = random.Random(3)
    drift = Drift(rnd, sigma_m=2.0)
    lat, lon = -34.6037, -58.3816
    pts, truth = [], 0.0
    t = START
    per_fix = 1.1 * 5
    for phase, n in (("walk", 24), ("still", 48), ("walk", 36)):
        for i in range(n):
            if phase == "walk":
                nlat, nlon = step(lat, lon, per_fix, 90 + 15 * math.sin(i / 5.0))
                truth += haversine(lat, lon, nlat, nlon)
                lat, lon = nlat, nlon
            dx, dy = drift.next()
            jlat, jlon = jitter(lat, lon, dx, dy)
            pts.append({"t": t, "lat": jlat, "lon": jlon, "ele": 25.0})
            t += timedelta(seconds=5)
    return pts, {
        "distance_m": round(truth, 1),
        "still_s": 240,
        "duration_s": int((pts[-1]["t"] - pts[0]["t"]).total_seconds()),
        "points": len(pts),
    }


def main():
    for name, fn in (("trekking", trekking), ("car", car), ("continuity", continuity)):
        d = ROOT / name
        d.mkdir(exist_ok=True)
        pts, truth = fn()
        write_gpx(d / "track.gpx", name, pts)
        # Raw path length of the GPX as written (what a replay reproduces exactly).
        truth["gpx_path_m"] = round(
            sum(
                haversine(
                    pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"]
                )
                for i in range(len(pts) - 1)
            ),
            1,
        )
        (d / "reference.json").write_text(
            json.dumps(truth, indent=2) + "\n", encoding="utf-8"
        )
        print(f"{name}: {len(pts)} pts, distance {truth['distance_m']} m")


if __name__ == "__main__":
    main()
