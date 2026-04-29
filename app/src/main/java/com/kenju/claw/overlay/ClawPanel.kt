package com.kenju.claw.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kenju.claw.orchestrator.ClawMode
import com.kenju.claw.ui.theme.GpuGlowColor
import com.kenju.claw.ui.theme.HudAmber
import com.kenju.claw.ui.theme.HudGray
import com.kenju.claw.ui.theme.HudGreen
import com.kenju.claw.ui.theme.HudRed
import com.kenju.claw.ui.theme.NpuGlowColor
import com.kenju.claw.ui.theme.PanelBg
import com.kenju.claw.ui.theme.PanelBorder
import com.kenju.claw.ui.theme.SegmentSelected
import com.kenju.claw.ui.theme.SegmentUnselected

private val PanelShape  = RoundedCornerShape(20.dp)
private val SegShape    = RoundedCornerShape(12.dp)

/**
 * ClawPanel
 *
 * The slide-in dark selector panel that appears below (or above) the FAB when
 * the user taps it.
 *
 * Contains:
 *  1. **Header row** — "KenjuClaw" wordmark + close affordance hint.
 *  2. **Engine Switcher** — 3-segment toggle: NPU · HYBRID · GPU.
 *  3. **Hardware HUD** — live metrics strip reading from [HudSnapshot].
 *
 * @param visible     Whether the panel is shown. Drives AnimatedVisibility.
 * @param activeMode  The currently selected [ClawMode] — highlights the active segment.
 * @param hud         Latest [HudSnapshot] from [HardwareHudMonitor].
 * @param onModeSelect Callback when the user selects a new mode.
 */
@Composable
fun ClawPanel(
    visible:       Boolean,
    activeMode:    ClawMode,
    hud:           HudSnapshot,
    messages:      List<ChatMessage>,
    perf:          InferencePerf,
    onSendMessage: (String) -> Unit,
    onModeSelect:  (ClawMode) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(220)) + expandVertically(
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            expandFrom    = Alignment.Top
        ),
        exit    = fadeOut(tween(180)) + shrinkVertically(
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 580.dp)
                .clip(PanelShape)
                .background(PanelBg)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(PanelBorder.copy(alpha = 0.8f), PanelBorder.copy(alpha = 0.2f))
                    ),
                    shape = PanelShape
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            PanelHeader()

            HorizontalDivider(color = PanelBorder, thickness = 0.5.dp)

            // ── Engine Switcher ───────────────────────────────────────────────
            Text(
                text  = "INFERENCE ENGINE",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.5.sp,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            EngineSegmentedToggle(
                activeMode    = activeMode,
                onModeSelect  = onModeSelect
            )

            HorizontalDivider(color = PanelBorder, thickness = 0.5.dp)

            // ── Hardware HUD ──────────────────────────────────────────────────
            Text(
                text  = "HARDWARE HUD · SM8750",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.5.sp,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            HardwareHud(hud = hud, activeMode = activeMode)

            HorizontalDivider(color = PanelBorder, thickness = 0.5.dp)

            // -- Chat UI
            ChatHistoryList(
                messages   = messages,
                activeMode = activeMode,
                modifier   = Modifier.weight(1f, fill = false)
            )

            // -- Performance Badge
            PerformanceBadge(
                perf       = perf,
                activeMode = activeMode
            )

            // -- Chat Input
            ChatInputBar(
                onSendMessage = onSendMessage
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Panel Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PanelHeader() {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text       = "KenjuClaw",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text  = "AGENT OVERLAY",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.sp,
                fontSize      = 9.sp
            ),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine Segmented Toggle
// ─────────────────────────────────────────────────────────────────────────────

private data class Segment(
    val mode:  ClawMode,
    val label: String,
    val sub:   String,
    val color: Color
)

private val segments = listOf(
    Segment(ClawMode.EFFICIENT_NPU, "NPU",    "OmniNeural",  NpuGlowColor),
    Segment(ClawMode.HYBRID,        "HYBRID", "Auto",        Color(0xFFB08BFF)),
    Segment(ClawMode.POWER_GPU,     "GPU",    "Gemma",       GpuGlowColor)
)

@Composable
private fun EngineSegmentedToggle(
    activeMode:   ClawMode,
    onModeSelect: (ClawMode) -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(SegShape)
            .background(SegmentUnselected),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { seg ->
            val isSelected = seg.mode == activeMode
            SegmentChip(
                segment    = seg,
                isSelected = isSelected,
                modifier   = Modifier.weight(1f),
                onClick    = { onModeSelect(seg.mode) }
            )
        }
    }
}

@Composable
private fun SegmentChip(
    segment:    Segment,
    isSelected: Boolean,
    modifier:   Modifier = Modifier,
    onClick:    () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier         = modifier
            .clip(SegShape)
            .background(
                if (isSelected)
                    Brush.verticalGradient(
                        colors = listOf(
                            segment.color.copy(alpha = 0.28f),
                            SegmentSelected
                        )
                    )
                else
                    Brush.verticalGradient(colors = listOf(SegmentUnselected, SegmentUnselected))
            )
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) segment.color.copy(alpha = 0.6f) else Color.Transparent,
                shape = SegShape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text       = segment.label,
                fontSize   = 11.sp,
                fontWeight = FontWeight.Bold,
                color      = if (isSelected) segment.color else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign  = TextAlign.Center
            )
            Text(
                text     = segment.sub,
                fontSize = 8.5.sp,
                color    = if (isSelected) segment.color.copy(alpha = 0.75f)
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hardware HUD strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HardwareHud(hud: HudSnapshot, activeMode: ClawMode = ClawMode.EFFICIENT_NPU) {
    val tokColor = when (activeMode) {
        ClawMode.EFFICIENT_NPU -> NpuGlowColor
        ClawMode.POWER_GPU     -> GpuGlowColor
        ClawMode.HYBRID        -> Color(0xFFB08BFF)
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HudMetric(label = "NPU",   value = hud.npuBusyPercent?.let { "$it%" }  ?: "N/A",  color = NpuGlowColor)
            HudMetric(label = "GPU",   value = hud.gpuUsagePercent?.let { "$it%" } ?: "N/A",  color = GpuGlowColor)
            HudMetric(label = "CPU",   value = hud.cpuUsagePercent?.let { "$it%" } ?: "N/A",  color = thermalColor(hud.cpuUsagePercent))
            HudMetric(label = "TEMP",  value = hud.thermalCelsius?.let { "%.0f°".format(it) } ?: "N/A", color = thermalTempColor(hud.thermalStatus))
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HudMetric(label = "RAM",    value = hud.ramUsedMb?.let { "${it} MB" }   ?: "N/A",  color = HudGray)
            HudMetric(label = "FREQ",   value = hud.cpuFreqMhz?.let { "${it} MHz" } ?: "N/A",  color = HudGray)
            HudMetric(label = "TOK/S",  value = hud.inferenceTokensPerSec?.let { "%.1f".format(it) } ?: "—", color = tokColor)
            ThermalStatusBadge(status = hud.thermalStatus)
        }
    }
}

@Composable
private fun HudMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text      = label,
            fontSize  = 8.sp,
            color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text       = value,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color      = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ThermalStatusBadge(status: ThermalStatus) {
    val (label, color) = when (status) {
        ThermalStatus.COOL    -> "COOL"    to HudGreen
        ThermalStatus.WARM    -> "WARM"    to HudAmber
        ThermalStatus.HOT     -> "HOT ⚠"  to HudRed
        ThermalStatus.UNKNOWN -> "TEMP?"   to HudGray
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 9.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HUD colour helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun thermalColor(percent: Int?): Color = when {
    percent == null  -> HudGray
    percent >= 85    -> HudRed
    percent >= 65    -> HudAmber
    else             -> HudGreen
}

private fun thermalTempColor(status: ThermalStatus): Color = when (status) {
    ThermalStatus.COOL    -> HudGreen
    ThermalStatus.WARM    -> HudAmber
    ThermalStatus.HOT     -> HudRed
    ThermalStatus.UNKNOWN -> HudGray
}
