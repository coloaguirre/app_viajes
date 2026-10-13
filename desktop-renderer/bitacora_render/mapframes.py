"""Base map (tiles from a keyed provider, or none) and animated route frames streamed to ffmpeg (DISENO §13.4)."""

from __future__ import annotations

import hashlib
import math
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw

from . import cards

TILE = 256
ROUTE_COLORS = [(31, 56, 100), (100, 143, 31), (212, 63, 31), (164, 31, 143), (255, 127, 0)]


def _ease(t: float) -> float:
    return 0.5 - 0.5 * math.cos(math.pi * min(1.0, max(0.0, t)))


def lonlat_to_world(lon: float, lat: float, zoom: float) -> tuple[float, float]:
    n = 2.0**zoom * TILE
    x = (lon + 180.0) / 360.0 * n
    lat_r = math.radians(max(-85.05, min(85.05, lat)))
    y = (1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n
    return x, y


@dataclass
class BBox:
    min_lat: float
    min_lon: float
    max_lat: float
    max_lon: float

    @staticmethod
    def of(points: Iterable[tuple[float, float]], margin: float = 0.1) -> BBox:
        pts = list(points)
        lats = [p[0] for p in pts]
        lons = [p[1] for p in pts]
        dlat = max(1e-3, (max(lats) - min(lats)) * margin)
        dlon = max(1e-3, (max(lons) - min(lons)) * margin)
        return BBox(min(lats) - dlat, min(lons) - dlon, max(lats) + dlat, max(lons) + dlon)


@dataclass
class Projector:
    zoom: float
    origin_x: float
    origin_y: float

    def px(self, lat: float, lon: float) -> tuple[float, float]:
        x, y = lonlat_to_world(lon, lat, self.zoom)
        return x - self.origin_x, y - self.origin_y


def fit_zoom(bbox: BBox, w: int, h: int, max_zoom: float = 17.0) -> float:
    for z in range(int(max_zoom), 0, -1):
        x0, y0 = lonlat_to_world(bbox.min_lon, bbox.max_lat, z)
        x1, y1 = lonlat_to_world(bbox.max_lon, bbox.min_lat, z)
        if x1 - x0 <= w and y1 - y0 <= h:
            return float(z)
    return 1.0


class TileSource:
    """`https://api.maptiler.com/maps/outdoor-v2/256/{z}/{x}/{y}.png?key=…` style templates, cached on disk.

    Never a provider without a key (tile.openstreetmap.org, Google): their usage policies forbid this use.
    """

    def __init__(self, template: str, cache_dir: Path, attribution: str, session=None):
        self.template = template
        self.cache = cache_dir
        self.attribution = attribution
        self._session = session

    def tile(self, z: int, x: int, y: int) -> Image.Image | None:
        n = 2**z
        x %= n
        if y < 0 or y >= n:
            return None
        key = hashlib.sha1(f"{self.template}|{z}|{x}|{y}".encode()).hexdigest()
        f = self.cache / key[:2] / f"{key}.png"
        if f.exists():
            return Image.open(f).convert("RGB")
        url = self.template.format(z=z, x=x, y=y)
        try:
            import requests  # noqa: PLC0415

            s = self._session or requests.Session()
            r = s.get(
                url,
                timeout=20,
                headers={"User-Agent": "BitacoraViaje-render/0.1 (https://github.com/coloaguirre/app_viajes)"},
            )
            if r.status_code != 200:
                return None
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(r.content)
            return Image.open(f).convert("RGB")
        except Exception:
            return None


def basemap(bbox: BBox, w: int, h: int, tiles: TileSource | None) -> tuple[Image.Image, Projector]:
    """Composes the tiles covering the bbox at the zoom that fits; without a provider, a plain ground."""
    zoom = fit_zoom(bbox, w, h)
    cx, cy = lonlat_to_world((bbox.min_lon + bbox.max_lon) / 2, (bbox.min_lat + bbox.max_lat) / 2, zoom)
    proj = Projector(zoom, cx - w / 2, cy - h / 2)
    img = Image.new("RGB", (w, h), (226, 232, 226))
    if tiles is None:
        d = ImageDraw.Draw(img)
        step = TILE // 2
        for gx in range(0, w, step):
            d.line((gx, 0, gx, h), fill=(214, 221, 214))
        for gy in range(0, h, step):
            d.line((0, gy, w, gy), fill=(214, 221, 214))
        return img, proj
    tx0, ty0 = int(proj.origin_x // TILE), int(proj.origin_y // TILE)
    tx1, ty1 = int((proj.origin_x + w) // TILE), int((proj.origin_y + h) // TILE)
    z = int(zoom)
    for tx in range(tx0, tx1 + 1):
        for ty in range(ty0, ty1 + 1):
            t = tiles.tile(z, tx, ty)
            if t is not None:
                img.paste(t, (int(tx * TILE - proj.origin_x), int(ty * TILE - proj.origin_y)))
    return img, proj


def _attribution(img: Image.Image, text: str) -> None:
    d = ImageDraw.Draw(img)
    fnt = cards.font(max(10, img.height // 60))
    tw = d.textlength(text, font=fnt)
    pad = 6
    d.rectangle((img.width - tw - 3 * pad, img.height - fnt.size - 3 * pad, img.width, img.height), fill=(255, 255, 255))
    d.text((img.width - tw - 2 * pad, img.height - fnt.size - 2 * pad), text, font=fnt, fill=(60, 60, 60))


def route_frames(
    tracks: list[list[tuple[int, float, float]]],
    w: int,
    h: int,
    frames: int,
    style: str,
    tiles: TileSource | None,
    attribution: str,
    on_frame: Callable[[bytes], None],
) -> Image.Image:
    """Draws the route growing over `frames` frames (time-interpolated, ease-in-out) and hands RGB bytes to `on_frame`.

    `tracks`: per segment, (t_utc_ms, lat, lon) in time order. Returns the final frame (used as a still for overlays).
    """
    pts_all = [(lat, lon) for tr in tracks for _, lat, lon in tr]
    if not pts_all:
        img = Image.new("RGB", (w, h), (226, 232, 226))
        for _ in range(frames):
            on_frame(img.tobytes())
        return img
    scale = 2  # supersampling for antialiased lines
    W, H = w * scale, h * scale
    base, proj = basemap(BBox.of(pts_all), W, H, tiles)
    t_start = min(tr[0][0] for tr in tracks if tr)
    t_end = max(tr[-1][0] for tr in tracks if tr)
    span = max(1, t_end - t_start)
    projected = [[(t, *proj.px(lat, lon)) for t, lat, lon in tr] for tr in tracks]
    last = base
    for i in range(frames):
        t = t_start + span * _ease(i / max(1, frames - 1))
        img = base.copy()
        d = ImageDraw.Draw(img)
        tip = None
        for si, tr in enumerate(projected):
            color = ROUTE_COLORS[si % len(ROUTE_COLORS)]
            line = [(x, y) for tt, x, y in tr if tt <= t]
            # Interpolate the last leg so the tip moves smoothly.
            nxt = next(((tt, x, y) for tt, x, y in tr if tt > t), None)
            if line and nxt:
                pt, px, py = next((tt, x, y) for tt, x, y in reversed(tr) if tt <= t)
                f = (t - pt) / max(1, nxt[0] - pt)
                line.append((px + (nxt[1] - px) * f, py + (nxt[2] - py) * f))
            if len(line) >= 2:
                d.line(line, fill=(255, 255, 255), width=10 * scale // 2 + 4, joint="curve")
                d.line(line, fill=color, width=6 * scale // 2 + 2, joint="curve")
            if line:
                tip = line[-1]
        if tip:
            r = 9 * scale // 2 + 3
            d.ellipse((tip[0] - r, tip[1] - r, tip[0] + r, tip[1] + r), fill=(255, 255, 255))
            d.ellipse((tip[0] - r + 3, tip[1] - r + 3, tip[0] + r - 3, tip[1] + r - 3), fill=(212, 63, 31))
        if style == "follow" and tip:
            # Camera window around the tip (40 % of the map), eased from the full view in the first third.
            k = _ease(min(1.0, i / max(1, frames * 0.33)))
            vw, vh = W * (1 - 0.6 * k), H * (1 - 0.6 * k)
            cx = W / 2 + (tip[0] - W / 2) * k
            cy = H / 2 + (tip[1] - H / 2) * k
            x0 = min(max(0, cx - vw / 2), W - vw)
            y0 = min(max(0, cy - vh / 2), H - vh)
            img = img.crop((int(x0), int(y0), int(x0 + vw), int(y0 + vh)))
        frame = img.resize((w, h), Image.Resampling.LANCZOS)
        _attribution(frame, attribution)
        on_frame(frame.tobytes())
        last = frame
    return last
