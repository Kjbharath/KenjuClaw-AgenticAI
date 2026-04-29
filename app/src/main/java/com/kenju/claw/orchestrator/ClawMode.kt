package com.kenju.claw.orchestrator

/**
 * ClawMode
 *
 * Runtime selector that controls which inference engine(s) the
 * [ClawOrchestrator] routes requests to.
 *
 * | Mode              | Active engine(s)                          | Typical use-case                                  |
 * |-------------------|-------------------------------------------|---------------------------------------------------|
 * | [EFFICIENT_NPU]   | [NexaNpuEngine] only                      | Continuous screen observation, OCR, intent detection (battery-friendly) |
 * | [POWER_GPU]       | [GoogleGpuEngine] only                    | Heavy reasoning, function calling, agentic tasks  |
 * | [HYBRID]          | Both engines — NPU for triage, GPU for reasoning | Full agentic pipeline with automatic escalation |
 *
 * Switch modes at runtime via [ClawOrchestrator.setMode].
 */
enum class ClawMode {

    /**
     * Low-power mode.
     *
     * Routes all inference to [NexaNpuEngine] (OmniNeural 4B / Nexa SDK / Hexagon NPU).
     * Ideal for background, always-on screen observation where battery life matters most.
     * The GPU engine is kept in a [EngineState.IDLE] warm state for fast escalation.
     */
    EFFICIENT_NPU,

    /**
     * High-power mode.
     *
     * Routes all inference to [GoogleGpuEngine] (Gemma 4 E2B-IT / MediaPipe LLM / Adreno GPU).
     * Preferred for complex multi-step reasoning, structured function calls, and planning loops.
     * The NPU engine is kept [EngineState.IDLE] to conserve Hexagon clock cycles.
     */
    POWER_GPU,

    /**
     * Hybrid (dual-engine) mode.
     *
     * The orchestrator first runs a lightweight intent-classification pass on the NPU engine.
     * If the result confidence is below [ClawOrchestrator.HYBRID_ESCALATION_THRESHOLD], the
     * request is automatically escalated to the GPU engine for deeper reasoning.
     *
     * This mode offers the best balance of responsiveness and capability at the cost of
     * occasionally engaging both hardware paths simultaneously.
     */
    HYBRID
}
