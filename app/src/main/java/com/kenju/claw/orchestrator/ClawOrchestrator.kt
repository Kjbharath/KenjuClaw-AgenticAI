package com.kenju.claw.orchestrator

import android.content.Context
import com.kenju.claw.KenjuClawApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ClawOrchestrator
 *
 * The central inference coordinator for the KenjuClaw agentic stack.
 *
 * Manages two heterogeneous hardware inference engines:
 *
 * | Engine           | Model              | Backend              | Role                                      |
 * |------------------|--------------------|----------------------|-------------------------------------------|
 * | [NexaNpuEngine]  | OmniNeural 4B      | Nexa SDK / Hexagon NPU (HTP v79) | Screen observation, OCR, intent detection |
 * | [GoogleGpuEngine]| Gemma 4 E2B-IT     | MediaPipe LLM / Adreno GPU 830  | Reasoning, function calling, agentic tasks|
 *
 * ## Mode switching
 *
 * Call [setMode] to change the active [ClawMode] at any time. The transition is
 * applied immediately to the next [infer] call. No in-flight requests are cancelled.
 *
 * | Mode            | Behavior                                                      |
 * |-----------------|---------------------------------------------------------------|
 * | [ClawMode.EFFICIENT_NPU] | All requests routed to [NexaNpuEngine] only.       |
 * | [ClawMode.POWER_GPU]     | All requests routed to [GoogleGpuEngine] only.     |
 * | [ClawMode.HYBRID]        | NPU first; GPU if confidence < [HYBRID_ESCALATION_THRESHOLD]. |
 *
 * ## Lifecycle
 *
 * 1. Obtain the singleton via [ClawOrchestrator.getInstance].
 * 2. Call [initialize] once (typically from [com.kenju.claw.overlay.AgentOverlayService]).
 * 3. Call [infer] as many times as needed from any coroutine context.
 * 4. Call [shutdown] when the host service is destroyed.
 *
 * ## Thread-safety
 *
 * [ClawOrchestrator] is thread-safe. [infer] dispatches work to [Dispatchers.Default]
 * and may be called concurrently from multiple coroutines. Engine state transitions
 * are guarded by [@Volatile] fields inside each engine.
 *
 * @param context Application context (used to wire engine dependencies).
 */
class ClawOrchestrator private constructor(private val context: Context) {

    // ────────────────────────────────────────────────────────────────────────
    // Coroutine scope — survives configuration changes, cancelled on shutdown
    // ────────────────────────────────────────────────────────────────────────

    private val orchestratorJob = SupervisorJob()
    private val orchestratorScope = CoroutineScope(Dispatchers.Default + orchestratorJob)

    // ────────────────────────────────────────────────────────────────────────
    // Engine instances
    // ────────────────────────────────────────────────────────────────────────

    /** Engine 1 — Nexa SDK on the Hexagon NPU (OmniNeural 4B). */
    val npuEngine: NexaNpuEngine = NexaNpuEngine(
        context   = context,
        npuConfig = KenjuClawApplication.hwConfig.npu,
        vault     = KenjuClawApplication.modelVault
    )

    /** Engine 2 — MediaPipe LLM on the Adreno GPU (Gemma 4 E2B-IT). */
    val gpuEngine: GoogleGpuEngine = GoogleGpuEngine(
        context   = context,
        gpuConfig = KenjuClawApplication.hwConfig.gpu,
        vault     = KenjuClawApplication.modelVault
    )

    // ────────────────────────────────────────────────────────────────────────
    // Mode state — observable via StateFlow
    // ────────────────────────────────────────────────────────────────────────

    private val _mode = MutableStateFlow(ClawMode.EFFICIENT_NPU)

    /**
     * Observable stream of the current [ClawMode].
     * Collect this from UI components or overlay services to react to mode changes.
     */
    val modeFlow: StateFlow<ClawMode> = _mode.asStateFlow()

    /** Current active [ClawMode] (snapshot). */
    val currentMode: ClawMode get() = _mode.value

    // ────────────────────────────────────────────────────────────────────────
    // Orchestrator state — observable via StateFlow
    // ────────────────────────────────────────────────────────────────────────

    private val _orchestratorState = MutableStateFlow(OrchestratorState.UNINITIALIZED)

    /** Observable lifecycle state of the orchestrator itself. */
    val orchestratorStateFlow: StateFlow<OrchestratorState> = _orchestratorState.asStateFlow()

    // ────────────────────────────────────────────────────────────────────────
    // Initialization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the orchestrator and its managed engines.
     *
     * Engine initialization is performed in parallel to minimize startup latency.
     * The GPU engine (~2 s) and NPU engine (~0.8 s) load concurrently, so total
     * wall-clock startup time is bounded by the slower of the two (~2 s).
     *
     * Engines that fail to initialize are logged and marked [EngineState.ERROR];
     * the orchestrator itself transitions to [OrchestratorState.READY] as long as
     * at least one engine is available.
     *
     * @param initialMode The [ClawMode] to activate on startup. Defaults to [ClawMode.EFFICIENT_NPU].
     */
    suspend fun initialize(initialMode: ClawMode = ClawMode.EFFICIENT_NPU) {
        if (_orchestratorState.value != OrchestratorState.UNINITIALIZED) {
            Timber.tag(TAG).w("initialize() called in state %s — ignoring.", _orchestratorState.value)
            return
        }

        Timber.tag(TAG).i("ClawOrchestrator initializing with mode=%s…", initialMode)
        _orchestratorState.value = OrchestratorState.INITIALIZING

        // Launch both engine initializations concurrently
        val npuJob = orchestratorScope.launch { npuEngine.initialize() }
        val gpuJob = orchestratorScope.launch { gpuEngine.initialize() }
        npuJob.join()
        gpuJob.join()

        val npuReady = npuEngine.state == EngineState.READY
        val gpuReady = gpuEngine.state == EngineState.READY

        Timber.tag(TAG).i(
            "Engine init complete — NPU: %s | GPU: %s",
            npuEngine.state.label, gpuEngine.state.label
        )

        _mode.value = initialMode
        _orchestratorState.value = when {
            npuReady && gpuReady -> OrchestratorState.READY
            npuReady || gpuReady -> OrchestratorState.DEGRADED   // one engine is up
            else                 -> OrchestratorState.ERROR
        }

        Timber.tag(TAG).i("ClawOrchestrator state → %s", _orchestratorState.value)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Mode switching
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Switches the active [ClawMode] at runtime.
     *
     * The change takes effect on the **next** call to [infer]. In-flight requests
     * complete on their originally-selected engine.
     *
     * @param mode The new [ClawMode] to activate.
     */
    fun setMode(mode: ClawMode) {
        val previous = _mode.value
        if (previous == mode) {
            Timber.tag(TAG).d("setMode(%s) — already in this mode, no-op.", mode)
            return
        }
        _mode.value = mode
        Timber.tag(TAG).i("ClawMode: %s → %s", previous, mode)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Inference routing
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Routes an [InferenceRequest] to the appropriate engine based on the current [ClawMode].
     *
     * ### Routing rules
     *
     * | Mode                     | Engine(s) used                                          |
     * |--------------------------|---------------------------------------------------------|
     * | [ClawMode.EFFICIENT_NPU] | [NexaNpuEngine]                                         |
     * | [ClawMode.POWER_GPU]     | [GoogleGpuEngine]                                       |
     * | [ClawMode.HYBRID]        | [NexaNpuEngine] → [GoogleGpuEngine] if escalation needed |
     *
     * In [ClawMode.HYBRID], the NPU result is escalated to the GPU engine when:
     * - `result.confidence` is null **or** below [HYBRID_ESCALATION_THRESHOLD], **or**
     * - `result.error` is non-null (NPU failure always falls back to GPU).
     *
     * @param request The inference request to process.
     * @return [InferenceResult] from the winning engine. If escalation occurred,
     *         [InferenceResult.wasEscalated] is `true`.
     */
    suspend fun infer(request: InferenceRequest): InferenceResult = withContext(Dispatchers.Default) {
        val mode = _mode.value
        Timber.tag(TAG).d("[%s] infer() → mode=%s", request.requestId, mode)

        return@withContext when (mode) {
            ClawMode.EFFICIENT_NPU -> routeToNpu(request)
            ClawMode.POWER_GPU     -> routeToGpu(request)
            ClawMode.HYBRID        -> routeHybrid(request)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internal routing helpers
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun routeToNpu(request: InferenceRequest): InferenceResult {
        val result = npuEngine.infer(request)
        logResult(result, escalated = false)
        return result
    }

    private suspend fun routeToGpu(request: InferenceRequest): InferenceResult {
        val result = gpuEngine.infer(request)
        logResult(result, escalated = false)
        return result
    }

    private suspend fun routeHybrid(request: InferenceRequest): InferenceResult {
        // First pass — NPU (fast, low-power triage)
        val npuResult = npuEngine.infer(request)

        val shouldEscalate = shouldEscalateToGpu(npuResult)
        Timber.tag(TAG).d(
            "[%s] HYBRID — NPU confidence=%.2f, escalate=%b",
            request.requestId, npuResult.confidence ?: -1f, shouldEscalate
        )

        return if (shouldEscalate) {
            // Second pass — GPU (deep reasoning)
            val gpuResult = gpuEngine.infer(request)
            val escalatedResult = gpuResult.copy(wasEscalated = true)
            logResult(escalatedResult, escalated = true)
            escalatedResult
        } else {
            logResult(npuResult, escalated = false)
            npuResult
        }
    }

    /**
     * Determines whether a [ClawMode.HYBRID] NPU result should be escalated to the GPU.
     *
     * Escalation triggers:
     * - NPU returned an error.
     * - Confidence score is null (engine did not report one).
     * - Confidence score is below [HYBRID_ESCALATION_THRESHOLD].
     */
    private fun shouldEscalateToGpu(npuResult: InferenceResult): Boolean {
        if (!npuResult.isSuccess) return true
        val confidence = npuResult.confidence ?: return true
        return confidence < HYBRID_ESCALATION_THRESHOLD
    }

    private fun logResult(result: InferenceResult, escalated: Boolean) {
        Timber.tag(TAG).i(
            "[%s] Result ← engine=%s | tokens=%d | latency=%dms | escalated=%b | error=%s",
            result.requestId,
            result.engine.name,
            result.generatedTokens,
            result.latencyMs,
            escalated,
            result.error?.message ?: "none"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Shutdown
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Shuts down both engines and cancels the orchestrator's coroutine scope.
     *
     * Should be called from [com.kenju.claw.overlay.AgentOverlayService.onDestroy].
     * After [shutdown], [infer] calls will return [ErrorCode.ENGINE_NOT_READY].
     */
    suspend fun shutdown() {
        Timber.tag(TAG).i("ClawOrchestrator shutting down…")
        _orchestratorState.value = OrchestratorState.SHUTDOWN

        // Shut down engines concurrently
        val npuShutdown = orchestratorScope.launch { npuEngine.shutdown() }
        val gpuShutdown = orchestratorScope.launch { gpuEngine.shutdown() }
        npuShutdown.join()
        gpuShutdown.join()

        orchestratorJob.cancel()
        Timber.tag(TAG).i("ClawOrchestrator shut down.")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Companion — singleton factory
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "KenjuClaw/Orchestrator"

        /**
         * Confidence threshold below which a HYBRID-mode NPU result is escalated
         * to the GPU engine for deeper reasoning.
         *
         * Range: [0.0, 1.0]. Default: 0.75 (escalate when NPU is less than 75% confident).
         * Tune this value to balance battery life vs. accuracy.
         */
        const val HYBRID_ESCALATION_THRESHOLD = 0.75f

        @Volatile
        private var instance: ClawOrchestrator? = null

        /**
         * Returns the process-global [ClawOrchestrator] singleton, creating it on
         * first access. Safe for concurrent calls from multiple threads.
         *
         * @param context Any [Context] — the application context is used internally.
         */
        fun getInstance(context: Context): ClawOrchestrator =
            instance ?: synchronized(this) {
                instance ?: ClawOrchestrator(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Lifecycle state of the [ClawOrchestrator] itself (independent of individual engine states).
 */
enum class OrchestratorState {
    /** [ClawOrchestrator.initialize] has not been called yet. */
    UNINITIALIZED,
    /** Engine initialization is in progress. */
    INITIALIZING,
    /** Both engines are [EngineState.READY]. */
    READY,
    /** One engine is ready and one encountered an error. Partial operation is possible. */
    DEGRADED,
    /** Both engines failed to initialize. [ClawOrchestrator.infer] will return errors. */
    ERROR,
    /** [ClawOrchestrator.shutdown] has been called; all resources are released. */
    SHUTDOWN
}
