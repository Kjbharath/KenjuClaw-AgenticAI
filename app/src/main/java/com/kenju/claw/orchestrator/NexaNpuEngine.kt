package com.kenju.claw.orchestrator

import android.content.Context
import com.kenju.claw.bootstrap.ClawBootstrapper
import com.kenju.claw.hardware.HexagonNpuConfig
import com.kenju.claw.vault.ModelVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * NexaNpuEngine
 *
 * Inference engine backed by the **Nexa SDK** running on the Qualcomm **Hexagon NPU**
 * (HTP v79 on the Snapdragon 8 Elite / SM8750).
 *
 * ## Model
 * - **Name:** OmniNeural 4B
 * - **Format:** Sharded safetensors (NOT GGUF) — loaded via the Nexa SDK manifest.
 * - **Directory:** `<filesDir>/models/OmniNeural-4B/`
 * - **Layout:**
 *   ```
 *   OmniNeural-4B/
 *   ├── config.json
 *   ├── claw_config.json       ← synced from res/raw by ClawBootstrapper
 *   ├── nexa.manifest          ← Nexa SDK model descriptor (plugin_id = "npu")
 *   ├── tokenizer.json
 *   ├── model-00001-of-N.safetensors
 *   └── …
 *   ```
 *
 * ## Backend
 * - **SDK:** Nexa SDK (manifest-driven, on-device NPU acceleration)
 * - **Hardware:** Hexagon v79 DSP (HTP) — ~50 TOPS
 * - **Precision:** INT4 (q4_0) by default, configured in `claw_config.json`
 * - **Plugin ID:** `"npu"` — instructs the Nexa runtime to route to HTP
 *
 * ## Primary Roles
 * - **Screen observation** — processes screenshot byte arrays passed in [InferenceRequest.imageBytes].
 * - **OCR** — extracts text from UI regions.
 * - **Intent detection** — classifies user intent for lightweight routing decisions.
 * - **HYBRID triage** — first-pass confidence scoring that may escalate to [GoogleGpuEngine].
 *
 * ## Integration note
 * The Nexa SDK is loaded from the device's native library directory. Until the real SDK
 * AAR is added to the Gradle dependencies, all native calls are **stubbed** with realistic
 * latency simulation. Replace the `stubInfer` / `stubInit` bodies with real SDK calls
 * when integrating the production library.
 *
 * @param context   Application context used to resolve vault directories.
 * @param npuConfig Hexagon NPU config produced by [com.kenju.claw.hardware.HardwareAccelInitializer].
 * @param vault     Model vault that provides the model directory path.
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

    // ── Loaded config (populated during initialization) ──────────────────────
    private var loadedConfig: NexaModelConfig? = null

    // ────────────────────────────────────────────────────────────────────────
    // Initialization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the Nexa SDK session for OmniNeural 4B on the Hexagon HTP.
     *
     * Steps:
     *  1. Verify model directory exists with shards + manifest.
     *  2. Parse `nexa.manifest` to read plugin_id, shard list, and precision.
     *  3. Parse `claw_config.json` for inference parameters.
     *  4. Verify NPU hardware availability.
     *  5. Create a Nexa inference session (stubbed until SDK integrated).
     *  6. Transition state to [EngineState.READY].
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("Initializing %s…", displayName)
        state = EngineState.LOADING

        // ── Step 1: Model directory validation ──────────────────────────────
        val modelDir = vault.npuModelDir
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Timber.tag(TAG).e("Model directory missing: %s", modelDir.absolutePath)
            state = EngineState.ERROR
            return@withContext false
        }

        val manifestFile = vault.npuManifestFile
        if (!manifestFile.exists()) {
            Timber.tag(TAG).e("nexa.manifest missing in: %s", modelDir.absolutePath)
            state = EngineState.ERROR
            return@withContext false
        }

        // Count shards
        val shards = modelDir.listFiles()?.filter {
            it.name.endsWith(".safetensors") || it.name.endsWith(".bin")
        } ?: emptyList()

        if (shards.isEmpty()) {
            Timber.tag(TAG).e("No weight shards found in: %s", modelDir.absolutePath)
            state = EngineState.ERROR
            return@withContext false
        }

        Timber.tag(TAG).d("Model directory: %s (%d shards)", modelDir.absolutePath, shards.size)

        // ── Step 2: Parse nexa.manifest ─────────────────────────────────────
        val manifest = try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse nexa.manifest")
            state = EngineState.ERROR
            return@withContext false
        }

        val pluginId = manifest.optString("plugin_id", "cpu")
        if (pluginId != ClawBootstrapper.NPU_PLUGIN_ID) {
            Timber.tag(TAG).w(
                "nexa.manifest plugin_id='%s' — expected '%s'. NPU acceleration may not activate.",
                pluginId, ClawBootstrapper.NPU_PLUGIN_ID
            )
        } else {
            Timber.tag(TAG).i("nexa.manifest plugin_id=%s ✓ (Hexagon NPU)", pluginId)
        }

        val manifestShardCount = manifest.optInt("shard_count", shards.size)
        val runtime = manifest.optString("runtime", "hexagon")
        val precision = manifest.optString("precision", "int4")

        Timber.tag(TAG).d(
            "Manifest: model=%s, shards=%d, runtime=%s, precision=%s, plugin=%s",
            manifest.optString("model_name", "unknown"),
            manifestShardCount, runtime, precision, pluginId
        )

        // ── Step 3: Parse claw_config.json ──────────────────────────────────
        val configFile = vault.npuConfigFile
        val clawConfig = if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())

                // Parse npu_config block
                val npuJson = json.optJSONObject("npu_config")
                val npuSessionConfig = NpuSessionConfig(
                    device              = npuJson?.optString("device", "Snapdragon_8_Elite") ?: "Snapdragon_8_Elite",
                    soc                 = npuJson?.optString("soc", "sm8750") ?: "sm8750",
                    precision           = npuJson?.optString("precision", "int4") ?: "int4",
                    runtime             = npuJson?.optString("runtime", "hexagon") ?: "hexagon",
                    hexagonVersion      = npuJson?.optString("hexagon_version", "v79") ?: "v79",
                    powerProfile        = npuJson?.optString("power_profile", "balanced") ?: "balanced",
                    cacheCompiledGraphs = npuJson?.optBoolean("cache_compiled_graphs", true) ?: true,
                    numThreads          = npuJson?.optInt("num_threads", 4) ?: 4,
                    batchSize           = npuJson?.optInt("batch_size", 1) ?: 1,
                    contextWindow       = npuJson?.optInt("context_window", 4096) ?: 4096,
                    kvCacheType         = npuJson?.optString("kv_cache_type", "int8") ?: "int8"
                )

                // Parse generation block
                val genJson = json.optJSONObject("generation")
                val generationConfig = GenerationConfig(
                    stopTokens = genJson?.optJSONArray("stop_tokens")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: listOf("<|end|>", "</s>"),
                    stream = genJson?.optBoolean("stream", true) ?: true,
                    seed   = genJson?.optInt("seed", -1) ?: -1
                )

                // Parse role-specific configs
                val rolesJson = json.optJSONObject("roles")
                val roleConfigs = mutableMapOf<String, RoleConfig>()
                rolesJson?.keys()?.forEach { key ->
                    val roleObj = rolesJson.optJSONObject(key)
                    if (roleObj != null) {
                        roleConfigs[key] = RoleConfig(
                            systemPrompt = roleObj.optString("system_prompt", ""),
                            maxTokens    = roleObj.optInt("max_tokens", 2048),
                            temperature  = roleObj.optDouble("temperature", 0.6).toFloat()
                        )
                    }
                }

                NexaModelConfig(
                    modelType         = json.optString("model_type", "omnineural"),
                    modelName         = json.optString("model_name", "OmniNeural-4B"),
                    maxTokens         = json.optInt("max_tokens", 4096),
                    enableThinking    = json.optBoolean("enable_thinking", true),
                    temperature       = json.optDouble("temperature", 0.6).toFloat(),
                    topP              = json.optDouble("top_p", 0.92).toFloat(),
                    topK              = json.optInt("top_k", 40),
                    repetitionPenalty = json.optDouble("repetition_penalty", 1.15).toFloat(),
                    quantization      = json.optString("quantization", "q4_0"),
                    pluginId          = json.optString("plugin_id", pluginId),
                    npuSession        = npuSessionConfig,
                    generation        = generationConfig,
                    roles             = roleConfigs
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse claw_config.json — using defaults")
                NexaModelConfig()
            }
        } else {
            Timber.tag(TAG).w("claw_config.json not found — using defaults")
            NexaModelConfig()
        }

        loadedConfig = clawConfig
        Timber.tag(TAG).i(
            "Config loaded: model=%s, max_tokens=%d, temp=%.1f, top_k=%d, rep_pen=%.2f, quant=%s, thinking=%b, context=%d, kv=%s, roles=%d",
            clawConfig.modelName, clawConfig.maxTokens, clawConfig.temperature,
            clawConfig.topK, clawConfig.repetitionPenalty, clawConfig.quantization,
            clawConfig.enableThinking, clawConfig.npuSession.contextWindow,
            clawConfig.npuSession.kvCacheType, clawConfig.roles.size
        )

        // ── Step 4: Hardware availability ───────────────────────────────────
        if (!npuConfig.isAvailable) {
            Timber.tag(TAG).w(
                "Hexagon NPU unavailable (%s) — engine will run in stub/CPU-fallback mode.",
                npuConfig.runtimeStatus
            )
        } else {
            Timber.tag(TAG).i("Hexagon NPU ready: %s", npuConfig.runtimeStatus)
        }

        // ── Step 5: SDK session creation (STUBBED) ──────────────────────────
        // TODO: Replace with real Nexa SDK manifest-driven loading:
        //
        //   val options = NexaModelOptions.builder()
        //       .setModelDirectory(modelDir.absolutePath)
        //       .setManifestFile(manifestFile.absolutePath)
        //       .setPluginId("npu")                       // Hexagon HTP
        //       .setPrecision(NexaPrecision.INT4)
        //       .setCacheDir(npuConfig.cacheDir)
        //       .setMaxTokens(clawConfig.maxTokens)
        //       .setTemperature(clawConfig.temperature)
        //       .build()
        //
        //   nexaSession = NexaInference.createFromManifest(context, options)
        //   nexaSession.warmUp()
        //
        val stubSuccess = stubInitializeSession(modelDir.absolutePath, shards.size)

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
     * Uses the loaded `claw_config.json` parameters for max_tokens, temperature,
     * and top_p. If [InferenceRequest.imageBytes] is provided, the image is passed
     * through the vision encoder head before the text decoder generates output.
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

            val config = loadedConfig ?: NexaModelConfig()
            val effectiveMaxTokens = minOf(request.maxTokens, config.maxTokens)

            val start = System.currentTimeMillis()
            Timber.tag(TAG).d(
                "[%s] Infer — prompt: %d chars, vision: %s, maxTokens: %d, temp: %.1f",
                request.requestId, request.prompt.length,
                if (request.imageBytes != null) "${request.imageBytes.size} B" else "none",
                effectiveMaxTokens, config.temperature
            )

            return@withContext try {
                // TODO: Replace with real Nexa SDK inference call:
                //
                //   val prompt = NexaPrompt.builder()
                //       .systemPrompt(request.systemPrompt)
                //       .userMessage(request.prompt)
                //       .apply { request.imageBytes?.let { visionInput(it) } }
                //       .enableThinking(config.enableThinking)
                //       .build()
                //
                //   val response = nexaSession.generate(prompt,
                //       maxTokens   = effectiveMaxTokens,
                //       temperature = config.temperature,
                //       topP        = config.topP
                //   )
                //
                val response = stubInfer(request, config)

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
        loadedConfig = null
        state = EngineState.SHUTDOWN
        Timber.tag(TAG).i("%s shut down.", displayName)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Stub implementations (replace with real Nexa SDK calls)
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun stubInitializeSession(modelDirPath: String, shardCount: Int): Boolean =
        withContext(Dispatchers.Default) {
            // Simulate shard loading latency (~200 ms per shard on real HTP)
            val delay = minOf(shardCount * 200L, STUB_MAX_INIT_DELAY_MS)
            kotlinx.coroutines.delay(delay)
            Timber.tag(TAG).d(
                "[STUB] Session created from manifest — dir: %s, shards: %d",
                modelDirPath, shardCount
            )
            true
        }

    private data class StubResponse(
        val text: String,
        val confidence: Float,
        val promptTokens: Int,
        val generatedTokens: Int
    )

    private suspend fun stubInfer(request: InferenceRequest, config: NexaModelConfig): StubResponse =
        withContext(Dispatchers.Default) {
            // Simulate NPU inference latency (~45 ms per token on INT4 OmniNeural 4B)
            val estimatedTokens = minOf(request.maxTokens, 64)
            kotlinx.coroutines.delay(estimatedTokens * STUB_TOKEN_LATENCY_MS)

            val hasVision = request.imageBytes != null
            val stubText = if (hasVision) {
                "[NPU/OmniNeural-4B] Vision+text response (${config.quantization}) for: \"${request.prompt.take(60)}…\""
            } else {
                "[NPU/OmniNeural-4B] Text response (${config.quantization}) for: \"${request.prompt.take(80)}…\""
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
        private const val STUB_MAX_INIT_DELAY_MS = 3000L
        private const val STUB_TOKEN_LATENCY_MS  = 8L
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Config data class
// ────────────────────────────────────────────────────────────────────────────

/**
 * Parsed inference configuration from `claw_config.json`.
 * These values control the Nexa SDK session parameters.
 */
data class NexaModelConfig(
    val modelType:         String          = "omnineural",
    val modelName:         String          = "OmniNeural-4B",
    val maxTokens:         Int             = 4096,
    val enableThinking:    Boolean         = true,
    val temperature:       Float           = 0.6f,
    val topP:              Float           = 0.92f,
    val topK:              Int             = 40,
    val repetitionPenalty: Float           = 1.15f,
    val quantization:      String          = "q4_0",
    val pluginId:          String          = "npu",
    val npuSession:        NpuSessionConfig = NpuSessionConfig(),
    val generation:        GenerationConfig = GenerationConfig(),
    val roles:             Map<String, RoleConfig> = emptyMap()
)

/**
 * NPU-specific hardware session parameters.
 * Maps to the `npu_config` block in `claw_config.json`.
 */
data class NpuSessionConfig(
    val device:              String  = "Snapdragon_8_Elite",
    val soc:                 String  = "sm8750",
    val precision:           String  = "int4",
    val runtime:             String  = "hexagon",
    val hexagonVersion:      String  = "v79",
    val powerProfile:        String  = "balanced",
    val cacheCompiledGraphs: Boolean = true,
    val numThreads:          Int     = 4,
    val batchSize:           Int     = 1,
    val contextWindow:       Int     = 4096,
    val kvCacheType:         String  = "int8"
)

/**
 * Token generation settings.
 * Maps to the `generation` block in `claw_config.json`.
 */
data class GenerationConfig(
    val stopTokens: List<String> = listOf("<|end|>", "</s>"),
    val stream:     Boolean      = true,
    val seed:       Int          = -1
)

/**
 * Per-role inference overrides (screen_observation, ocr, intent_detection, agentic).
 * Maps to entries in the `roles` block in `claw_config.json`.
 */
data class RoleConfig(
    val systemPrompt: String = "",
    val maxTokens:    Int    = 2048,
    val temperature:  Float  = 0.6f
)
