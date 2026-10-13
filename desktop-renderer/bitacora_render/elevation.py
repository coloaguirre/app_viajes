"""Altitude correction with a digital elevation model (DEM) and the same gain/loss rule as core/geo (DISENO §13 "corrección de altitud")."""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from pathlib import Path

Provider = Callable[[Sequence[tuple[float, float]]], list[float | None]]


def moving_average(values: Sequence[float], window: int = 5) -> list[float]:
    out = []
    half = window // 2
    for i in range(len(values)):
        lo, hi = max(0, i - half), min(len(values), i + half + 1)
        out.append(sum(values[lo:hi]) / (hi - lo))
    return out


def elevation_gain_loss(alts: Sequence[float], threshold: float = 3.0) -> tuple[float, float]:
    """Hysteresis over a 5-sample moving average, identical to core/geo Metrics."""
    if not alts:
        return 0.0, 0.0
    smooth = moving_average(alts, 5)
    gain = loss = 0.0
    ref = smooth[0]
    for a in smooth[1:]:
        d = a - ref
        if d >= threshold:
            gain += d
            ref = a
        elif d <= -threshold:
            loss += -d
            ref = a
    return gain, loss


class OpenTopoData:
    """https://api.opentopodata.org (SRTM 30 m): 100 locations per call, 1 call per second, cached as JSON."""

    def __init__(self, cache_dir: Path, dataset: str = "srtm30m", session=None, sleep=None):
        self.cache = cache_dir
        self.dataset = dataset
        self._session = session
        self._sleep = sleep

    def __call__(self, points: Sequence[tuple[float, float]]) -> list[float | None]:
        import time  # noqa: PLC0415

        import requests  # noqa: PLC0415

        s = self._session or requests.Session()
        out: list[float | None] = []
        self.cache.mkdir(parents=True, exist_ok=True)
        for i in range(0, len(points), 100):
            chunk = points[i : i + 100]
            locs = "|".join(f"{lat:.5f},{lon:.5f}" for lat, lon in chunk)
            f = self.cache / (self.dataset + "-" + str(abs(hash(locs))) + ".json")
            if f.exists():
                data = json.loads(f.read_text())
            else:
                r = s.get(f"https://api.opentopodata.org/v1/{self.dataset}", params={"locations": locs}, timeout=30)
                r.raise_for_status()
                data = r.json()
                f.write_text(json.dumps(data))
                (self._sleep or time.sleep)(1.0)
            out.extend(res.get("elevation") for res in data.get("results", []))
        return out


def correct(points: Sequence[tuple[float, float, float | None]], provider: Provider, sample_every: int = 1) -> list[float | None]:
    """Replaces the GPS altitude of every `sample_every`-th point with the DEM value; the rest keep theirs."""
    idx = list(range(0, len(points), max(1, sample_every)))
    dem = provider([(points[i][0], points[i][1]) for i in idx])
    out = [p[2] for p in points]
    for i, v in zip(idx, dem, strict=False):
        if v is not None:
            out[i] = float(v)
    return out
