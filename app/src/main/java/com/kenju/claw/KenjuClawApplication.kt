package com.kenju.claw

import android.app.Application
import android.os.Build
import com.kenju.claw.bootstrap.ClawBootstrapper
import com.kenju.claw.hardware.HardwareAccelConfig
import com.kenju.claw.hardware.HardwareAccelInitializer
import com.kenju.claw.orchestrator.ClawOrchestrator
import com.kenju.claw.vault.ModelVaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * KenjuClawApplication
 *
 * Top-level Application class for the KenjuClaw agentic overlay system.
 *
 * Responsibilities:
 *  1. Bootstrap structured logging (Timber).
 *  2. Detect the Snapdragon 8 Elite SoC (SM8750) at runtime.
 *  3. Initialize the Hexagon NPU (HTP v79) hardware acceleration path.
 *  4. Initialize the Adreno 830 GPU hardware acceleration path.
 *  5. Surface a globally-accessible [HardwareAccelConfig] singleton
 *     for use by inference engines, overlay services, and model loaders.
 */
class KenjuClawApplication : Application() {

    companion object {
        private const val TAG = "KenjuClaw/App"

        /** Expected SoC platform strings for the Snapdragon 8 Elite (SM8750 / 'sun'). */
        private val EXPECTED_SOCS = listOf("sm8750", "sun")

        /** Globally accessible hardware configuration (non-null after [onCreate]). */
        lateinit var hwConfig: HardwareAccelConfig
            private set

        /** Globally accessible model vault manager (non-null after [onCreate]). */
        lateinit var modelVault: ModelVaultManager
            private set

        /**
         * Globally accessible [ClawOrchestrator] singleton (non-null after [onCreate]).
         * Call [ClawOrchestrator.initialize] before routing inference requests.
         */
        lateinit var orchestrator: ClawOrchestrator
            private set

        /** Globally accessible [ClawBootstrapper] (non-null after [onCreate]). */
        lateinit var bootstrapper: ClawBootstrapper
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // ── 1. Logging ──────────────────────────────────────────────────────
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseLoggingTree())
        }

        Timber.tag(TAG).i("KenjuClaw initializing — %s / API %d", Build.MODEL, Build.VERSION.SDK_INT)

        // ── 2. SoC detection ────────────────────────────────────────────────
        val detectedSoc = detectSoc()
        val isTargetHardware = EXPECTED_SOCS.any { detectedSoc.contains(it, ignoreCase = true) }

        if (isTargetHardware) {
            Timber.tag(TAG).i("Target hardware confirmed: %s (Snapdragon 8 Elite)", detectedSoc)
        } else {
            Timber.tag(TAG).w(
                "Non-target SoC detected: '%s'. Expected one of %s. " +
                "Hardware-accelerated paths will attempt best-effort fallbacks.",
                detectedSoc, EXPECTED_SOCS
            )
        }

        // ── 3 & 4. Initialize NPU + GPU acceleration paths ──────────────────
        hwConfig = HardwareAccelInitializer.initialize(
            context          = this,
            detectedSoc      = detectedSoc,
            isTargetHardware = isTargetHardware
        )

        Timber.tag(TAG).i(
            "Hardware acceleration initialized — NPU: %s | GPU: %s",
            hwConfig.npu.runtimeStatus,
            hwConfig.gpu.runtimeStatus
        )

        // ── 5. Model vault ──────────────────────────────────────────────────
        modelVault = ModelVaultManager(this).also { vault ->
            val vaultReady = vault.ensureVaultReady()
            if (vaultReady) {
                Timber.tag(TAG).i("Model vault directory ready: %s", vault.vaultDir.absolutePath)
            } else {
                Timber.tag(TAG).e("Model vault directory could not be prepared!")
            }
        }

        // ── 6. Bootstrapper — move shards + sync config + validate manifest ─
        bootstrapper = ClawBootstrapper(this)
        appScope.launch(Dispatchers.IO) {
            val result = bootstrapper.bootstrap()
            Timber.tag(TAG).i(
                "Bootstrap complete — ready=%b | shards=%d | config=%b | manifest=%b",
                result.isReady, result.shardsPresentNow,
                result.configSynced, result.manifestReady
            )

            // Re-run vault verification now that shards are in place
            val verification = modelVault.verifyModels()
            Timber.tag(TAG).i(
                "Post-bootstrap verification — NPU: %s | GPU: %s",
                verification.npuModel.statusLabel,
                verification.gpuModel.statusLabel
            )
        }

        // ── 7. Orchestrator singleton ────────────────────────────────────────
        orchestrator = ClawOrchestrator.getInstance(this)
        Timber.tag(TAG).i(
            "ClawOrchestrator ready — engines will initialize when ClawOrchestratorService starts."
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Reads the `ro.board.platform` system property to identify the SoC.
     * Falls back to [Build.HARDWARE] if the property is unavailable.
     */
    private fun detectSoc(): String {
        return try {
            val getProp = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
            (getProp.invoke(null, "ro.board.platform", "") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: Build.HARDWARE
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read ro.board.platform — falling back to Build.HARDWARE")
            Build.HARDWARE
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Release logging tree (non-debug builds)
    // ────────────────────────────────────────────────────────────────────────

    /** Minimal release tree that suppresses verbose/debug logs. */
    private class ReleaseLoggingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.INFO) return
            android.util.Log.println(priority, tag ?: "KenjuClaw", message)
            t?.let { android.util.Log.e(tag ?: "KenjuClaw", message, it) }
        }
    }
}
