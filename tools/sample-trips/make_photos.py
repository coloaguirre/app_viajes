# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow>=10", "piexif>=1.1"]
# ///
"""Generate synthetic JPEGs with EXIF for the media-linking E2E (DISENO §17.3).

    uv run tools/sample-trips/make_photos.py tools/sample-trips/continuity/track.gpx out/

For the given GPX it writes photos covering every case of DISENO §7.6:
  p1  EXIF time with offset + GPS on the track           -> EXIF, 1.0
  p2  EXIF time with offset, no GPS, inside the track    -> TRACK_INTERPOLATED, 0.8
  p3  EXIF local time (no offset), no GPS                -> EXIF_LOCAL + interpolated
  p4  EXIF GPS 2 km away from the track at that time     -> EXIF, 0.5, needs review
  p5  taken 7 min after the last point, no GPS           -> TRACK_NEAREST
  p6  taken 1 h after the track, no GPS                  -> NONE (review)
  p7  camera clock +1 h, GPS on the track                -> EXIF far from track time -> review
Each file embeds Make/Model so camera-offset correction can group them.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import piexif
from PIL import Image, ImageDraw

NS = {"g": "http://www.topografix.com/GPX/1/1"}
TZ = timezone(timedelta(hours=-3))  # the trip's local zone (Buenos Aires)


def read(gpx):
    pts = []
    for p in ET.parse(gpx).getroot().iterfind(".//g:trkpt", NS):
        t = datetime.fromisoformat(p.find("g:time", NS).text)
        pts.append((t, float(p.get("lat")), float(p.get("lon"))))
    return pts


def deg_to_dms(v):
    v = abs(v)
    d = int(v)
    m = int((v - d) * 60)
    s = round(((v - d) * 60 - m) * 60 * 10000)
    return ((d, 1), (m, 1), (s, 10000))


def write(path, when_local, offset, lat, lon, label, make="Synthetic", model="CamA"):
    img = Image.new("RGB", (640, 480), (30 + hash(label) % 200, 80, 120))
    ImageDraw.Draw(img).text((20, 20), label, fill=(255, 255, 255))
    exif = {"0th": {piexif.ImageIFD.Make: make, piexif.ImageIFD.Model: model}, "Exif": {}, "GPS": {}}
    exif["Exif"][piexif.ExifIFD.DateTimeOriginal] = when_local.strftime("%Y:%m:%d %H:%M:%S")
    if offset is not None:
        exif["Exif"][piexif.ExifIFD.OffsetTimeOriginal] = offset
    if lat is not None:
        exif["GPS"][piexif.GPSIFD.GPSLatitudeRef] = "N" if lat >= 0 else "S"
        exif["GPS"][piexif.GPSIFD.GPSLatitude] = deg_to_dms(lat)
        exif["GPS"][piexif.GPSIFD.GPSLongitudeRef] = "E" if lon >= 0 else "W"
        exif["GPS"][piexif.GPSIFD.GPSLongitude] = deg_to_dms(lon)
        utc = when_local.astimezone(UTC)
        exif["GPS"][piexif.GPSIFD.GPSDateStamp] = utc.strftime("%Y:%m:%d")
        exif["GPS"][piexif.GPSIFD.GPSTimeStamp] = ((utc.hour, 1), (utc.minute, 1), (utc.second, 1))
    img.save(path, "jpeg", quality=80, exif=piexif.dump(exif))


def main():
    gpx, out = Path(sys.argv[1]), Path(sys.argv[2])
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
        ("p7_camera_clock_plus_1h", mid[0] + timedelta(hours=1), "-03:00", mid[1], mid[2]),
    ]
    for name, when_utc, offset, lat, lon in cases:
        when_local = when_utc.astimezone(TZ)
        model = "CamB" if name.startswith("p7") else "CamA"
        write(out / f"{name}.jpg", when_local, offset, lat, lon, name, model=model)
        print(f"{name}: local {when_local:%H:%M:%S} offset={offset} gps={'yes' if lat else 'no'}")


if __name__ == "__main__":
    main()
