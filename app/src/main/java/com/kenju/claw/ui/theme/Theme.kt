package com.kenju.claw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KenjuDarkColorScheme = darkColorScheme(
    primary          = KenjuPrimary,
    secondary        = KenjuSecondary,
    background       = KenjuBackground,
    surface          = KenjuSurface,
    onPrimary        = KenjuOnPrimary,
    onSurface        = KenjuOnSurface,
    onSurfaceVariant = Color(0xFF9A9AB0)
)

@Composable
fun KenjuClawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KenjuDarkColorScheme,
        content     = content
    )
}
