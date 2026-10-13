"""`bitacora-render plan|render|preview` (DISENO §13.5)."""

from __future__ import annotations

import json
import shutil
import tempfile
from pathlib import Path

import typer

from . import elevation, ffmpeg, planner, render

app = typer.Typer(help="Renderizador de escritorio de Bitácora de Viaje", no_args_is_help=True)


@app.command("plan")
def plan_cmd(
    export_dir: Path = typer.Argument(..., help="Carpeta exportada por la app (contiene trip.json)"),
    duration: int = typer.Option(180, "--duration", help="Duración objetivo en segundos (60, 180, 300, 600)"),
    aspect: str = typer.Option("16:9", "--aspect", help="16:9 o 9:16"),
    voice: str = typer.Option("subtitles", "--voice", help="subtitles | audio | both"),
    music: Path | None = typer.Option(None, "--music", help="Archivo de música del usuario"),
    segment: list[str] = typer.Option([], "--segment", help="Limitar a estas etapas (id de trip.json)"),
    no_places: bool = typer.Option(False, "--no-places"),
    no_map: bool = typer.Option(False, "--no-map"),
    no_videos: bool = typer.Option(False, "--no-videos"),
    dem: bool = typer.Option(False, "--dem", help="Corregir altitudes con un modelo de elevación (OpenTopoData, requiere internet)"),
    out: Path = typer.Option(Path("plan.json"), "-o", "--out"),
):
    """Genera video_plan.json desde trip.json con las mismas reglas que la app."""
    trip = render.load_trip(export_dir)
    if dem:
        _apply_dem(trip)
    params = planner.PlanParams(
        duration,
        aspect,
        voice,
        not no_places,
        not no_map,
        not no_videos,
        str(music) if music else None,
        frozenset(segment) or None,
    )
    p = planner.plan(planner.plan_input_of(trip), params)
    out.write_text(p.to_json() + "\n", encoding="utf-8")
    typer.echo(f"Plan de {p.total_duration_ms / 1000:.1f} s con {len(p.scenes)} escenas → {out}")


def _apply_dem(trip) -> None:
    provider = elevation.OpenTopoData(Path.home() / ".cache" / "bitacora-render" / "dem")
    for s in trip.segments:
        pts = [p for p in s.points if not p.filtered]
        if len(pts) < 2:
            continue
        alts = elevation.correct([(p.lat, p.lon, p.alt_m) for p in pts], provider, sample_every=max(1, len(pts) // 400))
        known = [a for a in alts if a is not None]
        if known:
            gain, _loss = elevation.elevation_gain_loss(known)
            s.stats.elevation_gain_m = gain
            s.stats.max_alt_m = max(known)


@app.command("render")
def render_cmd(
    export_dir: Path = typer.Argument(...),
    plan_file: Path = typer.Argument(...),
    out: Path = typer.Option(Path("resumen.mp4"), "-o", "--out"),
    jobs: int = typer.Option(2, "--jobs"),
    keep_work: bool = typer.Option(False, "--keep-work"),
    preview_scale: float = typer.Option(1.0, "--preview-scale", help="0.25 para una prueba rápida en baja resolución"),
    tiles: str | None = typer.Option(
        None,
        "--tiles",
        help="Plantilla de teselas con clave, p.ej. https://api.maptiler.com/maps/outdoor-v2/256/{z}/{x}/{y}.png?key=…",
    ),
    tiles_attribution: str = typer.Option("© MapTiler © OpenStreetMap contributors", "--tiles-attribution"),
    voice: str = typer.Option("subtitles", "--voice", help="subtitles | audio | both"),
):
    """Renderiza el MP4 (H.264 + AAC) a partir del plan."""
    if not ffmpeg.available():
        raise typer.BadParameter("hace falta ffmpeg/ffprobe en el PATH")
    trip = render.load_trip(export_dir)
    plan = render.load_plan(plan_file)
    work = Path(tempfile.mkdtemp(prefix="bitacora-render-")) if not keep_work else out.with_suffix("").parent / (out.stem + "_work")
    opts = render.RenderOptions(
        preview_scale=preview_scale,
        jobs=jobs,
        keep_work=keep_work,
        tiles_template=tiles,
        tiles_attribution=tiles_attribution if tiles else render.ATTRIBUTION_OSM,
    )
    try:
        render.Renderer(export_dir, trip, plan, opts, work).render(out, voice_mode=voice)
    finally:
        if not keep_work:
            shutil.rmtree(work, ignore_errors=True)
    info = ffmpeg.probe(out)
    typer.echo(f"{out}: {info.duration_s:.1f} s, {info.width}x{info.height}, audio={'sí' if info.has_audio else 'no'}")


@app.command("preview")
def preview_cmd(
    export_dir: Path = typer.Argument(...),
    plan_file: Path = typer.Argument(...),
    scene: int = typer.Option(..., "--scene", help="Índice de la escena (desde 0)"),
    out: Path = typer.Option(Path("cuadro.png"), "-o", "--out"),
    preview_scale: float = typer.Option(0.5, "--preview-scale"),
):
    """Un cuadro de una escena, para revisar rápido."""
    trip = render.load_trip(export_dir)
    plan = render.load_plan(plan_file)
    work = Path(tempfile.mkdtemp(prefix="bitacora-preview-"))
    try:
        r = render.Renderer(export_dir, trip, plan, render.RenderOptions(preview_scale=preview_scale, jobs=1), work)
        clip = r.render_scene(scene, plan.scenes[scene])
        ffmpeg.run(["-ss", f"{plan.scenes[scene].duration_ms / 2000:.3f}", "-i", str(clip), "-frames:v", "1", str(out)])
    finally:
        shutil.rmtree(work, ignore_errors=True)
    typer.echo(json.dumps({"scene": scene, "type": plan.scenes[scene].type, "png": str(out)}))


if __name__ == "__main__":
    app()
