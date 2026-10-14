"""weather_text must match core/timeline WeatherDigest.segmentText (same cases as WeatherDigestTest)."""

from types import SimpleNamespace

from bitacora_render.planner import weather_text


def s(code, temp):
    return SimpleNamespace(weather_code=code, temperature_c=temp)


def test_ties_rounding_and_missing_data_match_kotlin():
    assert weather_text([s(61, 10.4), s(63, 12.5)]) == "Lluvia ligera, 10–13 °C"
    assert weather_text([s(0, 7.5), s(2, 8.0), s(2, 8.4), s(0, 7.6)]) == "Parcialmente nublado, 8 °C"
    assert weather_text([s(None, -2.5), s(None, 1.5)]) == "-2–2 °C"  # half up: -2.5 → -2, like Java Math.round
    assert weather_text([s(None, None)]) is None
    assert weather_text([s(3, None)]) == "Nublado"
    assert weather_text([]) is None


def test_worst_family_needs_thirty_percent():
    assert weather_text([s(0, 20), s(0, 21), s(0, 22), s(2, 23)]) == "Despejado, 20–23 °C"
    assert weather_text([s(0, 20), s(0, 21), s(95, 22)]) == "Tormenta, 20–22 °C"
