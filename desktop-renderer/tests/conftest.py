"""A small synthetic export folder (trip.json + JPEG photos + a lavfi video + sine voice notes), built once per session."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from bitacora_render import ffmpeg

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_TRIP = ROOT / "tools" / "plan-fixtures" / "sample-60s" / "trip.json"


def _photo(path: Path, color: tuple[int, int, int], size=(640, 480), label: str = "") -> None:
    img = Image.new("RGB", size, color)
    d = ImageDraw.Draw(img)
    for i in range(0, size[0], 40):
        d.line((i, 0, i, size[1]), fill=(255, 255, 255), width=2)
    d.ellipse((size[0] // 3, size[1] // 3, size[0] * 2 // 3, size[1] * 2 // 3), fill=(255, 220, 90))
    d.text((20, 20), label, fill=(0, 0, 0))
    img.save(path, quality=85)


@pytest.fixture(scope="session")
def export_dir(tmp_path_factory) -> Path:
    d = tmp_path_factory.mktemp("export")
    trip = json.loads(FIXTURE_TRIP.read_text(encoding="utf-8"))
    (d / "trip.json").write_text(json.dumps(trip, ensure_ascii=False), encoding="utf-8")
    colors = [(60, 120, 200), (200, 80, 60), (90, 170, 90), (150, 90, 170)]
    i = 0
    for s in trip["segments"]:
        for m in s["media"]:
            if not m.get("file"):
                continue
            p = d / m["file"]
            p.parent.mkdir(parents=True, exist_ok=True)
            if m["kind"] == "VIDEO":
                if ffmpeg.available():
                    subprocess.run(
                        [
                            ffmpeg.ffmpeg_path(),
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-y",
                            "-f",
                            "lavfi",
                            "-i",
                            "testsrc=size=320x240:rate=15",
                            "-f",
                            "lavfi",
                            "-i",
                            "sine=frequency=440:sample_rate=48000",
                            "-t",
                            f"{(m.get('duration_ms') or 4000) / 1000:.2f}",
                            "-c:v",
                            "libx264",
                            "-pix_fmt",
                            "yuv420p",
                            "-c:a",
                            "aac",
                            "-shortest",
                            str(p),
                        ],
                        check=True,
                    )
            else:
                # One portrait photo to exercise the letterbox path.
                _photo(p, colors[i % len(colors)], size=(480, 640) if i == 1 else (640, 480), label=m["id"])
            i += 1
        for v in s["voice_notes"]:
            if v.get("file") and ffmpeg.available():
                p = d / v["file"]
                p.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(
                    [
                        ffmpeg.ffmpeg_path(),
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=660:sample_rate=48000",
                        "-t",
                        f"{v['duration_ms'] / 1000:.2f}",
                        "-c:a",
                        "aac",
                        str(p),
                    ],
                    check=True,
                )
    yield d
    shutil.rmtree(d, ignore_errors=True)
