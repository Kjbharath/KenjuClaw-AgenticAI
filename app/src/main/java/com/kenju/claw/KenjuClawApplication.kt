package com.kenju.claw

import android.app.Application
import android.os.Build
import com.kenju.claw.hardware.HardwareAccelConfig
import com.kenju.claw.hardware.HardwareAccelInitializer
import com.kenju.claw.vault.ModelVaultManager
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

        /** Expected SoC platform string for the Snapdragon 8 Elite. */
        private const val EXPECTED_SOC = "sm8750"

        /** Globally accessible hardware configuration (non-null after [onCreate]). */
        lateinit var hwConfig: HardwareAccelConfig
            private set

        /** Globally accessible model vault manager (non-null after [onCreate]). */
        lateinit var modelVault: ModelVaultManager
            private set
    }

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
        val isTargetHardware = detectedSoc.contains(EXPECTED_SOC, ignoreCase = true)

        if (isTargetHardware) {
            Timber.tag(TAG).i("Target hardware confirmed: %s (Snapdragon 8 Elite)", detectedSoc)
        } else {
            Timber.tag(TAG).w(
                "Non-target SoC detected: '%s'. Expected '%s'. " +
                "Hardware-accelerated paths will attempt best-effort fallbacks.",
                detectedSoc, EXPECTED_SOC
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
                val verification = vault.verifyModels()
                Timber.tag(TAG).i(
                    "Model vault ready — NPU model: %s | GPU model: %s",
                    verification.npuModel.statusLabel,
                    verification.gpuModel.statusLabel
                )
                if (!verification.allPresent) {
                    Timber.tag(TAG).w(
                        "One or more models are missing from vault at: %s",
                        vault.vaultDir.absolutePath
                    )
                }
            } else {
                Timber.tag(TAG).e("Model vault directory could not be prepared!")
            }
        }
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
