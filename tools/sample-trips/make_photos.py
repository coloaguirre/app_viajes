# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10"]
# ///
"""Generate synthetic JPEGs with EXIF for the media-linking E2E (DISENO §17.3).

    uv run tools/sample-trips/make_photos.py tools/sample-trips/continuity/track.gpx out/
    uv run tools/sample-trips/make_photos.py --db bitacora.db [--segment <id-prefix>] out/

With --db the track is read from an app database (pulled from the device), so the
photos line up with a session that was actually recorded.

For the given GPX it writes photos covering every case of DISENO §7.6:
  p1  EXIF time with offset + GPS on the track           -> EXIF, 1.0
  p2  EXIF time with offset, no GPS, inside the track    -> TRACK_INTERPOLATED, 0.8
  p3  EXIF local time (no offset), no GPS                -> EXIF_LOCAL + interpolated
  p4  EXIF GPS 2 km away from the track at that time     -> EXIF, 0.5, needs review
  p5  taken 7 min after the last point, no GPS           -> TRACK_NEAREST
  p6  taken 1 h after the track, no GPS                  -> NONE (review)
  p7  camera clock +8 min, GPS on the track              -> EXIF (0.9 if no track within 2 min)
  p8  no EXIF at all (file time only)                    -> MEDIASTORE time, interpolated
Each file embeds Make/Model so camera-offset correction can group them.
EXIF is written with Pillow's own Exif API: androidx ExifInterface does not find the
GPS IFD in piexif output (measured 2026-10-12), while it reads Pillow's and real
camera files fine.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

from PIL import ExifTags, Image, ImageDraw

NS = {"g": "http://www.topografix.com/GPX/1/1"}
TZ = timezone(timedelta(hours=-3))  # the trip's local zone (Buenos Aires)


def read(gpx):
    pts = []
    for p in ET.parse(gpx).getroot().iterfind(".//g:trkpt", NS):
        t = datetime.fromisoformat(p.find("g:time", NS).text)
        pts.append((t, float(p.get("lat")), float(p.get("lon"))))
    return pts


def read_db(db, segment_prefix=None):
    import sqlite3

    c = sqlite3.connect(db)
    if segment_prefix:
        seg = c.execute(
            "select id from segment where id like ?", (segment_prefix + "%",)
        ).fetchone()[0]
    else:
        seg = c.execute(
            "select id from segment order by started_utc_ms desc limit 1"
        ).fetchone()[0]
    rows = c.execute(
        "select time_utc_ms, lat, lon from track_point where segment_id=? and is_filtered_out=0 order by time_utc_ms",
        (seg,),
    ).fetchall()
    print(f"segment {seg[:8]}: {len(rows)} accepted points", file=sys.stderr)
    return [(datetime.fromtimestamp(t / 1000, UTC), lat, lon) for t, lat, lon in rows]


def dms(v):
    v = abs(v)
    d = int(v)
    m = int((v - d) * 60)
    sec = ((v - d) * 60 - m) * 60
    return (float(d), float(m), round(sec, 4))


def write(
    path,
    when_local,
    offset,
    lat,
    lon,
    label,
    make="Synthetic",
    model="CamA",
    no_exif=False,
):
    img = Image.new("RGB", (640, 480), (30 + hash(label) % 200, 80, 120))
    ImageDraw.Draw(img).text((20, 20), label, fill=(255, 255, 255))
    if no_exif:
        img.save(path, "jpeg", quality=80)
        return
    ex = Image.Exif()
    ex[ExifTags.Base.Make] = make
    ex[ExifTags.Base.Model] = model
    e = ex.get_ifd(ExifTags.IFD.Exif)
    e[ExifTags.Base.DateTimeOriginal] = when_local.strftime("%Y:%m:%d %H:%M:%S")
    if offset is not None:
        e[ExifTags.Base.OffsetTimeOriginal] = offset
    if lat is not None:
        g = ex.get_ifd(ExifTags.IFD.GPSInfo)
        g[ExifTags.GPS.GPSVersionID] = b"\x02\x03\x00\x00"
        g[ExifTags.GPS.GPSLatitudeRef] = "N" if lat >= 0 else "S"
        g[ExifTags.GPS.GPSLatitude] = dms(lat)
        g[ExifTags.GPS.GPSLongitudeRef] = "E" if lon >= 0 else "W"
        g[ExifTags.GPS.GPSLongitude] = dms(lon)
        utc = when_local.astimezone(UTC)
        g[ExifTags.GPS.GPSDateStamp] = utc.strftime("%Y:%m:%d")
        g[ExifTags.GPS.GPSTimeStamp] = (
            float(utc.hour),
            float(utc.minute),
            float(utc.second),
        )
    img.save(path, "jpeg", quality=80, exif=ex.tobytes())


def main():
    args = sys.argv[1:]
    if args and args[0] == "--db":
        db = args[1]
        seg = None
        rest = args[2:]
        if rest and rest[0] == "--segment":
            seg, rest = rest[1], rest[2:]
        out = Path(rest[0])
        out.mkdir(parents=True, exist_ok=True)
        pts = read_db(db, seg)
    else:
        gpx, out = Path(args[0]), Path(args[1])
        out.mkdir(parents=True, exist_ok=True)
        pts = read(gpx)
    tN = pts[-1][0]
    mid = pts[len(pts) // 2]
    q = pts[len(pts) // 4]
    cases = [
        ("p1_exif_gps_on_track", mid[0], "-03:00", mid[1], mid[2]),
        ("p2_exif_offset_no_gps", q[0] + timedelta(seconds=2), "-03:00", None, None),
        ("p3_exif_local_no_gps", mid[0] + timedelta(seconds=30), None, None, None),
        ("p4_exif_gps_far", q[0], "-03:00", q[1] + 0.02, q[2]),
        ("p5_after_track_7min", tN + timedelta(minutes=7), "-03:00", None, None),
        ("p6_after_track_1h", tN + timedelta(hours=1), "-03:00", None, None),
        (
            "p7_camera_clock_plus_8min",
            mid[0] + timedelta(minutes=8),
            "-03:00",
            mid[1],
            mid[2],
        ),
        ("p8_no_exif", q[0] + timedelta(seconds=40), None, None, None),
    ]
    for name, when_utc, offset, lat, lon in cases:
        when_local = when_utc.astimezone(TZ)
        model = "CamB" if name.startswith("p7") else "CamA"
        write(
            out / f"{name}.jpg",
            when_local,
            offset,
            lat,
            lon,
            name,
            model=model,
            no_exif=name.startswith("p8"),
        )
        print(
            f"{name}: local {when_local:%H:%M:%S} offset={offset} gps={'yes' if lat else 'no'}"
        )


if __name__ == "__main__":
    main()
