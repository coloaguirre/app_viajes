"""ASS subtitles for voice notes burned in the final pass (readable style, bundled font)."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Cue:
    start_ms: int
    end_ms: int
    text: str


def _ts(ms: int) -> str:
    cs = ms // 10
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def ass(cues: list[Cue], width: int, height: int) -> str:
    size = max(18, int(height * 0.042))
    margin_v = int(height * 0.08)
    head = (
        f"[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n"
        "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
        "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
        f"Style: Voz,DejaVu Sans,{size},&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,3,2,0,2,60,60,{margin_v},1\n\n"
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    )
    body = "".join(
        f"Dialogue: 0,{_ts(c.start_ms)},{_ts(c.end_ms)},Voz,,0,0,0,,{c.text.replace(chr(10), ' ').replace('{', '(').replace('}', ')')}\n"
        for c in cues
        if c.text.strip()
    )
    return head + body
