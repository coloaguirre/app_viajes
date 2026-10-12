package com.bitacoraviaje.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HaversineTest {
    @Test
    fun `Buenos Aires to Cordoba is about 646 km`() {
        val d = haversineM(-34.6037, -58.3816, -31.4201, -64.1888)
        assertTrue(d in 640_000.0..653_000.0, "got $d")
    }

    @Test
    fun `same point is zero and distance is symmetric`() {
        assertEquals(0.0, haversineM(10.0, 20.0, 10.0, 20.0))
        assertEquals(haversineM(1.0, 2.0, 3.0, 4.0), haversineM(3.0, 4.0, 1.0, 2.0), 1e-9)
    }

    @Test
    fun `crossing the antimeridian is short, not 40000 km`() {
        val d = haversineM(0.0, 179.9, 0.0, -179.9)
        assertTrue(d in 22_000.0..22_300.0, "got $d")
    }

    @Test
    fun `percentile and moving average helpers`() {
        assertEquals(98.0, percentile((1..100).map { it.toDouble() }, 98.0))
        assertEquals(listOf(1.5, 2.0, 3.0, 4.0, 4.5), movingAverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))
    }
}
