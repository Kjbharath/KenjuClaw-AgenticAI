package com.kenju.claw.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Brand palette
// ─────────────────────────────────────────────────────────────────────────────

val KenjuPrimary    = Color(0xFF7C6AF7)   // Violet — NPU accent
val KenjuSecondary  = Color(0xFF5ED4F4)   // Cyan   — GPU accent
val KenjuBackground = Color(0xFF0E0E14)   // Near-black
val KenjuSurface    = Color(0xFF1A1A26)   // Dark surface
val KenjuOnPrimary  = Color(0xFFFFFFFF)
val KenjuOnSurface  = Color(0xFFE1E1F0)

// ─────────────────────────────────────────────────────────────────────────────
// Overlay-specific tokens
// ─────────────────────────────────────────────────────────────────────────────

/** FAB background — slightly lighter than background so it "lifts". */
val ClawFabBg           = Color(0xFF1E1B3A)

/** Neural glow ring for the NPU mode — violet pulse. */
val NpuGlowColor        = Color(0xFF9B7FFF)

/** Neural glow ring for the GPU mode — cyan pulse. */
val GpuGlowColor        = Color(0xFF4DD9F5)

/** HYBRID mode glow — gradient midpoint (used in brush definitions). */
val HybridGlowMid       = Color(0xFF6ECFF6)

/** Panel background with slight transparency feel (solid for WindowManager). */
val PanelBg             = Color(0xFF12121F)

/** Panel border / divider */
val PanelBorder         = Color(0xFF2A2A40)

/** Segmented selector — selected segment fill. */
val SegmentSelected     = Color(0xFF2C2650)

/** Segmented selector — unselected segment fill. */
val SegmentUnselected   = Color(0xFF18182A)

// ─────────────────────────────────────────────────────────────────────────────
// HUD status colours
// ─────────────────────────────────────────────────────────────────────────────

/** Usage / thermal within safe range. */
val HudGreen  = Color(0xFF4ADE80)

/** Usage / thermal approaching limit. */
val HudAmber  = Color(0xFFFBBF24)

/** Usage / thermal at critical level. */
val HudRed    = Color(0xFFF87171)

/** Inactive / unknown hardware. */
val HudGray   = Color(0xFF6B7280)
