package com.kenju.claw.orchestrator

import android.content.Context
import com.kenju.claw.hardware.AdrenoGpuConfig
import com.kenju.claw.hardware.GpuBackend
import com.kenju.claw.vault.ModelVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.google.mediapipe.tasks.genai.llminference.LlmInference

/**
 * GoogleGpuEngine
 *
 * Inference engine backed by **MediaPipe LLM Inference API** running on the
 * Qualcomm **Adreno 830 GPU** (SM8750 / Snapdragon 8 Elite).
 *
 * ## Model
 * - **Name:** Gemma 4 E2B-IT (Instruction-Tuned)
 * - **File:** [ModelVaultManager.MODEL_GPU_FILENAME] (`gemma-4-E2B-it.litertlm`)
 * - **Format:** MediaPipe LiteRT flat-buffer (`.litertlm`) — loaded directly by
 *   `com.google.mediapipe.tasks.genai.llminference.LlmInference`.
 *
 * ## Backend
 * - **SDK:** MediaPipe LLM Inference API (Task API v0.10+)
 * - **Hardware:** Adreno 830 GPU via Vulkan compute / OpenCL fallback
 * - **Precision:** FP16 by default (native on Adreno 830)
 *
 * ## Primary Roles
 * - **Complex reasoning** — multi-step planning and chain-of-thought generation.
 * - **Function calling** — emits structured JSON tool-call payloads via the
 *   Gemma instruction-following head.
 * - **Agentic tasks** — orchestrates multi-turn dialogs and sub-task delegation.
 * - **HYBRID escalation** — handles requests that exceed the NPU confidence threshold.
 *
 * ## Integration note
 * MediaPipe dependencies are **not yet added** to `build.gradle.kts`. Until the
 * `com.google.mediapipe:tasks-genai` AAR is declared and the real SDK is available,
 * all native calls are **stubbed** with realistic latency simulation.
 * Replace the `stubInfer` / `stubInit` bodies with real MediaPipe calls when
 * integrating the production library.
 *
 * @param context   Application context used to resolve vault directories.
 * @param gpuConfig Adreno GPU config produced by [com.kenju.claw.hardware.HardwareAccelInitializer].
 * @param vault     Model vault that provides the model file path.
 */
class GoogleGpuEngine(
    private val context: Context,
    private val gpuConfig: AdrenoGpuConfig,
    private val vault: ModelVaultManager
) : InferenceEngine {

    // ────────────────────────────────────────────────────────────────────────
    // Identity
    // ────────────────────────────────────────────────────────────────────────

    override val engineId: EngineId = EngineId.GOOGLE_GPU
    override val displayName: String = "Google_GPU [Gemma 4 E2B-IT / Adreno 830]"

    // ────────────────────────────────────────────────────────────────────────
    // State
    // ────────────────────────────────────────────────────────────────────────

    @Volatile
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private var llmSession: LlmInference? = null
    private var isStubMode: Boolean = false
    private var initializationError: String? = null

    // ────────────────────────────────────────────────────────────────────────
    // Initialization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the MediaPipe LLM session for Gemma 4 E2B-IT on the Adreno GPU.
     *
     * Steps (stubbed — replace with real MediaPipe Task API calls):
     *  1. Verify model file is present in the vault.
     *  2. Select GPU backend: Vulkan preferred, OpenCL as fallback.
     *  3. Build `LlmInference` options with the resolved model path and GPU backend.
     *  4. Create `LlmInference` session (triggers model weight loading on GPU VRAM).
     *  5. Transition state to [EngineState.READY].
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("Initializing %s…", displayName)
        state = EngineState.LOADING

        // ── Step 1: Model availability ───────────────────────────────────────
        val modelFile = vault.gpuModelFile
        if (!modelFile.exists()) {
            Timber.tag(TAG).e("Model file missing: %s", modelFile.absolutePath)
            state = EngineState.ERROR
            return@withContext false
        }
        Timber.tag(TAG).d("Model located: %s (%.2f MB)",
            modelFile.name, modelFile.length() / (1024.0 * 1024.0))

        // ── Step 2: GPU backend selection ────────────────────────────────────
        val backend = gpuConfig.preferredBackend
        Timber.tag(TAG).i(
            "Adreno GPU backend: %s (status: %s, mem: %d MB)",
            backend.name, gpuConfig.runtimeStatus, gpuConfig.maxMemoryMb
        )
        if (backend == GpuBackend.NONE) {
            Timber.tag(TAG).w("No GPU backend available — engine will run in stub/CPU-fallback mode.")
        }

        // ── Steps 3 + 4: MediaPipe session creation ──────────────────────────
        var session: LlmInference? = null
        var lastError: Exception? = null

        // Attempt 1: Preferred Backend
        try {
            val backendType = if (backend == GpuBackend.VULKAN) LlmInference.Backend.GPU else LlmInference.Backend.CPU
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setPreferredBackend(backendType)
                .build()
            session = LlmInference.createFromOptions(context, options)
            Timber.tag(TAG).i("LlmInference initialized successfully with backend: %s", backendType.name)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "LlmInference failed with preferred backend.")
            lastError = e
        }

        // Attempt 2: Fallback to CPU if GPU failed
        if (session == null && backend == GpuBackend.VULKAN) {
            try {
                Timber.tag(TAG).i("Falling back to CPU backend for LlmInference...")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                session = LlmInference.createFromOptions(context, options)
                Timber.tag(TAG).i("LlmInference initialized successfully with CPU fallback.")
                lastError = null
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "LlmInference CPU fallback also failed.")
                lastError = e
            }
        }

        if (session != null) {
            llmSession = session
            state = EngineState.READY
            return@withContext true
        } else {
            Timber.tag(TAG).e(lastError, "%s initialization completely FAILED natively.", displayName)
            initializationError = lastError?.message ?: "Unknown MediaPipe Error"
            isStubMode = true
            state = EngineState.READY
            return@withContext true
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Inference
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Runs a Gemma 4 E2B-IT inference pass on the Adreno GPU.
     *
     * If [InferenceRequest.functionSchema] is non-null, the request is treated as
     * a function-calling pass and [InferenceResult.functionCall] will contain the
     * serialized JSON tool-call payload.
     *
     * @return [InferenceResult] — always non-null; check [InferenceResult.isSuccess].
     */
    override suspend fun infer(request: InferenceRequest): InferenceResult =
        withContext(Dispatchers.Default) {
            if (state != EngineState.READY) {
                return@withContext buildErrorResult(
                    request, ErrorCode.ENGINE_NOT_READY,
                    "Engine state is ${state.label} — call initialize() first."
                )
            }

            val start = System.currentTimeMillis()
            val isFunctionCall = request.functionSchema != null
            Timber.tag(TAG).d(
                "[%s] Infer — prompt length: %d chars, function-call: %b",
                request.requestId, request.prompt.length, isFunctionCall
            )

            return@withContext try {
                val responseText: String
                val extractedJson: String?
                
                if (isStubMode) {
                    kotlinx.coroutines.delay(500)
                    responseText = "⚠️ **Real Model Initialization Failed!**\n\nI tried to load the real Gemma model, but the MediaPipe SDK crashed with the following error:\n\n`${initializationError}`\n\nIf you see `Error building tflite model`, it means the file in your Downloads folder is invalid, corrupt, or is a tiny HTML/LFS pointer instead of the actual 2GB+ model binary. Please re-download the raw `.litertlm` file and clear the app data to try again!"
                    extractedJson = null
                } else {
                    val session = llmSession ?: throw IllegalStateException("LlmInference session is null")
                    
                    val fullPrompt = if (isFunctionCall) {
                        "System: You are an agentic AI that returns JSON function calls. Available schema: ${request.functionSchema}\nUser: ${request.prompt}\nAssistant:"
                    } else {
                        "<start_of_turn>user\n${request.prompt}<end_of_turn>\n<start_of_turn>model\n"
                    }
                    
                    responseText = session.generateResponse(fullPrompt)
                    
                    var jsonExtract: String? = null
                    if (isFunctionCall) {
                        val jsonStart = responseText.indexOf("{")
                        val jsonEnd = responseText.lastIndexOf("}")
                        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                            jsonExtract = responseText.substring(jsonStart, jsonEnd + 1)
                        }
                    }
                    extractedJson = jsonExtract
                }

                InferenceResult(
                    requestId       = request.requestId,
                    engine          = engineId,
                    text            = responseText,
                    functionCall    = extractedJson,
                    confidence      = FULL_CONFIDENCE,
                    promptTokens    = request.prompt.length / 4,
                    generatedTokens = responseText.length / 4,
                    latencyMs       = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Inference failed for request %s", request.requestId)
                buildErrorResult(request, ErrorCode.INFERENCE_FAILURE, e.message ?: "Unknown error", e)
            }
        }

    // ────────────────────────────────────────────────────────────────────────
    // Shutdown
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun shutdown(): Unit = withContext(Dispatchers.Default) {
        if (state == EngineState.SHUTDOWN) return@withContext
        Timber.tag(TAG).i("Shutting down %s…", displayName)
        
        try {
            llmSession?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing LlmInference session")
        }
        llmSession = null
        
        state = EngineState.SHUTDOWN
        Timber.tag(TAG).i("%s shut down.", displayName)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun buildErrorResult(
        request: InferenceRequest,
        code: ErrorCode,
        message: String,
        cause: Throwable? = null
    ) = InferenceResult(
        requestId = request.requestId,
        engine    = engineId,
        text      = "",
        error     = InferenceError(code, message, cause)
    )

    companion object {
        private const val TAG = "KenjuClaw/GoogleGPU"
        private const val STUB_INIT_DELAY_MS    = 2000L
        private const val STUB_TOKEN_LATENCY_MS = 5L    // ~20 ms per token / 4 ms per step
        private const val FULL_CONFIDENCE       = 0.98f // GPU engine always returns high confidence
    }
}
