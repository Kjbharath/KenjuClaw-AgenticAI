package com.kenju.claw.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kenju.claw.orchestrator.ClawMode

/**
 * OverlayUiState
 *
 * Single source of truth for the KenjuClaw floating overlay UI.
 * All fields are Compose-observable via delegated [mutableStateOf] /
 * [mutableFloatStateOf], so any composable reading them will recompose
 * automatically when they change.
 *
 * Mutated exclusively by [AgentOverlayService] via the monitor callback
 * and user interaction handlers — no ViewModel needed since the overlay
 * lives inside a Service, not an Activity.
 */
class OverlayUiState {

    // ── Panel expansion ────────────────────────────────────────────────────────

    /** Whether the selector panel is open. Toggled by tapping the FAB. */
    var panelExpanded by mutableStateOf(false)

    // ── Engine mode ────────────────────────────────────────────────────────────

    /** Currently active inference mode. Drives the segmented toggle. */
    var activeMode by mutableStateOf(ClawMode.EFFICIENT_NPU)

    // ── HUD data ───────────────────────────────────────────────────────────────

    /** Latest hardware snapshot from [HardwareHudMonitor]. */
    var hud by mutableStateOf(HudSnapshot.EMPTY)

    // ── FAB drag position ──────────────────────────────────────────────────────

    /** FAB X offset from the initial anchor (px). Updated by drag gesture. */
    var fabOffsetX by mutableFloatStateOf(0f)

    /** FAB Y offset from the initial anchor (px). Updated by drag gesture. */
    var fabOffsetY by mutableFloatStateOf(0f)
}
