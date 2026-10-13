"""PLAN 4.5 acceptance: MP4 of the requested duration (±5 %), 1080p-proportional, with audio; keyframes by SSIM."""

from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from bitacora_render import cards, elevation, ffmpeg, kenburns, mapframes, planner, render, subtitles
from bitacora_render.model import Trip, VideoPlan

needs_ffmpeg = pytest.mark.skipif(not ffmpeg.available(), reason="ffmpeg/ffprobe not installed")

REFS = Path(__file__).parent / "refs"
UPDATE = os.environ.get("UPDATE_REFS") == "1"


def ssim(a: Image.Image, b: Image.Image) -> float:
    """Global SSIM on the luminance of two same-size images (no window: enough to catch a broken scene)."""
    x = np.asarray(a.convert("L"), dtype=np.float64)
    y = np.asarray(b.convert("L"), dtype=np.float64)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mx, my = x.mean(), y.mean()
    vx, vy = x.var(), y.var()
    cov = ((x - mx) * (y - my)).mean()
    return float(((2 * mx * my + c1) * (2 * cov + c2)) / ((mx**2 + my**2 + c1) * (vx + vy + c2)))


def check_ref(name: str, img: Image.Image, threshold: float = 0.95) -> None:
    ref = REFS / f"{name}.png"
    small = img.resize((192, 108 if img.width >= img.height else 192))
    if UPDATE or not ref.exists():
        REFS.mkdir(exist_ok=True)
        small.save(ref)
        return
    s = ssim(small, Image.open(ref))
    assert s >= threshold, f"{name}: SSIM {s:.3f} < {threshold} (set UPDATE_REFS=1 to accept a deliberate change)"


def test_cards_render_and_match_references():
    w, h = 384, 216
    check_ref("title", cards.title_card(w, h, "Patagonia 2026", "2025-10-09 — 2025-10-10", None))
    check_ref("chapter", cards.chapter_card(w, h, "Caminata inicial", "2025-10-09"))
    check_ref("place", cards.place_card(w, h, "El Chaltén", "Un pueblo al pie del Fitz Roy, fundado en 1985. " * 6, "Wikipedia (CC BY-SA 4.0)"))
    check_ref(
        "stats",
        cards.stats_card(
            w,
            h,
            "Caminata",
            [("distancia", cards.fmt_km(3500)), ("en movimiento", cards.fmt_dur(3_000_000)), ("desnivel +", cards.fmt_int(1204, "m"))],
        ),
    )
    check_ref(
        "credits", cards.credits_card(w, h, ["Datos del mapa © colaboradores de OpenStreetMap (ODbL)", "Texto: Wikipedia — Mirador (CC BY-SA 4.0)"])
    )
    assert cards.fmt_km(8_300) == "8,3 km" and cards.fmt_int(1204.4, "m") == "1.204 m" and cards.fmt_dur(8_040_000) == "2 h 14 min"


def test_map_frames_offline_grow_the_route_and_follow_mode_crops():
    track = [(i * 10_000, -49.33 + i * 0.001, -72.9 + i * 0.001) for i in range(30)]
    frames = []
    last = mapframes.route_frames([track], 320, 180, 6, "overview", None, "test", lambda b: frames.append(b))
    assert len(frames) == 6 and last.size == (320, 180)
    first = np.frombuffer(frames[0], dtype=np.uint8).reshape(180, 320, 3)
    final = np.frombuffer(frames[-1], dtype=np.uint8).reshape(180, 320, 3)
    assert (final != first).any(), "the route grows between the first and the last frame"
    check_ref("map_overview", last)
    follow = []
    mapframes.route_frames([track], 320, 180, 4, "follow", None, "test", lambda b: follow.append(b))
    assert len(follow) == 4
    # Projection sanity: equator/Greenwich at zoom 0 is the centre of the single tile.
    assert mapframes.lonlat_to_world(0.0, 0.0, 0) == (128.0, 128.0)


def test_subtitles_and_elevation_helpers():
    text = subtitles.ass([subtitles.Cue(1500, 4000, "Llegamos al mirador {viento}")], 1920, 1080)
    assert "Dialogue: 0,0:00:01.50,0:00:04.00,Voz" in text and "(viento)" in text
    gain, loss = elevation.elevation_gain_loss([100, 100, 110, 110, 120, 120, 105, 105, 100, 100])
    assert gain > 5 and loss > 5
    pts = [(-49.3, -72.9, 400.0), (-49.31, -72.91, 410.0), (-49.32, -72.92, None)]
    out = elevation.correct(pts, lambda locs: [500.0 + i for i in range(len(locs))], sample_every=2)
    assert out == [500.0, 410.0, 501.0]
    zp = kenburns.zoompan_filter([0.0, 0.0, 1.0], [0.05, 0.05, 1.15], 105, 1920, 1080, 30)
    assert "zoompan=" in zp and "d=105" in zp and "s=1920x1080" in zp


@needs_ffmpeg
def test_full_render_low_resolution(export_dir: Path, tmp_path: Path):
    trip = Trip.model_validate_json((export_dir / "trip.json").read_text(encoding="utf-8"))
    plan = planner.plan(planner.plan_input_of(trip), planner.PlanParams(60, voice="both"))
    plan_file = tmp_path / "plan.json"
    plan_file.write_text(plan.to_json(), encoding="utf-8")
    out = tmp_path / "resumen.mp4"
    work = tmp_path / "work"
    r = render.Renderer(
        export_dir, trip, VideoPlan.model_validate(json.loads(plan_file.read_text())), render.RenderOptions(preview_scale=0.2, jobs=2), work
    )
    r.render(out, voice_mode="both")
    info = ffmpeg.probe(out)
    target = plan.total_duration_ms / 1000
    assert abs(info.duration_s - target) <= target * 0.05 + 0.5, f"{info.duration_s} vs {target}"
    assert (info.width, info.height) == (384, 216)
    assert info.has_video and info.has_audio
    # Keyframe of the first photo scene, compared by SSIM with the stored reference.
    idx = next(i for i, sc in enumerate(plan.scenes) if sc.type == "photo")
    frame = tmp_path / "frame.png"
    ffmpeg.run(["-ss", "0.5", "-i", str(work / f"scene_{idx:03d}.mp4"), "-frames:v", "1", str(frame)])
    check_ref("photo_scene", Image.open(frame), threshold=0.9)


@needs_ffmpeg
def test_cli_plan_and_preview(export_dir: Path, tmp_path: Path):
    from typer.testing import CliRunner

    from bitacora_render.cli import app

    runner = CliRunner()
    plan_file = tmp_path / "plan.json"
    r = runner.invoke(app, ["plan", str(export_dir), "--duration", "60", "-o", str(plan_file)])
    assert r.exit_code == 0, r.output
    assert "escenas" in r.output and plan_file.exists()
    png = tmp_path / "cuadro.png"
    r = runner.invoke(app, ["preview", str(export_dir), str(plan_file), "--scene", "0", "-o", str(png), "--preview-scale", "0.2"])
    assert r.exit_code == 0, r.output
    assert png.exists() and Image.open(png).size == (384, 216)


def test_plan_json_validates_against_schema(export_dir: Path):
    import jsonschema

    schema = json.loads((Path(__file__).resolve().parents[2] / "docs" / "video-plan.schema.json").read_text(encoding="utf-8"))
    trip = Trip.model_validate_json((export_dir / "trip.json").read_text(encoding="utf-8"))
    for secs in planner.DURATIONS_S:
        p = planner.plan(planner.plan_input_of(trip), planner.PlanParams(secs))
        jsonschema.Draft202012Validator(schema).validate(json.loads(p.to_json()))
        assert p.total_duration_ms <= secs * 1000 * 1.05
    with pytest.raises(ValueError):
        planner.plan(planner.plan_input_of(trip), planner.PlanParams(0))


@needs_ffmpeg
def test_music_with_ducking_and_without_voices(export_dir: Path, tmp_path: Path):
    """Review B1: the ducking graph used the voices pad twice and ffmpeg refused it."""
    import subprocess

    music = tmp_path / "musica.mp3"
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
            "sine=frequency=220:sample_rate=48000",
            "-t",
            "8",
            "-c:a",
            "libmp3lame",
            str(music),
        ],
        check=True,
    )
    trip = Trip.model_validate_json((export_dir / "trip.json").read_text(encoding="utf-8"))
    plan = planner.plan(planner.plan_input_of(trip), planner.PlanParams(60, voice="both", music_file=str(music)))
    for voice_mode in ("both", "subtitles"):
        out = tmp_path / f"con_musica_{voice_mode}.mp4"
        render.Renderer(export_dir, trip, plan, render.RenderOptions(preview_scale=0.1, jobs=2), tmp_path / f"work_{voice_mode}").render(
            out, voice_mode=voice_mode
        )
        info = ffmpeg.probe(out)
        assert info.has_audio and abs(info.duration_s - plan.total_duration_ms / 1000) <= 3.5


def test_tie_break_matches_kotlin_and_helpers():
    from bitacora_render.planner import PlanInput, PlanMedia, PlanSegment

    t0 = 1_760_000_000_000
    seg = PlanSegment(
        "s",
        "Etapa",
        "2026-01-01",
        t0,
        t0 + 3_600_000,
        media=[PlanMedia("early", False, t0 + 60_000), PlanMedia("late", False, t0 + 120_000), PlanMedia("far", False, t0 + 3_000_000)],
    )
    p = planner.plan(PlanInput("t", "T", None, [seg]), planner.PlanParams(60, include_map=False, include_places=False))
    photos = [sc.media for sc in p.scenes if sc.type == "photo"]
    assert "late" in photos and "far" in photos, photos
    assert ffmpeg.escape_filter_path("/tmp/a'b:c,d.ass") == "/tmp/a\\'b\\:c\\,d.ass"


def test_every_voice_mode_keeps_the_voice_notes(export_dir: Path):
    """Review B2 of PR #11: in audio mode the planner used to drop every voice note."""
    trip = Trip.model_validate_json((export_dir / "trip.json").read_text(encoding="utf-8"))
    for mode in ("subtitles", "audio", "both"):
        p = planner.plan(planner.plan_input_of(trip), planner.PlanParams(180, voice=mode))
        refs = [sc.subtitle_from_voice for sc in p.scenes if sc.type == "photo" and sc.subtitle_from_voice] + [
            sc.voice for sc in p.scenes if sc.type == "voice_overlay"
        ]
        assert "v-1" in refs, (mode, refs)
