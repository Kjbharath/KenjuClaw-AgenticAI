package com.kenju.claw.overlay

import com.kenju.claw.orchestrator.ClawMode
import com.kenju.claw.orchestrator.ClawOrchestrator
import com.kenju.claw.orchestrator.InferenceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

enum class MessageRole { USER, AGENT }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val isGenerating: Boolean = false,
    val engineLabel: String? = null
)

/**
 * Snapshot of live inference performance metrics, emitted during generation.
 *
 * @param tokensPerSecond Current tok/s rate (null when idle).
 * @param totalTokens     Total tokens generated so far in the current response.
 * @param isGenerating    Whether inference is currently in progress.
 * @param engineLabel     Short label of the active engine (e.g., "NPU", "GPU").
 */
data class InferencePerf(
    val tokensPerSecond: Float? = null,
    val totalTokens: Int = 0,
    val isGenerating: Boolean = false,
    val engineLabel: String = "—"
)

/**
 * ChatController
 *
 * Manages the conversation state for the Agentic Overlay chat interface.
 *
 * Scoped to the [AgentOverlayService] lifecycle (not a ViewModel, because
 * this runs inside a Service, not an Activity/Fragment).
 *
 * When [sendMessage] is called, the controller:
 *  1. Appends the user message to the conversation.
 *  2. Creates a placeholder agent bubble with "…" and `isGenerating = true`.
 *  3. Routes the prompt through the [ClawOrchestrator] to the active engine.
 *  4. Measures tokens-per-second and emits live updates to [perfFlow].
 *  5. Replaces the placeholder with the actual response.
 */
class ChatController(
    private val orchestrator: ClawOrchestrator,
    private val coroutineScope: CoroutineScope
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _perf = MutableStateFlow(InferencePerf())
    val perfFlow: StateFlow<InferencePerf> = _perf.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 1. Append user message
        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value += userMsg

        // 2. Create placeholder agent bubble
        val agentMsgId = UUID.randomUUID().toString()
        val activeMode = orchestrator.currentMode
        val engineLabel = when (activeMode) {
            ClawMode.EFFICIENT_NPU -> "NPU"
            ClawMode.POWER_GPU     -> "GPU"
            ClawMode.HYBRID        -> "HYBRID"
        }
        val agentMsg = ChatMessage(
            id = agentMsgId,
            role = MessageRole.AGENT,
            content = "…",
            isGenerating = true,
            engineLabel = engineLabel
        )
        _messages.value += agentMsg

        // 3. Start inference
        _perf.value = InferencePerf(
            isGenerating = true,
            engineLabel = engineLabel
        )

        coroutineScope.launch {
            val startNs = System.nanoTime()
            try {
                val request = InferenceRequest(
                    prompt = text,
                    maxTokens = 512
                )

                val result = orchestrator.infer(request)

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                val generatedTokens = result.generatedTokens
                val tokPerSec = if (elapsedMs > 0 && generatedTokens > 0) {
                    (generatedTokens / (elapsedMs / 1000.0)).toFloat()
                } else null

                val responseText = if (result.isSuccess) {
                    result.text
                } else {
                    "⚠ ${result.error?.message ?: "Inference failed"}"
                }

                // 4. Update agent bubble with response
                updateMessage(agentMsgId) {
                    it.copy(
                        content = responseText,
                        isGenerating = false,
                        engineLabel = "${engineLabel}${if (result.wasEscalated) " → GPU" else ""}"
                    )
                }

                // 5. Emit final perf
                _perf.value = InferencePerf(
                    tokensPerSecond = tokPerSec,
                    totalTokens = generatedTokens,
                    isGenerating = false,
                    engineLabel = engineLabel
                )

                Timber.tag(TAG).i(
                    "Inference complete: %d tokens, %.1f tok/s, %dms",
                    generatedTokens, tokPerSec ?: 0f, elapsedMs.toLong()
                )

                // Fade out perf badge after 3 seconds
                delay(3000)
                _perf.value = InferencePerf()

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Inference error")
                updateMessage(agentMsgId) {
                    it.copy(
                        content = "⚠ Error: ${e.message}",
                        isGenerating = false
                    )
                }
                _perf.value = InferencePerf()
            }
        }
    }

    private fun updateMessage(id: String, update: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) update(it) else it }
    }

    companion object {
        private const val TAG = "KenjuClaw/Chat"
    }
}
