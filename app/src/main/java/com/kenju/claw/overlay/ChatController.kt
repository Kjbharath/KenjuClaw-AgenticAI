package com.kenju.claw.overlay

import com.kenju.claw.orchestrator.ClawOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageRole {
    USER, AGENT
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val isGenerating: Boolean = false
)

/**
 * Manages the state of the chat UI in the agentic overlay.
 * We use a dedicated controller rather than a ViewModel because this is scoped
 * to a Service lifecycle, not an Activity/Fragment.
 */
class ChatController(
    private val orchestrator: ClawOrchestrator,
    private val coroutineScope: CoroutineScope
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value += userMsg

        val agentMsgId = UUID.randomUUID().toString()
        val agentMsg = ChatMessage(
            id = agentMsgId,
            role = MessageRole.AGENT,
            content = "...",
            isGenerating = true
        )
        _messages.value += agentMsg

        // TODO: Pass intent to orchestrator and observe response streams
        // Mocking engine response generation for now:
        coroutineScope.launch {
            delay(800)
            updateMessage(agentMsgId) {
                it.copy(content = "Processing: \"$text\"", isGenerating = true)
            }
            delay(1200)
            updateMessage(agentMsgId) {
                it.copy(
                    content = "I've received your task and am ready to execute it. (Engine wiring pending)",
                    isGenerating = false
                )
            }
        }
    }

    private fun updateMessage(id: String, update: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) update(it) else it }
    }
}
