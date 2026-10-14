"""Port of core/videoplan Planner (Kotlin). Same rules, same numbers: tools/plan-fixtures are the proof.

Keep this file in step with core/videoplan/src/main/kotlin/.../Planner.kt; a change on one side
must be mirrored on the other and the fixtures regenerated on the Kotlin side.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .model import (
    Chapter,
    Credits,
    KenBurns,
    MapRoute,
    Music,
    Output,
    Photo,
    PlaceCard,
    StatsScene,
    Title,
    Trip,
    VideoClip,
    VideoPlan,
    VoiceOverlay,
)

TITLE_MS = 5_000
OVERVIEW_MAP_MS = 7_000
CREDITS_MS = 6_000
CHAPTER_MS = 2_500
SEGMENT_MAP_MS = 5_000
STATS_MS = 4_000
PHOTO_MS = 3_500
PHOTO_SHORT_MS = 2_500
PHOTO_MAX_MS = 5_000
PLACE_CARD_MS = 5_000
PLACE_CARD_MAX_MS = 8_000
MAP_MAX_MS = 12_000
CLIP_MAX_MS = 6_000
CLIP_MIN_MS = 2_000
VOICE_OVERLAY_MAX_MS = 8_000
HIGHLIGHT_WINDOW_MS = 2 * 60_000
VOICE_WINDOW_MS = 3 * 60_000
MANY_PHOTOS = 12
TOLERANCE = 0.05
PHASH_DUPLICATE_BITS = 6
ASPECTS = {"16:9": (1920, 1080), "9:16": (1080, 1920)}
DURATIONS_S = (60, 180, 300, 600)


@dataclass(frozen=True)
class PlanParams:
    target_duration_s: int
    aspect: str = "16:9"
    voice: str = "subtitles"  # subtitles | audio | both
    include_places: bool = True
    include_map: bool = True
    include_videos: bool = True
    music_file: str | None = None
    music_volume: float = 0.4
    segment_ids: frozenset[str] | None = None

    @staticmethod
    def quick_one_minute(segment_id: str, aspect: str = "16:9", music_file: str | None = None) -> PlanParams:
        return PlanParams(60, aspect, "subtitles", True, True, False, music_file, 0.4, frozenset({segment_id}))

    @staticmethod
    def from_dict(d: dict) -> PlanParams:
        ids = d.get("segment_ids")
        return PlanParams(
            int(d["target_duration_s"]),
            d.get("aspect", "16:9"),
            d.get("voice", "subtitles"),
            bool(d.get("include_places", True)),
            bool(d.get("include_map", True)),
            bool(d.get("include_videos", True)),
            d.get("music_file"),
            float(d.get("music_volume", 0.4)),
            frozenset(ids) if ids is not None else None,
        )


@dataclass
class PlanMedia:
    id: str
    is_video: bool
    taken_utc_ms: int
    duration_ms: int | None = None
    is_favorite: bool = False
    is_excluded: bool = False
    caption: str | None = None
    phash: int | None = None
    sharpness: float | None = None


@dataclass
class PlanVoice:
    id: str
    started_utc_ms: int
    duration_ms: int
    text: str | None


@dataclass
class PlanPlace:
    id: str
    name: str
    text: str
    source_name: str
    license: str | None
    url: str | None


@dataclass
class PlanSegment:
    id: str
    title: str
    date: str
    started_utc_ms: int
    ended_utc_ms: int | None
    distance_m: float | None = None
    moving_time_ms: int | None = None
    elevation_gain_m: float | None = None
    max_alt_m: float | None = None
    has_track: bool = True
    media: list[PlanMedia] = field(default_factory=list)
    voice_notes: list[PlanVoice] = field(default_factory=list)
    highlights_utc_ms: list[int] = field(default_factory=list)
    places: list[PlanPlace] = field(default_factory=list)
    weather: str | None = None


@dataclass
class PlanInput:
    trip_id: str
    title: str
    cover_media_id: str | None
    segments: list[PlanSegment]
    extra_credits: list[str] = field(default_factory=list)


_KIND = {"TREKKING": "Trekking", "EXCURSION": "Excursión"}

# WMO codes in Spanish and the coarse families, mirror of core/model WeatherCodes (keep both in step).
_WMO = {
    0: "Despejado",
    1: "Mayormente despejado",
    2: "Parcialmente nublado",
    3: "Nublado",
    45: "Niebla",
    48: "Niebla con escarcha",
    51: "Llovizna",
    53: "Llovizna",
    55: "Llovizna",
    56: "Llovizna helada",
    57: "Llovizna helada",
    61: "Lluvia ligera",
    63: "Lluvia",
    65: "Lluvia intensa",
    66: "Lluvia helada",
    67: "Lluvia helada",
    71: "Nevada ligera",
    73: "Nevada",
    75: "Nevada intensa",
    77: "Granos de nieve",
    80: "Chaparrones ligeros",
    81: "Chaparrones",
    82: "Chaparrones fuertes",
    85: "Chaparrones de nieve",
    86: "Chaparrones de nieve",
    95: "Tormenta",
    96: "Tormenta con granizo",
    99: "Tormenta con granizo",
}


def _family(code: int) -> str:
    if code in (0, 1):
        return "CLEAR"
    if code in (2, 3):
        return "CLOUDY"
    if code in (45, 48):
        return "FOG"
    if 51 <= code <= 57 or 61 <= code <= 67 or 80 <= code <= 82:
        return "RAIN"
    if 71 <= code <= 77 or code in (85, 86):
        return "SNOW"
    if code in (95, 96, 99):
        return "STORM"
    return "UNKNOWN"


def _dominant(codes: list[int], min_share: float = 0.3) -> int | None:
    """Mirror of WeatherCodes.dominant: the worst family covering ≥ 30 % of the hours, else the most frequent code."""
    if not codes:
        return None

    def most_frequent(cs: list[int]) -> int:
        counts: dict[int, int] = {}
        for c in cs:
            counts[c] = counts.get(c, 0) + 1
        best = max(counts.values())
        return next(c for c in cs if counts[c] == best)

    for fam in ("STORM", "SNOW", "RAIN", "FOG", "CLOUDY", "CLEAR"):
        hits = [c for c in codes if _family(c) == fam]
        if hits and len(hits) >= len(codes) * min_share:
            return most_frequent(hits)
    return most_frequent(codes)


def _round_half_up(x: float) -> int:
    return int(math.floor(x + 0.5))


def weather_text(samples) -> str | None:
    """Mirror of core/timeline WeatherDigest.segmentText: dominant weather plus the temperature range."""
    if not samples:
        return None
    code = _dominant([s.weather_code for s in samples if s.weather_code is not None])
    temps = [s.temperature_c for s in samples if s.temperature_c is not None]
    if code is None and not temps:
        return None
    text = _WMO.get(code, "Sin dato") if code is not None else ""
    if temps:
        lo, hi = _round_half_up(min(temps)), _round_half_up(max(temps))
        if text:
            text += ", "
        text += f"{lo} °C" if lo == hi else f"{lo}–{hi} °C"
    return text


def plan_input_of(trip: Trip, extra_credits: list[str] | None = None) -> PlanInput:
    """Mirror of core/export PlanInputs.of: the same trip.json gives the same planner input."""
    segments = []
    for s in sorted(trip.segments, key=lambda x: x.started_utc_ms):
        places = []
        for p, _v in trip.places_of(s.id):
            texts = [e for e in trip.enrichments_of(p.id) if e.user_approved]
            texts.sort(key=lambda e: 0 if e.source == "Wikipedia" else 1)
            for e in texts:
                places.append(PlanPlace(p.id, p.name, e.text, e.source, e.license or None, e.url or None))
        segments.append(
            PlanSegment(
                id=s.id,
                title=s.display_name or _KIND.get(s.type, "Etapa"),
                date=s.date,
                started_utc_ms=s.started_utc_ms,
                ended_utc_ms=s.ended_utc_ms,
                distance_m=s.stats.distance_m,
                moving_time_ms=s.stats.moving_time_ms,
                elevation_gain_m=s.stats.elevation_gain_m,
                max_alt_m=s.stats.max_alt_m,
                has_track=sum(1 for p in s.points if not p.filtered) >= 2,
                media=[
                    PlanMedia(m.id, m.kind == "VIDEO", m.taken_utc_ms, m.duration_ms, m.favorite, m.excluded, m.caption)
                    for m in sorted(s.media, key=lambda m: m.taken_utc_ms)
                ],
                voice_notes=[
                    PlanVoice(v.id, v.started_utc_ms, v.duration_ms, v.transcript) for v in sorted(s.voice_notes, key=lambda v: v.started_utc_ms)
                ],
                highlights_utc_ms=sorted(n.time_utc_ms for n in s.text_notes if n.kind == "HIGHLIGHT"),
                places=places,
                weather=weather_text(s.weather),
            )
        )
    return PlanInput(trip.trip.id, trip.trip.title, trip.trip.cover_media_id, segments, list(extra_credits or []))


@dataclass
class _Candidate:
    media: PlanMedia
    score: float
    voice: PlanVoice | None


def _round_half_up(x: float) -> int:
    return int(math.floor(x + 0.5))


def ken_burns(i: int) -> KenBurns:
    k = i % 4
    if k == 0:
        return KenBurns(**{"from": [0.0, 0.0, 1.0], "to": [0.05, 0.05, 1.15]})
    if k == 1:
        return KenBurns(**{"from": [0.05, 0.05, 1.15], "to": [0.0, 0.0, 1.0]})
    if k == 2:
        return KenBurns(**{"from": [0.0, 0.02, 1.1], "to": [0.1, 0.02, 1.1]})
    return KenBurns(**{"from": [0.1, 0.02, 1.1], "to": [0.0, 0.02, 1.1]})


def _eligible(s: PlanSegment, params: PlanParams) -> list[PlanMedia]:
    base = sorted(
        (m for m in s.media if not m.is_excluded and (params.include_videos or not m.is_video)),
        key=lambda m: m.taken_utc_ms,
    )
    kept: list[PlanMedia] = []
    for m in base:
        dup = m.phash is not None and any(
            k.phash is not None and bin((k.phash ^ m.phash) & 0xFFFFFFFFFFFFFFFF).count("1") <= PHASH_DUPLICATE_BITS for k in kept
        )  # 64-bit two's complement like Long.bitCount (review M7)
        if not dup:
            kept.append(m)
    return kept


def _score(s: PlanSegment, media: list[PlanMedia]) -> list[_Candidate]:
    out = []
    for m in media:
        near_highlight = any(abs(h - m.taken_utc_ms) <= HIGHLIGHT_WINDOW_MS for h in s.highlights_utc_ms)
        voices = [v for v in s.voice_notes if v.text and v.text.strip() and abs(v.started_utc_ms - m.taken_utc_ms) <= VOICE_WINDOW_MS]
        voice = min(voices, key=lambda v: abs(v.started_utc_ms - m.taken_utc_ms)) if voices else None
        score = (
            (3.0 if m.is_favorite else 0.0)
            + (2.0 if near_highlight else 0.0)
            + (1.5 if voice else 0.0)
            + (m.sharpness if m.sharpness is not None else 0.5)
        )
        out.append(_Candidate(m, score, voice))
    return out


def _pick(candidates: list[_Candidate], slots: int, s: PlanSegment) -> list[_Candidate]:
    if slots <= 0 or not candidates:
        return []
    n = min(slots, len(candidates))
    start = s.started_utc_ms
    end = max(s.ended_utc_ms if s.ended_utc_ms is not None else candidates[-1].media.taken_utc_ms, start + 1)
    window = max(1, (end - start) // n)
    chosen: list[_Candidate] = []
    for w in range(n):
        lo = start + w * window
        hi = float("inf") if w == n - 1 else lo + window
        pool = [c for c in candidates if lo <= c.media.taken_utc_ms < hi and c not in chosen]
        if pool:
            # Kotlin: maxWithOrNull(compareBy { score }.thenBy { takenUtcMs }) → on a tie the later photo wins (review I1).
            chosen.append(max(pool, key=lambda c: (c.score, c.media.taken_utc_ms)))
    for c in sorted(candidates, key=lambda c: (-c.score, c.media.taken_utc_ms)):
        if len(chosen) >= n:
            break
        if c not in chosen:
            chosen.append(c)
    return sorted(chosen, key=lambda c: c.media.taken_utc_ms)


def _subtitle_of(segments: list[PlanSegment]) -> str | None:
    if not segments:
        return None
    first, last = segments[0].date, segments[-1].date
    return first if first == last else f"{first} — {last}"


def _credits(inp: PlanInput, segments: list[PlanSegment], params: PlanParams) -> list[str]:
    lines = ["Datos del mapa © colaboradores de OpenStreetMap (ODbL)"]
    if params.include_places:
        seen = set()
        all_places = [p for s in segments for p in s.places]
        for p in all_places:
            if p.source_name != "Wikipedia":
                continue
            key = p.url or p.name
            if key in seen:
                continue
            seen.add(key)
            lines.append(f"Texto: Wikipedia — {p.name} ({p.license or 'CC BY-SA 4.0'})" + (f" {p.url}" if p.url else ""))
        if any(p.source_name == "Wikidata" for p in all_places):
            lines.append("Datos: Wikidata (CC0)")
    if any(s.weather is not None for s in segments):
        lines.append("Datos meteorológicos: Open-Meteo (CC BY 4.0)")
    lines.extend(inp.extra_credits)
    lines.append("Hecho con Bitácora de Viaje")
    return lines


def _fit(scenes: list, photo_scores: list[tuple[Photo, float]], budget: int) -> list:
    out = list(scenes)

    def total() -> int:
        return sum(sc.duration_ms for sc in out)

    # Kotlin: sortedWith(compareBy { score }.thenByDescending { media }) — stable two-pass sort.
    by_score = sorted(photo_scores, key=lambda ps: ps[0].media, reverse=True)
    by_score = sorted(by_score, key=lambda ps: ps[1])
    i = 0
    while total() > budget * (1 + TOLERANCE) and i < len(by_score):
        victim = by_score[i][0]
        for idx, sc in enumerate(out):
            if sc is victim:
                del out[idx]
                break
        i += 1

    def stretch(kind, cap_attr):
        for _ in range(8):
            room = [(idx, cap_attr - sc.duration_ms) for idx, sc in enumerate(out) if isinstance(sc, kind) and cap_attr - sc.duration_ms > 0]
            deficit = budget - total()
            if deficit <= 0 or not room:
                return
            extra = min(min(r for _, r in room), deficit // len(room))
            if extra <= 0:
                return
            for idx, _ in room:
                out[idx] = out[idx].model_copy(update={"duration_ms": out[idx].duration_ms + extra})

    stretch(Photo, PHOTO_MAX_MS)
    stretch(MapRoute, MAP_MAX_MS)
    stretch(PlaceCard, PLACE_CARD_MAX_MS)
    return out


def plan(inp: PlanInput, params: PlanParams) -> VideoPlan:
    if params.target_duration_s <= 0:
        raise ValueError("duration must be positive")
    segments = sorted(
        (s for s in inp.segments if params.segment_ids is None or s.id in params.segment_ids),
        key=lambda s: s.started_utc_ms,
    )
    budget = params.target_duration_s * 1000
    multi = len(segments) > 1
    w, h = ASPECTS[params.aspect]
    output = Output(width=w, height=h)
    music = Music(file=params.music_file, volume=params.music_volume) if params.music_file else None

    fixed = TITLE_MS + CREDITS_MS
    if params.include_map and multi:
        fixed += OVERVIEW_MAP_MS
    for s in segments:
        fixed += CHAPTER_MS + STATS_MS + (SEGMENT_MAP_MS if params.include_map and s.has_track else 0)
    flexible = max(0, budget - fixed)

    eligible = {s.id: _eligible(s, params) for s in segments}
    richness = {
        s.id: 1.0 * len(eligible[s.id])
        + 2.0 * len(s.highlights_utc_ms)
        + 1.5 * len(s.voice_notes)
        + 2.0 * (len(s.places) if params.include_places else 0)
        for s in segments
    }
    total_richness = sum(richness.values())
    share = {
        s.id: (flexible // max(1, len(segments)) if total_richness <= 0.0 else _round_half_up(flexible * richness[s.id] / total_richness))
        for s in segments
    }

    scenes: list = []
    all_eligible = [m for s in segments for m in eligible[s.id]]
    cover = inp.cover_media_id or next((m.id for m in all_eligible if m.is_favorite), None) or (all_eligible[0].id if all_eligible else None)
    scenes.append(Title(duration_ms=TITLE_MS, title=inp.title, subtitle=_subtitle_of(segments), background_media=cover))
    if params.include_map and multi:
        scenes.append(MapRoute(duration_ms=OVERVIEW_MAP_MS, segments=[s.id for s in segments if s.has_track], style="overview"))
    photo_scores: list[tuple[Photo, float]] = []
    ken_index = 0
    for s in segments:
        scenes.append(Chapter(duration_ms=CHAPTER_MS, title=s.title, date=s.date, segment=s.id, weather=s.weather))
        if params.include_map and s.has_track:
            scenes.append(MapRoute(duration_ms=SEGMENT_MAP_MS, segments=[s.id], style="follow" if multi else "overview"))
        remaining = share[s.id]
        if params.include_places:
            for p in s.places:
                if remaining < PLACE_CARD_MS:
                    break
                scenes.append(
                    PlaceCard(
                        duration_ms=PLACE_CARD_MS,
                        place=p.id,
                        title=p.name,
                        text=p.text,
                        source=p.source_name + (f" ({p.license})" if p.license else ""),
                        url=p.url,
                    )
                )
                remaining -= PLACE_CARD_MS
        candidates = _score(s, eligible[s.id])
        photo_ms = PHOTO_SHORT_MS if len(candidates) > MANY_PHOTOS else PHOTO_MS
        slots = remaining // photo_ms
        chosen = _pick(candidates, slots, s)
        used_voices: set[str] = set()
        for c in chosen:
            if c.media.is_video:
                d = c.media.duration_ms if c.media.duration_ms is not None else CLIP_MAX_MS
                length = min(CLIP_MAX_MS, max(CLIP_MIN_MS, d))
                start = max(0, (d - length) // 2)
                scenes.append(
                    VideoClip(
                        media=c.media.id,
                        trim_start_ms=start,
                        trim_end_ms=start + length,
                        keep_audio=True,
                        caption=c.media.caption,
                    )
                )
            else:
                voice_id = None
                # Set in every mode: the engine decides subtitle vs audio (review B2 of PR #11).
                if c.voice is not None and c.voice.id not in used_voices:
                    used_voices.add(c.voice.id)
                    voice_id = c.voice.id
                photo = Photo(
                    duration_ms=photo_ms,
                    media=c.media.id,
                    kenburns=ken_burns(ken_index),
                    caption=c.media.caption,
                    subtitle_from_voice=voice_id,
                )
                ken_index += 1
                scenes.append(photo)
                photo_scores.append((photo, c.score))
        left = remaining - len(chosen) * photo_ms
        for v in sorted(s.voice_notes, key=lambda v: v.started_utc_ms):
            if v.id in used_voices:
                continue
            if (not v.text or not v.text.strip()) and params.voice == "subtitles":
                continue
            d = min(VOICE_OVERLAY_MAX_MS, max(3_000, v.duration_ms))
            if left < d:
                break
            scenes.append(VoiceOverlay(duration_ms=d, voice=v.id, segment=s.id, text=v.text))
            left -= d
        scenes.append(
            StatsScene(
                duration_ms=STATS_MS,
                segment=s.id,
                distance_m=s.distance_m,
                moving_time_ms=s.moving_time_ms,
                elevation_gain_m=s.elevation_gain_m,
                max_alt_m=s.max_alt_m,
                photos=sum(1 for c in chosen if not c.media.is_video),
            )
        )
    scenes.append(Credits(duration_ms=CREDITS_MS, lines=_credits(inp, segments, params)))
    fitted = _fit(scenes, photo_scores, budget)
    return VideoPlan(output=output, music=music, scenes=fitted, total_duration_ms=sum(sc.duration_ms for sc in fitted))
