package com.kenju.claw.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kenju.claw.orchestrator.ClawMode

/**
 * ClawOverlayContent
 *
 * Root Composable for the KenjuClaw floating overlay. This is the entry-point
 * passed to the [androidx.compose.ui.platform.ComposeView] that [AgentOverlayService]
 * inflates and attaches to [android.view.WindowManager].
 *
 * Layout (top-to-bottom when panel is open):
 * ```
 * ┌──────────────────────────┐
 * │  ClawPanel (animated)    │  ← slides in / out (includes chat + perf badge)
 * ├──────────────────────────┤
 * │  [4 dp gap]              │
 * ├──────────────────────────┤
 * │  ClawFab   ⚡            │  ← always visible
 * └──────────────────────────┘
 * ```
 *
 * @param state          Observable [OverlayUiState].
 * @param chatController [ChatController] managing conversation state and perf metrics.
 * @param onTap          Called when the user taps the FAB.
 * @param onDrag         Called on drag events with pixel deltas.
 * @param onModeSelect   Called when the user picks a new [ClawMode].
 */
@Composable
fun ClawOverlayContent(
    state:          OverlayUiState,
    chatController: ChatController,
    onTap:          () -> Unit,
    onDrag:         (Float, Float) -> Unit,
    onModeSelect:   (ClawMode) -> Unit
) {
    val messages by chatController.messages.collectAsState()
    val perf     by chatController.perfFlow.collectAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        // ── Selector panel — animates in above the FAB ───────────────────────
        ClawPanel(
            visible       = state.panelExpanded,
            activeMode    = state.activeMode,
            hud           = state.hud,
            messages      = messages,
            perf          = perf,
            onSendMessage = chatController::sendMessage,
            onModeSelect  = onModeSelect
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Draggable floating button ─────────────────────────────────────────
        ClawFab(
            activeMode = state.activeMode,
            isExpanded = state.panelExpanded,
            onTap      = onTap,
            onDrag     = onDrag
        )
    }
}
