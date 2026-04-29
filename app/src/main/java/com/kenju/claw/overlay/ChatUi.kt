package com.kenju.claw.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kenju.claw.orchestrator.ClawMode
import com.kenju.claw.ui.theme.GpuGlowColor
import com.kenju.claw.ui.theme.NpuGlowColor
import com.kenju.claw.ui.theme.PanelBg
import com.kenju.claw.ui.theme.PanelBorder
import com.kenju.claw.ui.theme.SegmentUnselected

// ─────────────────────────────────────────────────────────────────────────────
// Chat History List
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatHistoryList(
    messages: List<ChatMessage>,
    activeMode: ClawMode,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            MessageBubble(msg, activeMode)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Message Bubble
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(msg: ChatMessage, activeMode: ClawMode) {
    val isUser = msg.role == MessageRole.USER
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val agentColor = when (activeMode) {
        ClawMode.EFFICIENT_NPU -> NpuGlowColor
        ClawMode.POWER_GPU     -> GpuGlowColor
        ClawMode.HYBRID        -> Color(0xFFB08BFF)
    }

    val bgColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    } else {
        PanelBg.copy(alpha = 0.95f)
    }

    val borderColor = if (isUser) Color.Transparent else agentColor.copy(alpha = 0.45f)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(shape)
                    .background(bgColor)
                    .border(0.5.dp, borderColor, shape)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                if (msg.isGenerating) {
                    TypingIndicator(agentColor)
                } else {
                    Text(
                        text = msg.content,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // Engine label badge (agent messages only)
            if (!isUser && msg.engineLabel != null) {
                Text(
                    text = msg.engineLabel,
                    fontSize = 8.sp,
                    color = agentColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typing indicator (3 pulsing dots)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TypingIndicator(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_pulse"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha * (1f - i * 0.15f))
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat Input Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatInputBar(
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SegmentUnselected)
            .border(1.dp, PanelBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mic button (placeholder)
        IconButton(
            onClick = { /* Voice input — future feature */ },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Attach or Voice Input",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }

        // Text field
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            }),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "Message KenjuClaw…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Send button
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Performance Badge (tok/s)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PerformanceBadge(
    perf: InferencePerf,
    activeMode: ClawMode,
    modifier: Modifier = Modifier
) {
    val accentColor = when (activeMode) {
        ClawMode.EFFICIENT_NPU -> NpuGlowColor
        ClawMode.POWER_GPU     -> GpuGlowColor
        ClawMode.HYBRID        -> Color(0xFFB08BFF)
    }

    AnimatedVisibility(
        visible = perf.tokensPerSecond != null || perf.isGenerating,
        enter = fadeIn(tween(200)),
        exit  = fadeOut(tween(400)),
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "perf_pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.08f))
                .border(0.5.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .alpha(if (perf.isGenerating) pulseAlpha else 1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (perf.isGenerating) "⚡ generating…" else "⚡ ${String.format("%.1f", perf.tokensPerSecond)} tok/s",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accentColor
            )
            Text(
                text = "${perf.totalTokens} tokens · ${perf.engineLabel}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = accentColor.copy(alpha = 0.7f)
            )
        }
    }
}
