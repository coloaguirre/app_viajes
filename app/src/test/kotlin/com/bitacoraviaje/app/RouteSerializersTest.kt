package com.bitacoraviaje.app

import com.bitacoraviaje.app.ui.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.serializer

/**
 * Navigation Compose resolves a serializer for each typed destination at runtime. Without
 * the kotlin-serialization compiler plugin the app compiles but crashes on the first frame.
 * This test fails at build time instead.
 */
class RouteSerializersTest {
    @Test
    fun `every route has a generated serializer`() {
        assertEquals("com.bitacoraviaje.app.ui.navigation.Route.Home", serializer<Route.Home>().descriptor.serialName)
        assertEquals("com.bitacoraviaje.app.ui.navigation.Route.Settings", serializer<Route.Settings>().descriptor.serialName)
    }
}
