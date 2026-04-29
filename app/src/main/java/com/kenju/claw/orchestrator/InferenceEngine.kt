package com.kenju.claw.orchestrator

/**
 * InferenceEngine
 *
 * Common interface implemented by every hardware-backed inference engine in
 * the KenjuClaw stack. The [ClawOrchestrator] programs against this abstraction,
 * allowing it to route requests without knowing the concrete backend details.
 *
 * ## Lifecycle
 *
 * ```
 * UNINITIALIZED ──initialize()──► READY ──shutdown()──► SHUTDOWN
 *                                   │
 *                             infer(request)
 *                                   │
 *                              InferenceResult
 * ```
 *
 * Engines must be initialized before [infer] is called. Calls to [infer] while
 * the engine is not [EngineState.READY] must return an [InferenceResult] with
 * [ErrorCode.ENGINE_NOT_READY] rather than throwing.
 *
 * ## Thread-safety
 *
 * Each implementation is responsible for serializing concurrent calls to [infer].
 * The [ClawOrchestrator] does **not** guarantee single-threaded access.
 */
interface InferenceEngine {

    /** Stable identifier for this engine instance. */
    val engineId: EngineId

    /** Human-readable name for logging and UI display. */
    val displayName: String

    /** Current lifecycle state of the engine (thread-safe observable). */
    val state: EngineState

    /**
     * Initializes the engine: loads the model from the vault, warms up the
     * backend, and transitions [state] to [EngineState.READY].
     *
     * This is a potentially long-running operation and **must** be called from
     * a coroutine or background thread. Implementations should post progress
     * updates through [state] as they go (UNINITIALIZED → LOADING → READY / ERROR).
     *
     * @return `true` if initialization succeeded; `false` otherwise.
     */
    suspend fun initialize(): Boolean

    /**
     * Runs a single inference pass and returns the result.
     *
     * Safe to call from any coroutine context; implementations will dispatch
     * to the appropriate dispatcher internally (e.g., [Dispatchers.Default]).
     *
     * Returns an [InferenceResult] with a non-null [InferenceResult.error] on
     * failure — callers should check [InferenceResult.isSuccess].
     *
     * @param request The inference request including prompt, optional image bytes,
     *                and generation parameters.
     */
    suspend fun infer(request: InferenceRequest): InferenceResult

    /**
     * Releases all native resources held by this engine (model buffers, JNI
     * sessions, GPU command queues). Transitions [state] to [EngineState.SHUTDOWN].
     *
     * Calling [infer] after [shutdown] must return [ErrorCode.ENGINE_NOT_READY].
     * [shutdown] is idempotent — multiple calls must not throw.
     */
    suspend fun shutdown()
}

/**
 * Lifecycle state of an [InferenceEngine].
 *
 * @param label Human-readable label for logging / diagnostics.
 */
enum class EngineState(val label: String) {
    /** Engine has been created but [InferenceEngine.initialize] has not been called. */
    UNINITIALIZED("Uninitialized"),
    /** Engine is currently loading its model and warming up the backend. */
    LOADING("Loading"),
    /** Engine is fully initialized and ready to accept inference requests. */
    READY("Ready"),
    /**
     * Engine is initialized but intentionally paused by the orchestrator
     * (e.g., the active [ClawMode] does not require this engine).
     * Transitions to [READY] quickly on next request without a full reload.
     */
    IDLE("Idle"),
    /** Engine encountered an unrecoverable error during initialization or inference. */
    ERROR("Error"),
    /** Engine has been shut down and released its resources. */
    SHUTDOWN("Shutdown")
}
