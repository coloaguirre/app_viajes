"""Final audio pass: voices placed at their scene offsets, music looped and ducked under them (skill trip-video §3)."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import ffmpeg


@dataclass
class VoiceCue:
    file: Path
    offset_ms: int
    max_ms: int


def mix(video: Path, out: Path, voices: list[VoiceCue], music: Path | None, music_volume: float, duck: bool, work: Path) -> Path:
    """`video` already carries the clips' own audio. Adds the voices (adelay) and the music (ducked when asked)."""
    voices = [v for v in voices if v.file.exists()]
    if not voices and music is None:
        if video != out:
            out.write_bytes(video.read_bytes())
        return out
    inputs = ["-i", str(video)]
    filters = []
    labels = ["[0:a]"]
    idx = 1
    for v in voices:
        inputs += ["-i", str(v.file)]
        filters.append(
            f"[{idx}:a]atrim=0:{v.max_ms / 1000:.3f},aformat=sample_rates=48000:channel_layouts=stereo,adelay={v.offset_ms}|{v.offset_ms}[v{idx}]"
        )
        labels.append(f"[v{idx}]")
        idx += 1
    voice_mix = "[voices]"
    if len(labels) > 1:
        filters.append(f"{''.join(labels)}amix=inputs={len(labels)}:duration=first:normalize=0{voice_mix}")
    else:
        filters.append(f"[0:a]acopy{voice_mix}")
    final = voice_mix
    if music is not None:
        inputs += ["-stream_loop", "-1", "-i", str(music)]
        filters.append(f"[{idx}:a]volume={music_volume},aformat=sample_rates=48000:channel_layouts=stereo[m]")
        if duck:
            filters.append(f"[m]{voice_mix}sidechaincompress=threshold=0.05:ratio=8:attack=20:release=400[duck]")
            filters.append(f"[duck]{voice_mix}amix=inputs=2:duration=first:normalize=0[a]")
        else:
            filters.append(f"[m]{voice_mix}amix=inputs=2:duration=first:normalize=0[a]")
        final = "[a]"
    ffmpeg.run(
        [
            *inputs,
            "-filter_complex",
            ";".join(filters),
            "-map",
            "0:v",
            "-map",
            final,
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-shortest",
            str(out),
        ]
    )
    return out
