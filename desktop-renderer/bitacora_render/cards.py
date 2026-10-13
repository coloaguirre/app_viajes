"""Static cards drawn with Pillow: title, chapter, place, stats, credits, subtitles (skill trip-video §3)."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

FONTS = Path(__file__).parent / "fonts"
NAVY = (31, 56, 100)
INK = (27, 27, 27)
PAPER = (247, 244, 238)
ACCENT = (244, 185, 66)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")), size)


def load_image(path: Path) -> Image.Image:
    """Opens a photo honouring EXIF orientation; HEIC through pillow-heif when installed."""
    try:
        import pillow_heif  # noqa: PLC0415

        pillow_heif.register_heif_opener()
    except Exception:  # pragma: no cover - optional
        pass
    img = Image.open(path)
    img = ImageOps.exif_transpose(img) or img
    return img.convert("RGB")


def cover_fit(img: Image.Image, w: int, h: int) -> Image.Image:
    """Scale to cover the frame (centre crop)."""
    return ImageOps.fit(img, (w, h), method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))


def letterbox(img: Image.Image, w: int, h: int) -> Image.Image:
    """A vertical photo on a 16:9 frame: blurred copy behind, the photo centred on top."""
    bg = cover_fit(img, w, h).filter(ImageFilter.GaussianBlur(radius=max(8, w // 60)))
    bg = Image.eval(bg, lambda v: int(v * 0.6))
    fg = ImageOps.contain(img, (w, h), method=Image.Resampling.LANCZOS)
    bg.paste(fg, ((w - fg.width) // 2, (h - fg.height) // 2))
    return bg


def wrap(draw: ImageDraw.ImageDraw, text: str, fnt: ImageFont.FreeTypeFont, max_w: int) -> list[str]:
    lines: list[str] = []
    for para in text.replace("\r", "").split("\n"):
        words = para.split()
        cur = ""
        for word in words:
            cand = f"{cur} {word}".strip()
            if draw.textlength(cand, font=fnt) <= max_w or not cur:
                cur = cand
            else:
                lines.append(cur)
                cur = word
        lines.append(cur)
    return lines


def _panel(img: Image.Image, box: tuple[int, int, int, int], alpha: int = 170) -> None:
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rounded_rectangle(box, radius=24, fill=(20, 24, 36, alpha))
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"))


def _text_block(img: Image.Image, lines: list[tuple[str, ImageFont.FreeTypeFont, tuple]], x: int, y: int, gap: int = 12) -> int:
    d = ImageDraw.Draw(img)
    for text, fnt, color in lines:
        d.text((x + 2, y + 2), text, font=fnt, fill=(0, 0, 0))
        d.text((x, y), text, font=fnt, fill=color)
        y += int(fnt.size * 1.25) + gap
    return y


def title_card(w: int, h: int, title: str, subtitle: str | None, background: Path | None) -> Image.Image:
    if background and background.exists():
        img = cover_fit(load_image(background), w, h).filter(ImageFilter.GaussianBlur(radius=w // 240))
        img = Image.eval(img, lambda v: int(v * 0.65))
    else:
        img = Image.new("RGB", (w, h), NAVY)
    d = ImageDraw.Draw(img)
    big = font(int(h * 0.09), bold=True)
    lines = wrap(d, title, big, int(w * 0.84))
    y = int(h * 0.36) - (len(lines) - 1) * int(big.size * 0.7)
    for ln in lines:
        tw = d.textlength(ln, font=big)
        d.text(((w - tw) / 2 + 3, y + 3), ln, font=big, fill=(0, 0, 0))
        d.text(((w - tw) / 2, y), ln, font=big, fill=PAPER)
        y += int(big.size * 1.2)
    if subtitle:
        small = font(int(h * 0.04))
        tw = d.textlength(subtitle, font=small)
        d.text(((w - tw) / 2, y + int(h * 0.02)), subtitle, font=small, fill=ACCENT)
    return img


def chapter_card(w: int, h: int, title: str, date: str | None) -> Image.Image:
    img = Image.new("RGB", (w, h), PAPER)
    d = ImageDraw.Draw(img)
    d.rectangle((0, int(h * 0.44), int(w * 0.03), int(h * 0.56)), fill=ACCENT)
    big = font(int(h * 0.07), bold=True)
    lines = wrap(d, title, big, int(w * 0.8))
    y = int(h * 0.42)
    for ln in lines:
        d.text((int(w * 0.08), y), ln, font=big, fill=NAVY)
        y += int(big.size * 1.2)
    if date:
        d.text((int(w * 0.08), y + 6), date, font=font(int(h * 0.035)), fill=INK)
    return img


def place_card(w: int, h: int, title: str, text: str, source: str, background: Path | None = None) -> Image.Image:
    img = cover_fit(load_image(background), w, h) if background and background.exists() else Image.new("RGB", (w, h), NAVY)
    margin = int(w * 0.06)
    box = (margin, int(h * 0.18), w - margin, int(h * 0.88))
    _panel(img, box)
    d = ImageDraw.Draw(img)
    head = font(int(h * 0.06), bold=True)
    body = font(int(h * 0.036))
    foot = font(int(h * 0.024))
    x = margin + int(w * 0.03)
    y = box[1] + int(h * 0.03)
    for ln in wrap(d, title, head, box[2] - box[0] - int(w * 0.06)):
        d.text((x, y), ln, font=head, fill=PAPER)
        y += int(head.size * 1.25)
    y += int(h * 0.02)
    max_lines = max(2, int((box[3] - y - int(h * 0.08)) / (body.size * 1.35)))
    lines = wrap(d, text, body, box[2] - box[0] - int(w * 0.06))
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1].rstrip(".,;:") + "…"
    for ln in lines:
        d.text((x, y), ln, font=body, fill=PAPER)
        y += int(body.size * 1.35)
    d.text((x, box[3] - int(h * 0.055)), f"Fuente: {source}", font=foot, fill=ACCENT)
    return img


def fmt_km(m: float | None) -> str:
    if m is None:
        return "—"
    return f"{m / 1000:.1f} km".replace(".", ",") if m >= 1000 else f"{int(m)} m"


def fmt_dur(ms: int | None) -> str:
    if ms is None:
        return "—"
    hrs, mins = ms // 3_600_000, ms // 60_000 % 60
    return f"{hrs} h {mins:02d} min" if hrs else f"{mins} min"


def fmt_int(v: float | None, unit: str) -> str:
    if v is None:
        return "—"
    return f"{int(round(v)):,}".replace(",", ".") + f" {unit}"


def stats_card(w: int, h: int, title: str, rows: list[tuple[str, str]]) -> Image.Image:
    img = Image.new("RGB", (w, h), NAVY)
    d = ImageDraw.Draw(img)
    head = font(int(h * 0.055), bold=True)
    d.text((int(w * 0.08), int(h * 0.12)), title, font=head, fill=PAPER)
    label = font(int(h * 0.034))
    value = font(int(h * 0.07), bold=True)
    cols = 3 if w >= h else 2
    cell_w = int(w * 0.84) // cols
    for i, (lab, val) in enumerate(rows):
        cx = int(w * 0.08) + (i % cols) * cell_w
        cy = int(h * 0.32) + (i // cols) * int(h * 0.28)
        d.text((cx, cy), val, font=value, fill=ACCENT)
        d.text((cx, cy + int(value.size * 1.2)), lab, font=label, fill=PAPER)
    return img


def credits_card(w: int, h: int, lines: list[str]) -> Image.Image:
    img = Image.new("RGB", (w, h), INK)
    d = ImageDraw.Draw(img)
    head = font(int(h * 0.05), bold=True)
    body = font(int(h * 0.026))
    d.text((int(w * 0.08), int(h * 0.1)), "Créditos", font=head, fill=PAPER)
    y = int(h * 0.22)
    for line in lines:
        for ln in wrap(d, line, body, int(w * 0.84)):
            d.text((int(w * 0.08), y), ln, font=body, fill=PAPER)
            y += int(body.size * 1.4)
        y += int(body.size * 0.5)
        if y > h - int(h * 0.08):
            break
    return img


def caption_overlay(img: Image.Image, text: str, bottom: bool = True) -> Image.Image:
    """Burns a caption/subtitle with a soft panel; used for photo captions (voice subtitles go through .ass)."""
    w, h = img.size
    d = ImageDraw.Draw(img)
    fnt = font(int(h * 0.034))
    lines = wrap(d, text, fnt, int(w * 0.8))[:3]
    block_h = len(lines) * int(fnt.size * 1.35) + int(h * 0.03)
    y0 = h - block_h - int(h * 0.06) if bottom else int(h * 0.06)
    _panel(img, (int(w * 0.06), y0, w - int(w * 0.06), y0 + block_h), alpha=150)
    y = y0 + int(h * 0.015)
    for ln in lines:
        d = ImageDraw.Draw(img)
        d.text((int(w * 0.09), y), ln, font=fnt, fill=PAPER)
        y += int(fnt.size * 1.35)
    return img
