"""Ken Burns photo scenes through ffmpeg's zoompan (skill trip-video §3)."""

from __future__ import annotations

from pathlib import Path

from PIL import Image

from . import cards, ffmpeg


def prepare_still(src: Path, w: int, h: int, out: Path) -> Path:
    """A 2× frame (less zoompan jitter), vertical photos letterboxed on a blurred copy."""
    img = cards.load_image(src)
    target = (w * 2, h * 2)
    frame_ratio = w / h
    ratio = img.width / img.height
    still = cards.letterbox(img, *target) if abs(ratio - frame_ratio) > 0.2 and ratio < frame_ratio else cards.cover_fit(img, *target)
    still.save(out, quality=92)
    return out


def zoompan_filter(from_: list[float], to: list[float], frames: int, w: int, h: int, fps: int) -> str:
    """kenburns [x, y, zoom] from→to interpolated linearly over `frames` (x/y as a fraction of the image)."""
    x0, y0, z0 = from_
    x1, y1, z1 = to
    t = f"(on/{max(1, frames - 1)})"
    zoom = f"({z0}+({z1}-{z0})*{t})"
    # Pan expressed as the top-left corner of the visible window inside the (2×) input.
    x = f"(iw-iw/zoom)*({x0}+({x1}-{x0})*{t})/0.15"
    y = f"(ih-ih/zoom)*({y0}+({y1}-{y0})*{t})/0.15"
    return f"zoompan=z='min(max({zoom},1.0),2.0)':x='min(max({x},0),iw-iw/zoom)':y='min(max({y},0),ih-ih/zoom)':d={frames}:s={w}x{h}:fps={fps}"


def render_photo(
    still: Path,
    out: Path,
    duration_ms: int,
    w: int,
    h: int,
    fps: int,
    from_: list[float],
    to: list[float],
    caption: str | None,
) -> Path:
    frames = max(1, round(duration_ms / 1000 * fps))
    vf = zoompan_filter(from_, to, frames, w, h, fps)
    if caption:
        # Captions are drawn on a transparent PNG overlaid at the end: no font escaping issues in ffmpeg.
        overlay = out.with_suffix(".caption.png")
        base = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        cap = cards.caption_overlay(Image.new("RGB", (w, h), (0, 0, 0)), caption)
        alpha = Image.new("L", (w, h), 0)
        mask = cards.caption_overlay(Image.new("RGB", (w, h), (0, 0, 0)), caption).convert("L").point(lambda v: 255 if v > 8 else 0)
        base.paste(cap, (0, 0), mask)
        del alpha
        base.save(overlay)
        ffmpeg.run([
            "-loop", "1", "-i", str(still), "-i", str(overlay), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", f"{duration_ms / 1000:.3f}",
            "-filter_complex", f"[0:v]{vf},format=yuv420p[kb];[kb][1:v]overlay=0:0:format=auto,format=yuv420p[v]",
            "-map", "[v]", "-map", "2:a", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-shortest", str(out),
        ])  # fmt: skip
        return out
    ffmpeg.run([
        "-loop", "1", "-i", str(still), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", f"{duration_ms / 1000:.3f}",
        "-filter_complex", f"[0:v]{vf},format=yuv420p[v]",
        "-map", "[v]", "-map", "1:a", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-shortest", str(out),
    ])  # fmt: skip
    return out
