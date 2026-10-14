package com.bitacoraviaje.core.timeline

import com.bitacoraviaje.core.model.WeatherCodes
import com.bitacoraviaje.core.model.WeatherSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Backlog D3: hourly samples → morning / afternoon / night blocks and the sample for an instant. */
class WeatherDigestTest {
    // 2025-10-09 03:00 UTC = 00:00 in Buenos Aires (UTC-3).
    private val midnightBa = 1_759_978_800_000L
    private fun h(hourLocal: Int, temp: Double, code: Int, rain: Double = 0.0, wind: Double = 10.0) =
        WeatherSample("w$hourLocal", "s1", midnightBa + hourLocal * 3_600_000L, -34.6, -58.4, temp, temp - 1, rain, wind, 50, code)

    private val day = (0 until 24).map { hr ->
        when {
            hr < 6 -> h(hr, 8.0, 3)
            hr < 12 -> h(hr, 12.0 + hr - 6, 0)
            hr < 20 -> h(hr, 20.0, if (hr in 14..16) 61 else 2, rain = if (hr in 14..16) 2.0 else 0.0, wind = if (hr == 15) 42.0 else 12.0)
            else -> h(hr, 15.0, 1)
        }
    }

    @Test
    fun `blocks follow the local day and summarise range, rain, wind and dominant weather`() {
        val blocks = WeatherDigest.blocks(day, "America/Argentina/Buenos_Aires")
        assertEquals(listOf(WeatherDigest.Block.NIGHT, WeatherDigest.Block.MORNING, WeatherDigest.Block.AFTERNOON, WeatherDigest.Block.NIGHT), blocks.map { it.block })
        val early = blocks[0]
        assertEquals("2025-10-08", early.localDate, "hours before 6 belong to the night that began the day before")
        assertEquals(6, early.hours)
        val morning = blocks[1]
        assertEquals("2025-10-09", morning.localDate)
        assertEquals("Despejado, 12–17 °C", morning.text)
        val afternoon = blocks[2]
        assertEquals(61, afternoon.weatherCode, "3 of 8 hours of rain outrank 5 hours of clouds")
        assertEquals("Lluvia ligera, 20 °C, 6.0 mm de lluvia, viento 42 km/h", afternoon.text)
        assertEquals(4, blocks[3].hours)
    }

    @Test
    fun `at picks the nearest sample within 90 min and brief formats it`() {
        val s = WeatherDigest.at(day, midnightBa + 9 * 3_600_000L + 20 * 60_000L)
        assertEquals("w9", s?.id)
        assertEquals("☀️ 15 °C", WeatherDigest.brief(s))
        assertNull(WeatherDigest.at(day, midnightBa + 30 * 3_600_000L), "4 h after the last sample")
        assertNull(WeatherDigest.brief(null))
        assertNull(WeatherDigest.brief(day[0].copy(temperatureC = null)))
    }

    @Test
    fun `segmentText mirrors planner_py weather_text on ties, rounding and missing data`() {
        fun s(i: Int, code: Int?, t: Double?) = WeatherSample("x$i", "s", i * 3_600_000L, 0.0, 0.0, t, null, null, null, null, code)
        assertEquals("Lluvia ligera, 10–13 °C", WeatherDigest.segmentText(listOf(s(0, 61, 10.4), s(1, 63, 12.5))), "tie inside the rain family: first seen wins; 12.5 rounds up")
        assertEquals("Parcialmente nublado, 8 °C", WeatherDigest.segmentText(listOf(s(0, 0, 7.5), s(1, 2, 8.0), s(2, 2, 8.4), s(3, 0, 7.6))), "two cloudy hours of four (≥ 30 %) outrank the clear ones; 7.5 rounds half up to 8")
        assertEquals("-2–2 °C", WeatherDigest.segmentText(listOf(s(0, null, -2.5), s(1, null, 1.5))), "no code at all: only the temperatures, no Sin dato; -2.5 rounds half up to -2 on both sides")
        assertNull(WeatherDigest.segmentText(listOf(s(0, null, null))))
        assertEquals("Nublado", WeatherDigest.segmentText(listOf(s(0, 3, null))))
    }

    @Test
    fun `codes map to labels, families and a dominant code`() {
        assertEquals("Tormenta", WeatherCodes.label(95))
        assertEquals(WeatherCodes.Family.SNOW, WeatherCodes.family(73))
        assertEquals("Sin dato", WeatherCodes.label(null))
        assertEquals(0, WeatherCodes.dominant(listOf(0, 0, 0, 2)), "a lone cloudy hour does not define a clear morning")
        assertEquals(95, WeatherCodes.dominant(listOf(0, 0, 95)), "one stormy hour in three is the story")
        assertNull(WeatherCodes.dominant(emptyList()))
    }
}
