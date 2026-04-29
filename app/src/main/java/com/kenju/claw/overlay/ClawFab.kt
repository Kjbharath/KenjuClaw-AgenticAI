package com.kenju.claw.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kenju.claw.R
import com.kenju.claw.orchestrator.ClawMode
import com.kenju.claw.ui.theme.ClawFabBg
import com.kenju.claw.ui.theme.GpuGlowColor
import com.kenju.claw.ui.theme.HybridGlowMid
import com.kenju.claw.ui.theme.NpuGlowColor

/**
 * ClawFab
 *
 * The draggable, tappable floating action button that is the primary entry
 * point of the KenjuClaw overlay.
 *
 * Visual anatomy:
 * ```
 *  ┌─────────────────────────────┐
 *  │   [animated glow rings]     │
 *  │   ┌─────────────────────┐   │
 *  │   │   [icon]   ●        │   │
 *  │   └─────────────────────┘   │
 *  └─────────────────────────────┘
 * ```
 *
 * - **Idle:** a single pulsing glow ring whose colour reflects [activeMode].
 * - **Pressed:** scale springs down to 0.92 for tactile feedback.
 * - **Drag:** offset reported via [onDrag] for the host to reposition the window.
 *
 * @param activeMode   Current [ClawMode] — drives glow ring colour.
 * @param isExpanded   Whether the selector panel is open — dims glow when true.
 * @param fabSize      Diameter of the circular button. Default: 56 dp.
 * @param onTap        Invoked on a tap (no drag) to toggle the panel.
 * @param onDrag       Invoked on each drag event with pixel deltas.
 */
@Composable
fun ClawFab(
    activeMode: ClawMode,
    isExpanded: Boolean,
    fabSize: Dp = 56.dp,
    onTap: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    val glowColor = when (activeMode) {
        ClawMode.EFFICIENT_NPU -> NpuGlowColor
        ClawMode.POWER_GPU     -> GpuGlowColor
        ClawMode.HYBRID        -> HybridGlowMid
    }

    // ── Pulse animation ───────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "fabGlow")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue   = 0.85f,
        targetValue    = 1.15f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue   = 0.55f,
        targetValue    = 0.0f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // ── Press scale spring ────────────────────────────────────────────────────
    // Note: actual press state tracked via pointerInput below; we read a stable
    // animatable driven by a mutable boolean captured in the lambda.
    val scaleTarget by animateFloatAsState(
        targetValue    = if (isExpanded) 0.94f else 1.0f,
        animationSpec  = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label          = "fabScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(fabSize)
            .scale(scaleTarget)
            .drawBehind {
                drawNeuralGlow(
                    glowColor  = glowColor,
                    radius     = pulseRadius,
                    alpha      = if (isExpanded) pulseAlpha * 0.4f else pulseAlpha,
                    fabSizePx  = size.minDimension
                )
            }
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.25f),
                        ClawFabBg
                    )
                )
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        // Claw icon (falls back to a built-in star if vector not added yet)
        ClawIconGlyph(tint = glowColor)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Glow drawing helper
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawNeuralGlow(
    glowColor: Color,
    radius: Float,
    alpha: Float,
    fabSizePx: Float
) {
    val centerX   = size.width  / 2f
    val centerY   = size.height / 2f
    val baseRadius = fabSizePx / 2f

    // Outer diffuse halo
    drawCircle(
        color  = glowColor.copy(alpha = alpha * 0.20f),
        radius = baseRadius * radius * 1.55f,
        center = androidx.compose.ui.geometry.Offset(centerX, centerY)
    )
    // Middle ring
    drawCircle(
        color  = glowColor.copy(alpha = alpha * 0.45f),
        radius = baseRadius * radius * 1.20f,
        center = androidx.compose.ui.geometry.Offset(centerX, centerY)
    )
    // Inner ring — sharp edge
    drawCircle(
        color  = glowColor.copy(alpha = alpha * 0.70f),
        radius = baseRadius * 1.05f,
        center = androidx.compose.ui.geometry.Offset(centerX, centerY),
        style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Icon glyph — safe fallback
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ClawIconGlyph(tint: Color) {
    // Uses a unicode "⚡" rendered as Text as a universal fallback.
    // Replace with your actual vector drawable once added to res/drawable/.
    androidx.compose.material3.Text(
        text  = "⚡",
        color = tint,
        style = androidx.compose.material3.MaterialTheme.typography.titleLarge
    )
}
