package com.kenju.claw.vault

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

/**
 * ModelVaultManager
 *
 * Manages the private model storage vault for KenjuClaw's inference stack.
 *
 * ## Storage layout
 * ```
 * <context.filesDir>/
 * └── models/
 *     ├── OmniNeural-4B/           ← Hexagon NPU model  (sharded safetensors + Nexa manifest)
 *     │   ├── config.json
 *     │   ├── claw_config.json     ← synced from res/raw by ClawBootstrapper
 *     │   ├── nexa.manifest        ← Nexa SDK loader descriptor (plugin_id = "npu")
 *     │   ├── tokenizer.json
 *     │   ├── model-00001-of-NNNNN.safetensors
 *     │   └── …
 *     └── gemma_4_e2b.bin          ← Adreno GPU model  (TFLite / flat-buffer format)
 * ```
 *
 * ## Responsibilities
 *  1. **Directory provisioning** — Ensures `models/` exists under the app's private
 *     `filesDir`. No external-storage or special permissions are required to *read*
 *     files already placed here; [MANAGE_EXTERNAL_STORAGE] is only needed during
 *     the initial import step (handled elsewhere).
 *  2. **Model verification** — [verifyModels] checks presence, readability, and a
 *     minimum sane file size for each expected model file.
 *  3. **URI provisioning** — [getModelUri] returns a content:// [Uri] backed by
 *     [FileProvider] so that inference engines (and any bound service) can open
 *     model files via [ContentResolver] without path-based access.
 *
 * ## Thread safety
 * All public methods are safe to call from any thread. Directory creation and
 * file-stat operations are fast and do not require coroutine dispatch.
 *
 * @param context Application context — used for [filesDir] and [FileProvider].
 */
class ModelVaultManager(private val context: Context) {

    // ────────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "KenjuClaw/Vault"

        /** Sub-directory name within [Context.filesDir]. */
        private const val MODELS_DIR = "models"

        /** FileProvider authority — must match the <provider> entry in AndroidManifest. */
        private const val FILE_PROVIDER_AUTHORITY = "com.kenju.claw.fileprovider"

        /** Minimum acceptable file size (bytes). Files smaller than this are treated
         *  as corrupt / incomplete downloads. 1 MB threshold. */
        private const val MIN_MODEL_SIZE_BYTES = 1_024 * 1_024L   // 1 MB

        // ── Model file descriptors ───────────────────────────────────────────

        /** Hexagon NPU model directory — sharded safetensors + nexa.manifest. */
        const val MODEL_NPU_DIR = "OmniNeural-4B"

        /** Nexa SDK manifest inside the NPU model directory. */
        const val NEXA_MANIFEST = "nexa.manifest"

        /** Config file synced from res/raw by ClawBootstrapper. */
        const val CLAW_CONFIG = "claw_config.json"

        /** @deprecated Kept for backward compat — use [MODEL_NPU_DIR] instead. */
        @Deprecated("Use MODEL_NPU_DIR for sharded model layout", replaceWith = ReplaceWith("MODEL_NPU_DIR"))
        const val MODEL_NPU_FILENAME = "omnineural_4b.gguf"

        /** Adreno GPU model — flat-buffer format, consumed via TFLite GPU delegate. */
        const val MODEL_GPU_FILENAME = "gemma-4-E2B-it.litertlm"
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * The private vault directory: `<filesDir>/models/`.
     * Guaranteed to exist after [ensureVaultReady] is called.
     */
    val vaultDir: File = File(context.filesDir, MODELS_DIR)

    /**
     * Ensures the vault directory exists and is writable.
     * Call this once during application startup (e.g., from [KenjuClawApplication]).
     *
     * @return `true` if the directory is ready; `false` on failure.
     */
    fun ensureVaultReady(): Boolean {
        return try {
            if (!vaultDir.exists()) {
                val created = vaultDir.mkdirs()
                if (created) {
                    Timber.tag(TAG).i("Vault directory created: %s", vaultDir.absolutePath)
                } else {
                    Timber.tag(TAG).e("Failed to create vault directory: %s", vaultDir.absolutePath)
                    return false
                }
            } else {
                Timber.tag(TAG).d("Vault directory already exists: %s", vaultDir.absolutePath)
            }
            vaultDir.canWrite().also { writable ->
                if (!writable) Timber.tag(TAG).e("Vault directory is not writable!")
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Security exception accessing vault directory")
            false
        }
    }

    /**
     * Verifies that both inference model files are present, readable, and
     * exceed the minimum size threshold.
     *
     * @return A [VaultVerificationResult] describing the state of each model.
     */
    fun verifyModels(): VaultVerificationResult {
        val npuStatus = checkNpuModelDir()
        val gpuStatus = checkModel(MODEL_GPU_FILENAME, ModelRole.GPU)

        val result = VaultVerificationResult(
            npuModel = npuStatus,
            gpuModel = gpuStatus,
            allPresent = npuStatus.isValid && gpuStatus.isValid
        )

        Timber.tag(TAG).i(
            "Vault verification — NPU: %s | GPU: %s | allPresent: %b",
            npuStatus.statusLabel,
            gpuStatus.statusLabel,
            result.allPresent
        )

        return result
    }

    /**
     * Returns a content:// [Uri] for a model file so that inference engines
     * can open it via [android.content.ContentResolver.openFileDescriptor].
     *
     * The file is served by [FileProvider] and is readable by any component
     * that receives the URI (e.g., a bound [android.app.Service]).
     *
     * @param filename One of [MODEL_NPU_FILENAME] or [MODEL_GPU_FILENAME].
     * @return A content:// [Uri], or `null` if the file does not exist.
     * @throws IllegalArgumentException if [filename] contains path separators.
     */
    fun getModelUri(filename: String): Uri? {
        require(!filename.contains(File.separatorChar)) {
            "filename must not contain path separators — got: '$filename'"
        }

        val file = File(vaultDir, filename)
        if (!file.exists()) {
            Timber.tag(TAG).w("getModelUri: file not found — %s", file.absolutePath)
            return null
        }

        return try {
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file).also { uri ->
                Timber.tag(TAG).d("URI issued for '%s' → %s", filename, uri)
            }
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "FileProvider could not resolve path for '%s'", filename)
            null
        }
    }

    /**
     * Convenience overload — returns the URI for the NPU model directly.
     * @return content:// URI for [MODEL_NPU_FILENAME], or `null` if missing.
     */
    fun getNpuModelUri(): Uri? = getModelUri(MODEL_NPU_FILENAME)

    /**
     * Convenience overload — returns the URI for the GPU model directly.
     * @return content:// URI for [MODEL_GPU_FILENAME], or `null` if missing.
     */
    fun getGpuModelUri(): Uri? = getModelUri(MODEL_GPU_FILENAME)

    /**
     * Returns the [File] reference for a model — for use by in-process components
     * that can accept a raw [File] path (e.g., llama.cpp JNI bridge which takes an
     * absolute path string rather than a file descriptor).
     *
     * **Do not share this [File] across process boundaries** — use [getModelUri] instead.
     *
     * @return The [File], whether or not it currently exists.
     */
    fun getModelFile(filename: String): File = File(vaultDir, filename)

    /** Directory containing OmniNeural-4B shards, config, and nexa.manifest. */
    val npuModelDir: File get() = File(vaultDir, MODEL_NPU_DIR)

    /** Direct [File] reference for the nexa.manifest inside the NPU model dir. */
    val npuManifestFile: File get() = File(npuModelDir, NEXA_MANIFEST)

    /** Direct [File] reference for the claw_config.json inside the NPU model dir. */
    val npuConfigFile: File get() = File(npuModelDir, CLAW_CONFIG)

    /**
     * @deprecated Use [npuModelDir] for the sharded layout.
     */
    @Deprecated("Use npuModelDir for sharded model layout", replaceWith = ReplaceWith("npuModelDir"))
    val npuModelFile: File get() = npuModelDir

    /** Direct [File] reference for the GPU model. */
    val gpuModelFile: File get() = getModelFile(MODEL_GPU_FILENAME)

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Checks a single model file (used for GPU model).
     */
    private fun checkModel(filename: String, role: ModelRole): ModelStatus {
        val file = File(vaultDir, filename)

        return when {
            !file.exists() -> {
                Timber.tag(TAG).w("[%s] Missing: %s", role, filename)
                ModelStatus(
                    filename    = filename,
                    role        = role,
                    isPresent   = false,
                    isReadable  = false,
                    sizeBytes   = 0L,
                    isValid     = false,
                    statusLabel = "MISSING"
                )
            }
            !file.canRead() -> {
                Timber.tag(TAG).w("[%s] Unreadable: %s", role, filename)
                ModelStatus(
                    filename    = filename,
                    role        = role,
                    isPresent   = true,
                    isReadable  = false,
                    sizeBytes   = file.length(),
                    isValid     = false,
                    statusLabel = "UNREADABLE"
                )
            }
            file.length() < MIN_MODEL_SIZE_BYTES -> {
                Timber.tag(TAG).w(
                    "[%s] File too small (%d bytes < %d min): %s",
                    role, file.length(), MIN_MODEL_SIZE_BYTES, filename
                )
                ModelStatus(
                    filename    = filename,
                    role        = role,
                    isPresent   = true,
                    isReadable  = true,
                    sizeBytes   = file.length(),
                    isValid     = false,
                    statusLabel = "CORRUPT (${file.length()} B)"
                )
            }
            else -> {
                Timber.tag(TAG).i(
                    "[%s] OK — %s (%.2f MB)",
                    role, filename, file.length() / (1024.0 * 1024.0)
                )
                ModelStatus(
                    filename    = filename,
                    role        = role,
                    isPresent   = true,
                    isReadable  = true,
                    sizeBytes   = file.length(),
                    isValid     = true,
                    statusLabel = "OK (%.2f MB)".format(file.length() / (1024.0 * 1024.0))
                )
            }
        }
    }

    /**
     * Checks the NPU sharded model directory.
     * Validates: directory exists, nexa.manifest present, at least one shard present.
     */
    private fun checkNpuModelDir(): ModelStatus {
        val dir = npuModelDir
        val manifest = npuManifestFile

        if (!dir.exists() || !dir.isDirectory) {
            Timber.tag(TAG).w("[NPU] Model directory missing: %s", dir.absolutePath)
            return ModelStatus(
                filename    = MODEL_NPU_DIR,
                role        = ModelRole.NPU,
                isPresent   = false,
                isReadable  = false,
                sizeBytes   = 0L,
                isValid     = false,
                statusLabel = "DIR MISSING"
            )
        }

        if (!manifest.exists()) {
            Timber.tag(TAG).w("[NPU] nexa.manifest missing in: %s", dir.absolutePath)
            return ModelStatus(
                filename    = MODEL_NPU_DIR,
                role        = ModelRole.NPU,
                isPresent   = true,
                isReadable  = true,
                sizeBytes   = 0L,
                isValid     = false,
                statusLabel = "NO MANIFEST"
            )
        }

        val shards = dir.listFiles()?.filter {
            it.name.endsWith(".safetensors") || it.name.endsWith(".bin")
        } ?: emptyList()

        if (shards.isEmpty()) {
            Timber.tag(TAG).w("[NPU] No weight shards found in: %s", dir.absolutePath)
            return ModelStatus(
                filename    = MODEL_NPU_DIR,
                role        = ModelRole.NPU,
                isPresent   = true,
                isReadable  = true,
                sizeBytes   = 0L,
                isValid     = false,
                statusLabel = "NO SHARDS"
            )
        }

        val totalBytes = shards.sumOf { it.length() }
        val shardCount = shards.size

        Timber.tag(TAG).i(
            "[NPU] OK — %s (%d shards, %.2f GB, manifest ✓)",
            MODEL_NPU_DIR, shardCount, totalBytes / (1024.0 * 1024.0 * 1024.0)
        )

        return ModelStatus(
            filename    = MODEL_NPU_DIR,
            role        = ModelRole.NPU,
            isPresent   = true,
            isReadable  = true,
            sizeBytes   = totalBytes,
            isValid     = true,
            statusLabel = "OK (%d shards, %.2f GB)".format(
                shardCount, totalBytes / (1024.0 * 1024.0 * 1024.0)
            )
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Data types
// ────────────────────────────────────────────────────────────────────────────

/**
 * Result of a full vault verification pass.
 *
 * @param npuModel  Status of [ModelVaultManager.MODEL_NPU_FILENAME].
 * @param gpuModel  Status of [ModelVaultManager.MODEL_GPU_FILENAME].
 * @param allPresent True only when both models pass all checks.
 */
data class VaultVerificationResult(
    val npuModel: ModelStatus,
    val gpuModel: ModelStatus,
    val allPresent: Boolean
)

/**
 * Per-file verification status.
 *
 * @param filename    Name of the file within the vault directory.
 * @param role        Whether this file serves the [ModelRole.NPU] or [ModelRole.GPU] path.
 * @param isPresent   File exists on disk.
 * @param isReadable  File can be opened for reading.
 * @param sizeBytes   Current file size in bytes (0 if missing).
 * @param isValid     True when present, readable, and above the minimum size threshold.
 * @param statusLabel Short human-readable status string for logging / UI display.
 */
data class ModelStatus(
    val filename: String,
    val role: ModelRole,
    val isPresent: Boolean,
    val isReadable: Boolean,
    val sizeBytes: Long,
    val isValid: Boolean,
    val statusLabel: String
)

/**
 * Which hardware backend a model targets.
 */
enum class ModelRole {
    /** Hexagon NPU (HTP v79) — sharded safetensors + Nexa manifest. */
    NPU,
    /** Adreno GPU — flat-buffer / TFLite format. */
    GPU
}
