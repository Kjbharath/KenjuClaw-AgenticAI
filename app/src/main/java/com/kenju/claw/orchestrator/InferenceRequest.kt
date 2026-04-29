package com.kenju.claw.orchestrator

/**
 * InferenceRequest
 *
 * The unified input contract for both the [NexaNpuEngine] and [GoogleGpuEngine].
 * The [ClawOrchestrator] constructs these from higher-level agentic events and
 * routes them to the appropriate engine based on the active [ClawMode].
 *
 * @param prompt         Raw user or system prompt text to be inferred.
 * @param imageBytes     Optional JPEG/PNG screenshot bytes for vision-capable requests
 *                       (e.g., OCR or screen-observation passes on the NPU engine).
 * @param maxTokens      Maximum number of tokens to generate. Defaults to 256.
 * @param temperature    Sampling temperature [0.0, 2.0]. Lower = more deterministic.
 * @param systemPrompt   Optional system/role prompt prepended before [prompt].
 * @param functionSchema Optional JSON schema string describing callable tools (GPU engine only).
 * @param requestId      Unique identifier for tracing this request through the pipeline.
 *                       Auto-generated via [java.util.UUID] if not supplied.
 */
data class InferenceRequest(
    val prompt: String,
    val imageBytes: ByteArray? = null,
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val systemPrompt: String? = null,
    val functionSchema: String? = null,
    val requestId: String = java.util.UUID.randomUUID().toString()
) {
    // ByteArray equality must be handled manually to avoid reference comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InferenceRequest) return false
        return requestId == other.requestId &&
               prompt == other.prompt &&
               imageBytes.contentEquals(other.imageBytes) &&
               maxTokens == other.maxTokens &&
               temperature == other.temperature &&
               systemPrompt == other.systemPrompt &&
               functionSchema == other.functionSchema
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + prompt.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        result = 31 * result + maxTokens
        result = 31 * result + temperature.hashCode()
        result = 31 * result + (systemPrompt?.hashCode() ?: 0)
        result = 31 * result + (functionSchema?.hashCode() ?: 0)
        return result
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extension helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun ByteArray?.contentEquals(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> this.contentEquals(other)
}

/**
 * InferenceResult
 *
 * The unified output contract returned by both inference engines and surfaced
 * by [ClawOrchestrator] to its callers.
 *
 * @param requestId         Echo of [InferenceRequest.requestId] for correlation.
 * @param engine            Which engine produced this result.
 * @param text              Generated text output.
 * @param functionCall      Optional structured function-call payload (GPU engine only).
 *                          Serialized as a JSON string when the model emits a tool-call.
 * @param confidence        Optional confidence score [0.0, 1.0] from the engine's logit head.
 *                          Used by the orchestrator in [ClawMode.HYBRID] to decide escalation.
 * @param promptTokens      Number of tokens consumed by the prompt.
 * @param generatedTokens   Number of tokens generated in the output.
 * @param latencyMs         Wall-clock inference time in milliseconds.
 * @param wasEscalated      True when this result was produced by a HYBRID escalation
 *                          (i.e., a GPU engine pass following a low-confidence NPU pass).
 * @param error             Non-null when the engine encountered a recoverable error.
 *                          A best-effort partial [text] may still be present.
 */
data class InferenceResult(
    val requestId: String,
    val engine: EngineId,
    val text: String,
    val functionCall: String? = null,
    val confidence: Float? = null,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val latencyMs: Long = 0L,
    val wasEscalated: Boolean = false,
    val error: InferenceError? = null
) {
    /** True when the result is usable (no error occurred). */
    val isSuccess: Boolean get() = error == null
}

/**
 * Identifies which physical inference engine produced a result.
 */
enum class EngineId {
    /** [NexaNpuEngine] — OmniNeural 4B on the Hexagon NPU. */
    NEXA_NPU,
    /** [GoogleGpuEngine] — Gemma 4 E2B-IT on the Adreno GPU. */
    GOOGLE_GPU
}

/**
 * Typed error information returned by an engine.
 *
 * @param code     Machine-readable error code.
 * @param message  Human-readable description for logging / UI display.
 * @param cause    Optional underlying throwable for stack-trace logging.
 */
data class InferenceError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null
)

/**
 * Error codes covering the most common failure scenarios in both engines.
 */
enum class ErrorCode {
    /** Engine has not been initialized or [EngineState.READY] was never reached. */
    ENGINE_NOT_READY,
    /** The model file is missing from the vault. */
    MODEL_NOT_FOUND,
    /** Native library (QNN HTP / MediaPipe) failed to load. */
    NATIVE_LOAD_FAILURE,
    /** Inference ran but the model did not produce a valid output. */
    INFERENCE_FAILURE,
    /** Request exceeded the timeout configured in the engine. */
    TIMEOUT,
    /** Unclassified / unexpected failure. */
    UNKNOWN
}
