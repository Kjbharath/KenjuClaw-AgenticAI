package com.kenju.claw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KenjuPrimary     = Color(0xFF7C6AF7)   // Violet
private val KenjuSecondary   = Color(0xFF5ED4F4)   // Cyan
private val KenjuBackground  = Color(0xFF0E0E14)   // Near-black
private val KenjuSurface     = Color(0xFF1A1A26)   // Dark surface
private val KenjuOnPrimary   = Color(0xFFFFFFFF)
private val KenjuOnSurface   = Color(0xFFE1E1F0)

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
