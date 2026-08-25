package com.vpnbox.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A9EFF),           // Blue primary
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3A6E),  // Blue dark
    onPrimaryContainer = Color(0xFFB0D4FF),

    secondary = Color(0xFF80C0FF),         // Blue accent
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0D2847),
    onSecondaryContainer = Color(0xFFB0D4FF),

    tertiary = Color(0xFF6B8AFF),
    onTertiary = Color.White,

    background = Color(0xFF0A1628),        // Dark background
    onBackground = Color.White,

    surface = Color(0xFF0F1D32),           // Dark surface
    onSurface = Color.White,
    surfaceVariant = Color(0xFF152540),    // Dark surface variant
    onSurfaceVariant = Color(0xFF8899AA),  // Grey light

    error = Color(0xFFFF6B6B),
    onError = Color.White
)

@Composable
fun WhiteHoleTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
