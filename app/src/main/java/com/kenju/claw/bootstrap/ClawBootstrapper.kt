package com.kenju.claw.bootstrap

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * ClawBootstrapper
 *
 * First-run bootstrap that prepares the local inference vault for the
 * OmniNeural 4B model on the Snapdragon 8 Elite's Hexagon NPU.
 *
 * ## What it does
 *
 * 1. **Shard ingestion** — Moves every file from
 *    `/sdcard/Download/OmniNeural-4B/` → `<filesDir>/models/OmniNeural-4B/`.
 *    The source directory typically contains:
 *    ```
 *    OmniNeural-4B/
 *    ├── config.json           ← model architecture config
 *    ├── tokenizer.json        ← tokenizer vocab
 *    ├── tokenizer_config.json
 *    ├── model-00001-of-NNNNN.safetensors   ← weight shard 1
 *    ├── model-00002-of-NNNNN.safetensors   ← weight shard 2
 *    ├── …
 *    ├── model.safetensors.index.json       ← shard index
 *    └── nexa.manifest         ← Nexa SDK model manifest
 *    ```
 *    After copy, the source files are **deleted** to free ~/Download space.
 *
 * 2. **Config sync** — Copies `res/raw/claw_config.json` (the IDE-editable
 *    inference config) into the same vault directory so the Nexa SDK loader
 *    can read it as a plain file alongside the model shards.
 *
 * 3. **Manifest validation** — Reads or creates a `nexa.manifest` inside the
 *    vault directory, ensuring `plugin_id` is set to `"npu"` for Hexagon HTP
 *    acceleration on the SM8750.
 *
 * ## Usage
 *
 * Call from [KenjuClawApplication.onCreate] or from the UI when the user
 * taps "Prepare Model":
 * ```kotlin
 * lifecycleScope.launch {
 *     val result = ClawBootstrapper(context).bootstrap()
 *     if (result.isReady) { /* start NexaNpuEngine */ }
 * }
 * ```
 *
 * ## Thread safety
 * All I/O is dispatched to [Dispatchers.IO]. Safe to call from any coroutine.
 *
 * @param context Application context — used for [Context.getFilesDir] and resources.
 */
class ClawBootstrapper(private val context: Context) {

    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Runs the full bootstrap pipeline:
     *  1. Ensure vault directory structure exists.
     *  2. Ingest model shards from `/sdcard/Download/OmniNeural-4B/`.
     *  3. Sync `claw_config.json` from `res/raw` into the vault.
     *  4. Validate / write `nexa.manifest` with `plugin_id = "npu"`.
     *
     * @return [BootstrapResult] describing what happened and whether the vault is ready.
     */
    suspend fun bootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("ClawBootstrapper starting…")

        // ── 1. Vault directory ──────────────────────────────────────────────
        val vaultDir  = File(context.filesDir, MODELS_DIR)
        val modelDir  = File(vaultDir, MODEL_SUBDIR)
        modelDir.mkdirs()
        Timber.tag(TAG).d("Vault directory: %s (exists=%b)", modelDir.absolutePath, modelDir.exists())

        // ── 2. Ingest shards from Download ──────────────────────────────────
        val ingestResult = ingestShards(modelDir)

        // ── 3. Sync claw_config.json from res/raw ───────────────────────────
        val configSynced = syncConfig(modelDir)

        // ── 3.5. Ingest GPU model from Download ─────────────────────────────
        val gpuIngested = ingestGpuModel(vaultDir)

        // ── 4. Validate / create nexa.manifest ──────────────────────────────
        val manifestReady = ensureNexaManifest(modelDir)

        // ── 5. Final readiness check ────────────────────────────────────────
        val shardCount = modelDir.listFiles()
            ?.count { it.name.endsWith(".safetensors") || it.name.endsWith(".bin") }
            ?: 0
        val hasConfig = File(modelDir, "config.json").exists() ||
                        File(modelDir, CONFIG_FILENAME).exists()
        val hasManifest = File(modelDir, NEXA_MANIFEST).exists()
        val isReady = shardCount > 0 && hasConfig && hasManifest

        val result = BootstrapResult(
            vaultPath         = modelDir.absolutePath,
            shardsIngested    = ingestResult.filesMovedCount,
            shardsPresentNow  = shardCount,
            totalBytesMoved   = ingestResult.totalBytesMoved,
            configSynced      = configSynced,
            manifestReady     = manifestReady,
            isReady           = isReady,
            sourceWasPresent  = ingestResult.sourceExists,
            sourceDeleted     = ingestResult.sourceDeleted
        )

        Timber.tag(TAG).i(
            "Bootstrap complete — ready=%b | shards=%d | bytes=%s | config=%b | manifest=%b",
            result.isReady, result.shardsPresentNow,
            formatBytes(result.totalBytesMoved), result.configSynced, result.manifestReady
        )

        return@withContext result
    }

    /**
     * Returns the absolute path to the model vault directory.
     * This is the path that should be passed to the Nexa SDK loader.
     */
    fun getVaultModelDir(): File =
        File(File(context.filesDir, MODELS_DIR), MODEL_SUBDIR)

    /**
     * Returns the absolute path to the synced `claw_config.json` in the vault.
     */
    fun getClawConfigFile(): File =
        File(getVaultModelDir(), CONFIG_FILENAME)

    /**
     * Returns the absolute path to the `nexa.manifest` in the vault.
     */
    fun getNexaManifestFile(): File =
        File(getVaultModelDir(), NEXA_MANIFEST)

    // ────────────────────────────────────────────────────────────────────────
    // Step 2 — Shard ingestion
    // ────────────────────────────────────────────────────────────────────────

    private data class IngestResult(
        val sourceExists: Boolean,
        val filesMovedCount: Int,
        val totalBytesMoved: Long,
        val sourceDeleted: Boolean
    )

    private fun ingestShards(destDir: File): IngestResult {
        val sourceDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_MODEL_FOLDER
        )

        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            Timber.tag(TAG).w(
                "Source directory not found: %s — skipping shard ingestion " +
                "(model may already be in vault).", sourceDir.absolutePath
            )
            return IngestResult(sourceExists = false, 0, 0L, false)
        }

        val sourceFiles = sourceDir.listFiles() ?: emptyArray()
        if (sourceFiles.isEmpty()) {
            Timber.tag(TAG).w("Source directory is empty: %s", sourceDir.absolutePath)
            return IngestResult(sourceExists = true, 0, 0L, false)
        }

        Timber.tag(TAG).i(
            "Ingesting %d files from %s → %s",
            sourceFiles.size, sourceDir.absolutePath, destDir.absolutePath
        )

        var movedCount = 0
        var totalBytes = 0L

        for (srcFile in sourceFiles) {
            if (!srcFile.isFile) continue  // skip subdirectories

            val destFile = File(destDir, srcFile.name)

            // Skip if already present and same size (resumable / idempotent)
            if (destFile.exists() && destFile.length() == srcFile.length()) {
                Timber.tag(TAG).d("Already present, skipping: %s (%s)",
                    srcFile.name, formatBytes(srcFile.length()))
                continue
            }

            try {
                Timber.tag(TAG).d("Moving: %s (%s)…", srcFile.name, formatBytes(srcFile.length()))
                copyFile(srcFile, destFile)
                totalBytes += destFile.length()
                movedCount++

                // Delete source after successful copy to free space
                if (srcFile.delete()) {
                    Timber.tag(TAG).d("  ✓ Source deleted: %s", srcFile.name)
                } else {
                    Timber.tag(TAG).w("  ⚠ Could not delete source: %s", srcFile.name)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to move: %s", srcFile.name)
            }
        }

        // Try to remove the now-empty source directory
        val sourceDirDeleted = try {
            val remaining = sourceDir.listFiles()
            if (remaining.isNullOrEmpty()) {
                sourceDir.delete()
            } else {
                Timber.tag(TAG).w(
                    "%d files remain in source dir (not deleted): %s",
                    remaining.size, sourceDir.absolutePath
                )
                false
            }
        } catch (_: Exception) { false }

        Timber.tag(TAG).i(
            "Shard ingestion done — moved %d files (%s), source dir deleted: %b",
            movedCount, formatBytes(totalBytes), sourceDirDeleted
        )

        return IngestResult(
            sourceExists    = true,
            filesMovedCount = movedCount,
            totalBytesMoved = totalBytes,
            sourceDeleted   = sourceDirDeleted
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 2.5 — GPU Model ingestion
    // ────────────────────────────────────────────────────────────────────────

    private fun ingestGpuModel(vaultDir: File): Boolean {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val gpuModelFileName = "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
        
        // Check root Download directory
        var srcFile = File(downloadDir, gpuModelFileName)
        
        // If not found in root, check in Gemma-4-E2B subdirectory
        if (!srcFile.exists()) {
            srcFile = File(File(downloadDir, "Gemma-4-E2B"), gpuModelFileName)
        }

        if (!srcFile.exists()) {
            Timber.tag(TAG).w("GPU model not found in Download directory: %s", gpuModelFileName)
            return false
        }

        val destFile = File(vaultDir, gpuModelFileName)

        // Skip if already present and same size
        if (destFile.exists() && destFile.length() == srcFile.length()) {
            Timber.tag(TAG).d("GPU model already present, skipping: %s (%s)",
                srcFile.name, formatBytes(srcFile.length()))
            return true
        }

        return try {
            Timber.tag(TAG).i("Moving GPU model: %s → %s", srcFile.absolutePath, destFile.absolutePath)
            copyFile(srcFile, destFile)
            if (srcFile.delete()) {
                Timber.tag(TAG).d("  ✓ Source deleted: %s", srcFile.name)
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to move GPU model: %s", srcFile.name)
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 3 — Config sync from res/raw
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Copies `res/raw/claw_config.json` into the vault directory.
     * This is always overwritten so IDE edits propagate on next app launch.
     */
    private fun syncConfig(destDir: File): Boolean {
        return try {
            val rawResId = context.resources.getIdentifier(
                "claw_config", "raw", context.packageName
            )
            if (rawResId == 0) {
                Timber.tag(TAG).e("res/raw/claw_config.json not found in resources!")
                return false
            }

            val destFile = File(destDir, CONFIG_FILENAME)
            context.resources.openRawResource(rawResId).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }

            Timber.tag(TAG).i("Config synced: %s (%d bytes)", destFile.name, destFile.length())

            // Verify plugin_id is "npu" in the synced config
            val json = destFile.readText()
            val obj = JSONObject(json)
            val pluginId = obj.optString("plugin_id", "")
            if (pluginId != NPU_PLUGIN_ID) {
                Timber.tag(TAG).w(
                    "claw_config.json has plugin_id='%s' — expected '%s'. Patching…",
                    pluginId, NPU_PLUGIN_ID
                )
                obj.put("plugin_id", NPU_PLUGIN_ID)
                destFile.writeText(obj.toString(2))
                Timber.tag(TAG).i("plugin_id patched to '%s'", NPU_PLUGIN_ID)
            }

            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to sync claw_config.json")
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 4 — Nexa manifest validation
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Ensures a `nexa.manifest` file exists in the vault directory with the
     * correct `plugin_id` set to `"npu"` for Hexagon NPU acceleration.
     *
     * If the file already exists (e.g., copied from the Download source), it is
     * read and patched if needed. If it does not exist, a minimal manifest is
     * generated from the shard list.
     */
    private fun ensureNexaManifest(modelDir: File): Boolean {
        return try {
            val manifestFile = File(modelDir, NEXA_MANIFEST)

            if (manifestFile.exists()) {
                // ── Existing manifest — validate & patch ─────────────────────
                val json = JSONObject(manifestFile.readText())
                var patched = false

                // Ensure plugin_id = "npu"
                if (json.optString("plugin_id", "") != NPU_PLUGIN_ID) {
                    json.put("plugin_id", NPU_PLUGIN_ID)
                    patched = true
                }

                // Ensure runtime = "hexagon"
                if (json.optString("runtime", "") != "hexagon") {
                    json.put("runtime", "hexagon")
                    patched = true
                }

                if (patched) {
                    manifestFile.writeText(json.toString(2))
                    Timber.tag(TAG).i("nexa.manifest patched (plugin_id=npu, runtime=hexagon)")
                } else {
                    Timber.tag(TAG).d("nexa.manifest already valid")
                }
            } else {
                // ── Generate manifest from scratch ───────────────────────────
                val shards = modelDir.listFiles()
                    ?.filter { it.name.endsWith(".safetensors") || it.name.endsWith(".bin") }
                    ?.sortedBy { it.name }
                    ?.map { it.name }
                    ?: emptyList()

                val manifest = JSONObject().apply {
                    put("model_name", "OmniNeural-4B")
                    put("model_type", "omnineural")
                    put("plugin_id", NPU_PLUGIN_ID)
                    put("runtime", "hexagon")
                    put("precision", "int4")
                    put("quantization", "q4_0")
                    put("device", "Snapdragon_8_Elite")
                    put("shard_count", shards.size)
                    put("shards", org.json.JSONArray(shards))
                    put("config_file", CONFIG_FILENAME)

                    // Check for index file (HF safetensors format)
                    val indexFile = modelDir.listFiles()
                        ?.firstOrNull { it.name.contains("index.json") }
                    if (indexFile != null) {
                        put("shard_index", indexFile.name)
                    }
                }

                manifestFile.writeText(manifest.toString(2))
                Timber.tag(TAG).i(
                    "nexa.manifest generated — %d shards, plugin_id=%s",
                    shards.size, NPU_PLUGIN_ID
                )
            }

            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to ensure nexa.manifest")
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Utilities
    // ────────────────────────────────────────────────────────────────────────

    private fun copyFile(src: File, dest: File) {
        FileInputStream(src).use { fis ->
            FileOutputStream(dest).use { fos ->
                fis.copyTo(fos, BUFFER_SIZE)
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }

    // ────────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "KenjuClaw/Bootstrapper"

        /** Top-level vault directory inside filesDir. */
        const val MODELS_DIR = "models"

        /** Sub-directory for the OmniNeural 4B model. */
        const val MODEL_SUBDIR = "OmniNeural-4B"

        /** Expected source folder in the device's Download directory. */
        const val DOWNLOAD_MODEL_FOLDER = "OmniNeural-4B"

        /** Nexa SDK manifest filename. */
        const val NEXA_MANIFEST = "nexa.manifest"

        /** The IDE-editable config file synced from res/raw. */
        const val CONFIG_FILENAME = "claw_config.json"

        /** Plugin ID for Hexagon NPU (HTP) acceleration on SM8750. */
        const val NPU_PLUGIN_ID = "npu"

        /** Copy buffer size — 256 KB chunks for fast large-file moves. */
        private const val BUFFER_SIZE = 256 * 1024
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Result type
// ────────────────────────────────────────────────────────────────────────────

/**
 * Result of the [ClawBootstrapper.bootstrap] pipeline.
 *
 * @param vaultPath         Absolute path to the vault model directory.
 * @param shardsIngested    Number of files moved from Download in this pass.
 * @param shardsPresentNow  Number of weight shard files currently in the vault.
 * @param totalBytesMoved   Total bytes moved from Download in this pass.
 * @param configSynced      True if `claw_config.json` was successfully synced.
 * @param manifestReady     True if `nexa.manifest` exists with `plugin_id = "npu"`.
 * @param isReady           True when the vault has shards + config + manifest.
 * @param sourceWasPresent  True if the Download source dir was found on this pass.
 * @param sourceDeleted     True if the Download source dir was removed after copy.
 */
data class BootstrapResult(
    val vaultPath: String,
    val shardsIngested: Int,
    val shardsPresentNow: Int,
    val totalBytesMoved: Long,
    val configSynced: Boolean,
    val manifestReady: Boolean,
    val isReady: Boolean,
    val sourceWasPresent: Boolean,
    val sourceDeleted: Boolean
)
