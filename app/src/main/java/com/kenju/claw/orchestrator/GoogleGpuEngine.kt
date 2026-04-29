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
        try {
            val backendType = if (backend == GpuBackend.VULKAN) {
                LlmInference.Backend.GPU
            } else {
                LlmInference.Backend.CPU
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .setPreferredBackend(backendType)
                .build()
                
            llmSession = LlmInference.createFromOptions(context, options)

            state = EngineState.READY
            Timber.tag(TAG).i("%s initialized and READY. LlmInference active.", displayName)
            return@withContext true
        } catch (e: Exception) {
            state = EngineState.ERROR
            Timber.tag(TAG).e(e, "%s initialization FAILED.", displayName)
            return@withContext false
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
                val session = llmSession ?: throw IllegalStateException("LlmInference session is null")
                
                // In Gemma, we format the prompt specifically for instruction following
                // E2B usually prefers simple user prompts or function-call structured prompts
                val fullPrompt = if (isFunctionCall) {
                    "System: You are an agentic AI that returns JSON function calls. Available schema: ${request.functionSchema}\nUser: ${request.prompt}\nAssistant:"
                } else {
                    "<start_of_turn>user\n${request.prompt}<end_of_turn>\n<start_of_turn>model\n"
                }
                
                val responseText = session.generateResponse(fullPrompt)
                
                // Extremely simple JSON extraction for function calling
                var extractedJson: String? = null
                if (isFunctionCall) {
                    val jsonStart = responseText.indexOf("{")
                    val jsonEnd = responseText.lastIndexOf("}")
                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        extractedJson = responseText.substring(jsonStart, jsonEnd + 1)
                    }
                }

                InferenceResult(
                    requestId       = request.requestId,
                    engine          = engineId,
                    text            = responseText,
                    functionCall    = extractedJson,
                    confidence      = FULL_CONFIDENCE,
                    promptTokens    = fullPrompt.length / 4,
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
