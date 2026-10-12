package com.bitacoraviaje.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// High-contrast palette: the active-session screen must be readable in full sun (DISENO §14.2).
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3864),
    onPrimary = Color.White,
    secondary = Color(0xFFF4B942),
    onSecondary = Color(0xFF1B1B1B),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB7E8),
    onPrimary = Color(0xFF0B1A33),
    secondary = Color(0xFFF4B942),
    onSecondary = Color(0xFF1B1B1B),
    background = Color(0xFF0E0E10),
    surface = Color(0xFF17171A),
)

@Composable
fun BitacoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
