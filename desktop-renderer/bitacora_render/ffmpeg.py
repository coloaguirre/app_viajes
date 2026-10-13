"""Thin wrappers over the system ffmpeg/ffprobe (skill trip-video §3)."""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


class FfmpegError(RuntimeError):
    pass


def ffmpeg_path() -> str:
    p = shutil.which("ffmpeg")
    if not p:
        raise FfmpegError("ffmpeg no está instalado o no está en el PATH")
    return p


def ffprobe_path() -> str:
    p = shutil.which("ffprobe")
    if not p:
        raise FfmpegError("ffprobe no está instalado o no está en el PATH")
    return p


def available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def run(args: list[str], *, stdin: bytes | None = None, cwd: Path | None = None) -> None:
    cmd = [ffmpeg_path(), "-hide_banner", "-loglevel", "error", "-y", *args]
    r = subprocess.run(cmd, input=stdin, capture_output=True, cwd=cwd)
    if r.returncode != 0:
        raise FfmpegError(f"ffmpeg falló ({r.returncode}): {' '.join(cmd[:12])}…\n{r.stderr.decode(errors='replace')[-2000:]}")


def start_rawvideo_pipe(out: Path, width: int, height: int, fps: int, extra: list[str] | None = None) -> subprocess.Popen:
    """ffmpeg reading RGB frames from stdin (map frames are streamed, never saved as PNG)."""
    cmd = [
        ffmpeg_path(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}", "-r", str(fps), "-i", "-",
        "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
        *(extra or []),
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(out),
    ]  # fmt: skip
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)


@dataclass
class ProbeInfo:
    duration_s: float
    width: int | None
    height: int | None
    has_video: bool
    has_audio: bool
    rotation: int = 0


def probe(path: Path) -> ProbeInfo:
    cmd = [
        ffprobe_path(),
        "-v",
        "error",
        "-show_entries",
        "format=duration:stream=codec_type,width,height,side_data_list:stream_tags=rotate",
        "-of",
        "json",
        str(path),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise FfmpegError(f"ffprobe falló sobre {path}: {r.stderr[-500:]}")
    d = json.loads(r.stdout or "{}")
    streams = d.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    rotation = 0
    if video:
        rotation = int(float(video.get("tags", {}).get("rotate", 0) or 0))
        for sd in video.get("side_data_list", []) or []:
            if "rotation" in sd:
                rotation = int(float(sd["rotation"]))
    return ProbeInfo(
        duration_s=float(d.get("format", {}).get("duration", 0.0) or 0.0),
        width=video.get("width") if video else None,
        height=video.get("height") if video else None,
        has_video=video is not None,
        has_audio=any(s.get("codec_type") == "audio" for s in streams),
        rotation=rotation,
    )


def concat(clips: list[Path], out: Path, work: Path) -> None:
    """concat demuxer over clips that share codec, size, fps and pix_fmt (each scene is normalised)."""
    lst = work / "scenes.txt"
    lst.write_text("".join(f"file '{c.resolve().as_posix()}'\n" for c in clips), encoding="utf-8")
    run(["-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(out)])
