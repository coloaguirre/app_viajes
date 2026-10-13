"""Scene-by-scene rendering to intermediate clips, concat, subtitles and audio (skill trip-video §3 "Estrategia")."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from . import audio, cards, ffmpeg, kenburns, mapframes, subtitles
from .model import (
    Chapter,
    Credits,
    MapRoute,
    Photo,
    PlaceCard,
    StatsScene,
    Title,
    Trip,
    VideoClip,
    VideoPlan,
    VoiceOverlay,
)

ATTRIBUTION_OSM = "© OpenStreetMap contributors"


@dataclass
class RenderOptions:
    preview_scale: float = 1.0
    jobs: int = 2
    keep_work: bool = False
    tiles_template: str | None = None
    tiles_attribution: str = ATTRIBUTION_OSM
    tile_cache: Path = Path.home() / ".cache" / "bitacora-render" / "tiles"


def _even(v: float) -> int:
    n = max(16, int(round(v)))
    return n if n % 2 == 0 else n + 1


class Renderer:
    def __init__(self, export_dir: Path, trip: Trip, plan: VideoPlan, opts: RenderOptions, work: Path):
        self.export = export_dir
        self.trip = trip
        self.plan = plan
        self.opts = opts
        self.work = work
        self.w = _even(plan.output.width * opts.preview_scale)
        self.h = _even(plan.output.height * opts.preview_scale)
        self.fps = plan.output.fps
        self.media = trip.media_by_id()
        self.segments = trip.segment_by_id()
        self.voices = {v.id: v for s in trip.segments for v in s.voice_notes}
        self.tiles = mapframes.TileSource(opts.tiles_template, opts.tile_cache, opts.tiles_attribution) if opts.tiles_template else None

    # -- helpers -------------------------------------------------------------------------------
    def media_path(self, media_id: str | None) -> Path | None:
        m = self.media.get(media_id or "")
        if not m or not m.file:
            return None
        p = self.export / m.file
        return p if p.exists() else None

    def still_clip(self, img: Image.Image, out: Path, duration_ms: int) -> Path:
        png = out.with_suffix(".png")
        img.save(png)
        ffmpeg.run([
            "-loop", "1", "-framerate", str(self.fps), "-i", str(png),
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", f"{duration_ms / 1000:.3f}",
            "-vf", f"scale={self.w}:{self.h},format=yuv420p", "-r", str(self.fps),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-shortest", str(out),
        ])  # fmt: skip
        return out

    def tracks_of(self, segment_ids: list[str]) -> list[list[tuple[int, float, float]]]:
        return [[(p.t_utc_ms, p.lat, p.lon) for p in self.segments[s].points if not p.filtered] for s in segment_ids if s in self.segments]

    # -- scenes --------------------------------------------------------------------------------
    def render_scene(self, i: int, scene) -> Path:
        out = self.work / f"scene_{i:03d}.mp4"
        w, h = self.w, self.h
        if isinstance(scene, Title):
            return self.still_clip(
                cards.title_card(w, h, scene.title, scene.subtitle, self.media_path(scene.background_media)),
                out,
                scene.duration_ms,
            )
        if isinstance(scene, Chapter):
            return self.still_clip(cards.chapter_card(w, h, scene.title, scene.date), out, scene.duration_ms)
        if isinstance(scene, PlaceCard):
            return self.still_clip(cards.place_card(w, h, scene.title, scene.text, scene.source), out, scene.duration_ms)
        if isinstance(scene, StatsScene):
            seg = self.segments.get(scene.segment or "")
            title = (seg.display_name if seg else None) or "Estadísticas"
            rows = [
                ("distancia", cards.fmt_km(scene.distance_m)),
                ("en movimiento", cards.fmt_dur(scene.moving_time_ms)),
                ("desnivel +", cards.fmt_int(scene.elevation_gain_m, "m")),
                ("altura máx.", cards.fmt_int(scene.max_alt_m, "m")),
                ("fotos", str(scene.photos) if scene.photos is not None else "—"),
            ]
            return self.still_clip(cards.stats_card(w, h, title, rows), out, scene.duration_ms)
        if isinstance(scene, Credits):
            # The tile provider is a render-time choice, so its credit is added here (review M13).
            lines = list(scene.lines)
            if self.tiles is not None and not any(self.opts.tiles_attribution in ln for ln in lines):
                lines.insert(1, f"Mapa: {self.opts.tiles_attribution}")
            return self.still_clip(cards.credits_card(w, h, lines), out, scene.duration_ms)
        if isinstance(scene, Photo):
            src = self.media_path(scene.media)
            if src is None:
                return self.still_clip(
                    cards.chapter_card(w, h, scene.caption or "Foto no incluida en la exportación", None),
                    out,
                    scene.duration_ms,
                )
            still = kenburns.prepare_still(src, w, h, self.work / f"still_{i:03d}.jpg")
            return kenburns.render_photo(still, out, scene.duration_ms, w, h, self.fps, scene.kenburns.from_, scene.kenburns.to, scene.caption)
        if isinstance(scene, VideoClip):
            src = self.media_path(scene.media)
            if src is None:
                return self.still_clip(cards.chapter_card(w, h, "Video no incluido en la exportación", None), out, scene.duration_ms)
            vf = f"scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,fps={self.fps},format=yuv420p"
            args = [
                "-ss",
                f"{scene.trim_start_ms / 1000:.3f}",
                "-to",
                f"{scene.trim_end_ms / 1000:.3f}",
                "-i",
                str(src),
            ]
            if scene.keep_audio and ffmpeg.probe(src).has_audio:
                args += [
                    "-vf",
                    vf,
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-crf",
                    "20",
                    "-c:a",
                    "aac",
                    "-ar",
                    "48000",
                    "-ac",
                    "2",
                    str(out),
                ]
            else:
                args += [
                    "-f",
                    "lavfi",
                    "-i",
                    "anullsrc=r=48000:cl=stereo",
                    "-vf",
                    vf,
                    "-map",
                    "0:v",
                    "-map",
                    "1:a",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-crf",
                    "20",
                    "-c:a",
                    "aac",
                    "-shortest",
                    str(out),
                ]
            ffmpeg.run(args)
            return out
        if isinstance(scene, MapRoute):
            frames = max(1, round(scene.duration_ms / 1000 * self.fps))
            log = self.work / f"scene_{i:03d}.ffmpeg.log"
            proc = ffmpeg.start_rawvideo_pipe(out, w, h, self.fps, log=log)
            try:
                mapframes.route_frames(
                    self.tracks_of(scene.segments),
                    w,
                    h,
                    frames,
                    scene.style,
                    self.tiles,
                    self.opts.tiles_attribution,
                    proc.stdin.write,
                )
            finally:
                proc.stdin.close()
                code = proc.wait()
                if proc.stderr is not None and hasattr(proc.stderr, "close"):
                    proc.stderr.close()
                if code != 0:
                    raise ffmpeg.FfmpegError(f"ffmpeg (mapa) falló: {log.read_text(errors='replace')[-1500:] if log.exists() else code}")
            if self.tiles is not None and self.tiles.failures and self.tiles.hits == 0:
                raise ffmpeg.FfmpegError(f"ninguna tesela pudo descargarse (primer error: {self.tiles.first_error}); revisá --tiles y la clave")
            return out
        if isinstance(scene, VoiceOverlay):
            last = mapframes.route_frames(
                self.tracks_of([scene.segment]),
                w,
                h,
                1,
                "overview",
                self.tiles,
                self.opts.tiles_attribution,
                lambda _b: None,
            )
            img = cards.caption_overlay(last, scene.text or "Nota de voz") if scene.text else last
            return self.still_clip(img, out, scene.duration_ms)
        raise ValueError(f"escena desconocida: {scene}")

    # -- whole video ---------------------------------------------------------------------------
    def offsets(self) -> list[int]:
        off, out = 0, []
        for sc in self.plan.scenes:
            out.append(off)
            off += sc.duration_ms
        return out

    def render(self, out: Path, voice_mode: str = "subtitles") -> Path:
        self.work.mkdir(parents=True, exist_ok=True)
        with ThreadPoolExecutor(max_workers=max(1, self.opts.jobs)) as ex:
            clips = list(ex.map(lambda t: self.render_scene(*t), list(enumerate(self.plan.scenes))))
        joined = self.work / "video_sin_audio.mp4"
        ffmpeg.concat(clips, joined, self.work)
        offsets = self.offsets()
        # Subtitles: voice notes attached to photos and overlays, timed from the scene offsets.
        cues, voice_cues = [], []
        for off, sc in zip(offsets, self.plan.scenes, strict=True):
            vid = getattr(sc, "subtitle_from_voice", None) or (sc.voice if isinstance(sc, VoiceOverlay) else None)
            v = self.voices.get(vid or "")
            if not v:
                continue
            if voice_mode in ("subtitles", "both") and v.transcript:
                cues.append(subtitles.Cue(off, off + sc.duration_ms, v.transcript))
            if voice_mode in ("audio", "both") and v.file:
                voice_cues.append(audio.VoiceCue(self.export / v.file, off, sc.duration_ms))
        with_subs = joined
        if cues:
            ass = self.work / "voces.ass"
            ass.write_text(subtitles.ass(cues, self.w, self.h), encoding="utf-8")
            with_subs = self.work / "video_subtitulado.mp4"
            fonts_dir = ffmpeg.escape_filter_path(cards.FONTS)  # review M4
            ffmpeg.run(
                [
                    "-i",
                    str(joined),
                    "-vf",
                    f"subtitles='{ffmpeg.escape_filter_path(ass)}':fontsdir='{fonts_dir}'",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-crf",
                    "20",
                    "-c:a",
                    "copy",
                    str(with_subs),
                ]
            )
        music = None
        if self.plan.music:
            m = Path(self.plan.music.file)
            music = m if m.is_absolute() else self.export / m
            if not music.exists():
                music = None
        audio.mix(
            with_subs,
            out,
            voice_cues,
            music,
            self.plan.music.volume if self.plan.music else 0.4,
            bool(self.plan.music and self.plan.music.duck_under_voice),
            self.work,
        )
        return out


def load_trip(export_dir: Path) -> Trip:
    return Trip.model_validate_json((export_dir / "trip.json").read_text(encoding="utf-8"))


def load_plan(path: Path) -> VideoPlan:
    return VideoPlan.model_validate(json.loads(path.read_text(encoding="utf-8")))
