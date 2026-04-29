package com.kenju.claw.orchestrator

import android.content.Context
import com.kenju.claw.hardware.HexagonNpuConfig
import com.kenju.claw.hardware.InferencePrecision
import com.kenju.claw.vault.ModelVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NexaNpuEngine
 *
 * Inference engine backed by the **Nexa SDK** running on the Qualcomm **Hexagon NPU**
 * (HTP v79 on the Snapdragon 8 Elite / SM8750).
 *
 * ## Model
 * - **Name:** OmniNeural 4B
 * - **File:** [ModelVaultManager.MODEL_NPU_FILENAME] (`omnineural_4b.gguf`)
 * - **Format:** GGUF — consumed via the Nexa SDK's llama.cpp / QNN-HTP delegate.
 *
 * ## Backend
 * - **SDK:** Nexa SDK (on-device NPU acceleration via QNN HTP delegate)
 * - **Hardware:** Hexagon v79 DSP (HTP) — ~50 TOPS
 * - **Precision:** INT8 by default; INT4 when [HexagonNpuConfig.precision] is [InferencePrecision.INT4]
 *
 * ## Primary Roles
 * - **Screen observation** — processes screenshot byte arrays passed in [InferenceRequest.imageBytes].
 * - **OCR** — extracts text from UI regions.
 * - **Intent detection** — classifies user intent for lightweight routing decisions.
 * - **HYBRID triage** — first-pass confidence scoring that may escalate to [GoogleGpuEngine].
 *
 * ## Integration note
 * The Nexa SDK is loaded from the device's native library directory.  Until the real SDK
 * AAR is added to the Gradle dependencies, all native calls are **stubbed** with realistic
 * latency simulation.  Replace the `stubInfer` / `stubInit` bodies with real SDK calls
 * when integrating the production library.
 *
 * @param context   Application context used to resolve vault directories.
 * @param npuConfig Hexagon NPU config produced by [com.kenju.claw.hardware.HardwareAccelInitializer].
 * @param vault     Model vault that provides the model file path.
 */
class NexaNpuEngine(
    private val context: Context,
    private val npuConfig: HexagonNpuConfig,
    private val vault: ModelVaultManager
) : InferenceEngine {

    // ────────────────────────────────────────────────────────────────────────
    // Identity
    // ────────────────────────────────────────────────────────────────────────

    override val engineId: EngineId = EngineId.NEXA_NPU
    override val displayName: String = "Nexa_NPU [OmniNeural 4B / Hexagon v79]"

    // ────────────────────────────────────────────────────────────────────────
    // State
    // ────────────────────────────────────────────────────────────────────────

    @Volatile
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    // ────────────────────────────────────────────────────────────────────────
    // Initialization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the Nexa SDK session for OmniNeural 4B on the Hexagon HTP.
     *
     * Steps (stubbed — replace with real Nexa SDK calls):
     *  1. Verify model file is present in the vault.
     *  2. Verify NPU hardware is available ([HexagonNpuConfig.isAvailable]).
     *  3. Create a Nexa `LlmInference` session bound to the HTP backend.
     *  4. Warm up the model (first inference is slow due to graph compilation).
     *  5. Transition state to [EngineState.READY].
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("Initializing %s…", displayName)
        state = EngineState.LOADING

        // ── Step 1: Model availability ───────────────────────────────────────
        val modelFile = vault.npuModelFile
        if (!modelFile.exists()) {
            Timber.tag(TAG).e("Model file missing: %s", modelFile.absolutePath)
            state = EngineState.ERROR
            return@withContext false
        }
        Timber.tag(TAG).d("Model located: %s (%.2f MB)",
            modelFile.name, modelFile.length() / (1024.0 * 1024.0))

        // ── Step 2: Hardware availability ────────────────────────────────────
        if (!npuConfig.isAvailable) {
            Timber.tag(TAG).w(
                "Hexagon NPU unavailable (%s) — engine will run in stub/CPU-fallback mode.",
                npuConfig.runtimeStatus
            )
        } else {
            Timber.tag(TAG).i("Hexagon NPU ready: %s", npuConfig.runtimeStatus)
        }

        // ── Step 3 + 4: SDK session creation & warm-up (STUBBED) ─────────────
        // TODO: Replace with real Nexa SDK calls, e.g.:
        //   val options = NexaLlmOptions.Builder()
        //       .setModelPath(modelFile.absolutePath)
        //       .setBackend(NexaBackend.HEXAGON_HTP)
        //       .setPrecision(PRECISION_INT8)
        //       .setCacheDir(npuConfig.cacheDir)
        //       .build()
        //   nexaSession = NexaLlmInference.create(context, options)
        //   nexaSession.warmUp()
        val stubSuccess = stubInitializeSession(modelFile.absolutePath)

        return@withContext if (stubSuccess) {
            state = EngineState.READY
            Timber.tag(TAG).i("%s initialized and READY.", displayName)
            true
        } else {
            state = EngineState.ERROR
            Timber.tag(TAG).e("%s initialization FAILED.", displayName)
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Inference
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Runs an OmniNeural 4B inference pass on the Hexagon NPU.
     *
     * If [InferenceRequest.imageBytes] is provided, the image is passed through
     * the vision encoder head before the text decoder generates output.
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
            Timber.tag(TAG).d(
                "[%s] Infer — prompt length: %d chars, vision: %s",
                request.requestId, request.prompt.length,
                if (request.imageBytes != null) "${request.imageBytes.size} B" else "none"
            )

            return@withContext try {
                // TODO: Replace with real Nexa SDK inference call, e.g.:
                //   val response = nexaSession.generateResponse(
                //       NexaPrompt.build {
                //           system(request.systemPrompt)
                //           user(request.prompt)
                //           imageBytes?.let { vision(it) }
                //       },
                //       maxTokens = request.maxTokens,
                //       temperature = request.temperature
                //   )
                val response = stubInfer(request)

                InferenceResult(
                    requestId       = request.requestId,
                    engine          = engineId,
                    text            = response.text,
                    confidence      = response.confidence,
                    promptTokens    = response.promptTokens,
                    generatedTokens = response.generatedTokens,
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
        // TODO: nexaSession.close()
        state = EngineState.SHUTDOWN
        Timber.tag(TAG).i("%s shut down.", displayName)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Stub implementations (replace with real Nexa SDK calls)
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun stubInitializeSession(modelPath: String): Boolean =
        withContext(Dispatchers.Default) {
            // Simulate graph compilation latency on first init (~800 ms on real HTP)
            kotlinx.coroutines.delay(STUB_INIT_DELAY_MS)
            Timber.tag(TAG).d("[STUB] Session created for model at: %s", modelPath)
            true
        }

    private data class StubResponse(
        val text: String,
        val confidence: Float,
        val promptTokens: Int,
        val generatedTokens: Int
    )

    private suspend fun stubInfer(request: InferenceRequest): StubResponse =
        withContext(Dispatchers.Default) {
            // Simulate NPU inference latency (~45 ms per token on INT8 OmniNeural 4B)
            val estimatedTokens = minOf(request.maxTokens, 64)
            kotlinx.coroutines.delay(estimatedTokens * STUB_TOKEN_LATENCY_MS)

            val hasVision = request.imageBytes != null
            val stubText = if (hasVision) {
                "[NPU/OmniNeural-4B] Vision+text response stub for: \"${request.prompt.take(60)}…\""
            } else {
                "[NPU/OmniNeural-4B] Text response stub for: \"${request.prompt.take(80)}…\""
            }
            StubResponse(
                text            = stubText,
                confidence      = 0.72f,  // Realistic NPU confidence — may trigger HYBRID escalation
                promptTokens    = request.prompt.length / 4,
                generatedTokens = estimatedTokens
            )
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
        private const val TAG = "KenjuClaw/NexaNPU"
        private const val STUB_INIT_DELAY_MS    = 800L
        private const val STUB_TOKEN_LATENCY_MS = 8L    // ~45 ms per token / 6 ms per step
    }
}
