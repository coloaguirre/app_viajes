"""Pydantic models of the two contracts: trip.json (DISENO §12.2) and video_plan.json (§13.3).

trip.json is read permissively (unknown keys ignored, every field optional where the app may omit
it); the plan is written strictly so it validates against docs/video-plan.schema.json.
"""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field


class _Lenient(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Stats(_Lenient):
    distance_m: float | None = None
    moving_time_ms: int | None = None
    total_time_ms: int | None = None
    elevation_gain_m: float | None = None
    elevation_loss_m: float | None = None
    max_alt_m: float | None = None
    min_alt_m: float | None = None


class Point(_Lenient):
    t_utc_ms: int
    lat: float
    lon: float
    alt_m: float | None = None
    filtered: bool = False


class Media(_Lenient):
    id: str
    kind: str = "PHOTO"
    file: str | None = None
    display_name: str | None = None
    width: int | None = None
    height: int | None = None
    duration_ms: int | None = None
    taken_utc_ms: int
    lat: float | None = None
    lon: float | None = None
    caption: str | None = None
    favorite: bool = False
    excluded: bool = False


class VoiceNote(_Lenient):
    id: str
    started_utc_ms: int
    duration_ms: int = 0
    lat: float | None = None
    lon: float | None = None
    file: str | None = None
    transcript: str | None = None


class TextNote(_Lenient):
    id: str
    kind: str
    time_utc_ms: int
    lat: float | None = None
    lon: float | None = None
    text: str | None = None


class Pause(_Lenient):
    id: str
    kind: str = "USER"
    paused_utc_ms: int
    resumed_utc_ms: int | None = None


class Stop(_Lenient):
    id: str
    start_utc_ms: int
    end_utc_ms: int
    lat: float
    lon: float
    user_label: str | None = None


class WeatherSample(_Lenient):
    id: str
    time_utc_ms: int
    temperature_c: float | None = None
    weather_code: int | None = None


class Segment(_Lenient):
    id: str
    type: str = "STAGE"
    transport: str | None = None
    title: str | None = None
    auto_name: str | None = None
    started: str | None = None
    started_utc_ms: int
    ended_utc_ms: int | None = None
    tz_id: str = "UTC"
    stats: Stats = Field(default_factory=Stats)
    points: list[Point] = Field(default_factory=list)
    pauses: list[Pause] = Field(default_factory=list)
    stops: list[Stop] = Field(default_factory=list)
    media: list[Media] = Field(default_factory=list)
    voice_notes: list[VoiceNote] = Field(default_factory=list)
    text_notes: list[TextNote] = Field(default_factory=list)
    weather: list[WeatherSample] = Field(default_factory=list)

    @property
    def display_name(self) -> str | None:
        return self.title or self.auto_name

    @property
    def date(self) -> str:
        """Local date of the start (the `started` field carries the segment's offset)."""
        return (self.started or "")[:10]


class Visit(_Lenient):
    segment_id: str
    first_seen_utc_ms: int
    dwell_ms: int | None = None
    relevance: float | None = None


class Place(_Lenient):
    id: str
    name: str
    lat: float
    lon: float
    kind: str | None = None
    visits: list[Visit] = Field(default_factory=list)


class Enrichment(_Lenient):
    place_id: str
    source: str
    license: str = ""
    text: str
    id: str | None = None
    lang: str | None = None
    title: str | None = None
    url: str | None = None
    user_approved: bool = False
    llm_rewritten: bool = False


class TripInfo(_Lenient):
    id: str
    title: str
    description: str | None = None
    cover_media_id: str | None = None
    started_utc_ms: int | None = None
    ended_utc_ms: int | None = None
    tz_id: str = "UTC"


class Attribution(_Lenient):
    name: str
    license: str
    url: str


class Trip(_Lenient):
    schema_version: int
    app_version: str = ""
    exported_utc: str = ""
    trip: TripInfo
    segments: list[Segment]
    places: list[Place] = Field(default_factory=list)
    enrichments: list[Enrichment] = Field(default_factory=list)
    attributions: list[Attribution] = Field(default_factory=list)

    def places_of(self, segment_id: str) -> list[tuple[Place, Visit]]:
        """Places of one segment, best ranked first (same rule as core/export TripExport.placesOf)."""
        out = []
        for p in self.places:
            for v in p.visits:
                if v.segment_id == segment_id:
                    out.append((p, v))
        out.sort(key=lambda pv: -(pv[1].relevance or 0.0))
        return out

    def enrichments_of(self, place_id: str) -> list[Enrichment]:
        return [e for e in self.enrichments if e.place_id == place_id]

    def media_by_id(self) -> dict[str, Media]:
        return {m.id: m for s in self.segments for m in s.media}

    def segment_by_id(self) -> dict[str, Segment]:
        return {s.id: s for s in self.segments}


# ---------------------------------------------------------------- video plan


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Output(_Strict):
    width: int
    height: int
    fps: int = 30
    format: str = "mp4-h264-aac"


class Music(_Strict):
    file: str
    volume: float = 0.4
    duck_under_voice: bool = True


class KenBurns(_Strict):
    from_: list[float] = Field(alias="from", serialization_alias="from")
    to: list[float]
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class Title(_Strict):
    type: Literal["title"] = "title"
    duration_ms: int
    title: str
    subtitle: str | None = None
    background_media: str | None = None


class MapRoute(_Strict):
    type: Literal["map_route"] = "map_route"
    duration_ms: int
    segments: list[str]
    style: Literal["overview", "follow"]


class Chapter(_Strict):
    type: Literal["chapter"] = "chapter"
    duration_ms: int
    title: str
    date: str | None = None
    segment: str
    weather: str | None = None


class Photo(_Strict):
    type: Literal["photo"] = "photo"
    duration_ms: int
    media: str
    kenburns: KenBurns
    caption: str | None = None
    subtitle_from_voice: str | None = None


class VideoClip(_Strict):
    type: Literal["video_clip"] = "video_clip"
    media: str
    trim_start_ms: int
    trim_end_ms: int
    keep_audio: bool = True
    caption: str | None = None

    @property
    def duration_ms(self) -> int:
        return self.trim_end_ms - self.trim_start_ms


class VoiceOverlay(_Strict):
    type: Literal["voice_overlay"] = "voice_overlay"
    duration_ms: int
    voice: str
    segment: str
    text: str | None = None


class PlaceCard(_Strict):
    type: Literal["place_card"] = "place_card"
    duration_ms: int
    place: str
    title: str
    text: str
    source: str
    url: str | None = None


class StatsScene(_Strict):
    type: Literal["stats"] = "stats"
    duration_ms: int
    segment: str | None = None
    distance_m: float | None = None
    moving_time_ms: int | None = None
    elevation_gain_m: float | None = None
    max_alt_m: float | None = None
    photos: int | None = None
    days: int | None = None
    places: int | None = None


class Credits(_Strict):
    type: Literal["credits"] = "credits"
    duration_ms: int
    lines: list[str]


Scene = Annotated[
    Title | MapRoute | Chapter | Photo | VideoClip | VoiceOverlay | PlaceCard | StatsScene | Credits,
    Field(discriminator="type"),
]


class VideoPlan(_Strict):
    schema_version: int = 1
    output: Output
    music: Music | None = None
    scenes: list[Scene]
    total_duration_ms: int = 0

    def to_json(self) -> str:
        import json

        return json.dumps(self.model_dump(mode="json", by_alias=True, exclude_none=True), ensure_ascii=False, indent=2)

    @classmethod
    def from_json(cls, text: str) -> VideoPlan:
        return cls.model_validate_json(text)
