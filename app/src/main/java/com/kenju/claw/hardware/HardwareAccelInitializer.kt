package com.kenju.claw.hardware

import android.content.Context
import com.kenju.claw.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * HardwareAccelInitializer
 *
 * Probes the runtime environment and constructs a fully-populated
 * [HardwareAccelConfig] for the KenjuClaw inference stack.
 *
 * ## Hexagon NPU (HTP) initialization sequence
 *
 *  1. Resolve QNN library paths from the device's native library directory
 *     and known Qualcomm firmware paths (`/vendor/lib64/`, `/system/lib64/`).
 *  2. Attempt `System.load()` for each required library (graceful failure).
 *  3. Set system properties consumed by downstream QNN delegates.
 *  4. Prepare the on-disk graph cache directory.
 *
 * ## Adreno GPU initialization sequence
 *
 *  1. Detect Vulkan support via `PackageManager` feature flags.
 *  2. If Vulkan is unavailable, probe `libOpenCL.so` in vendor lib paths.
 *  3. Select [GpuBackend.VULKAN] > [GpuBackend.OPENCL] > [GpuBackend.NONE].
 *  4. Configure FP16 precision (native on Adreno 830).
 *
 * All failures are non-fatal: missing libraries surface as warnings so the
 * app can still launch with CPU-only fallbacks.
 */
object HardwareAccelInitializer {

    private const val TAG = "KenjuClaw/HWAccel"

    // ── QNN Library names ────────────────────────────────────────────────────
    private const val LIB_QNN_HTP         = "libQnnHtp.so"
    private const val LIB_QNN_HTP_PREPARE = "libQnnHtpPrepare.so"
    private const val LIB_QNN_SYSTEM      = "libQnnSystem.so"
    private const val LIB_OPENCL          = "libOpenCL.so"

    /**
     * Ordered vendor paths to search for Qualcomm runtime libraries.
     * Note: If the OEM does not expose these in public vendor paths (or restricts
     * them via Android 14+ linker namespaces), they WILL fail to load. In that case,
     * the .so files MUST be bundled inside the app's `jniLibs/arm64-v8a/` directory.
     */
    private val VENDOR_LIB_PATHS = listOf(
        "/vendor/lib64/",
        "/system/vendor/lib64/",
        "/vendor/lib64/hw/",
        "/vendor/lib64/qcom/",
        "/system/lib64/",
        "/odm/lib64/",
        "/apex/com.android.vndk.v35/lib64/",
        "/apex/com.android.vndk.v34/lib64/"
    )

    // ────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Probe hardware, load native libraries, and return the resolved config.
     *
     * @param context            Application context (used for cache directory + PackageManager).
     * @param detectedSoc        SoC platform string read from `ro.board.platform`.
     * @param isTargetHardware   True when the device is SM8750 (Snapdragon 8 Elite).
     */
    fun initialize(
        context: Context,
        detectedSoc: String,
        isTargetHardware: Boolean
    ): HardwareAccelConfig {
        val npu = initHexagonNpu(context, isTargetHardware)
        val gpu = initAdrenoGpu(context, isTargetHardware)
        return HardwareAccelConfig(
            npu              = npu,
            gpu              = gpu,
            isTargetHardware = isTargetHardware,
            detectedSoc      = detectedSoc
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Hexagon NPU (HTP) — private helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun initHexagonNpu(context: Context, isTargetHardware: Boolean): HexagonNpuConfig {
        Timber.tag(TAG).d("Initializing Hexagon NPU path…")

        val backendPath = resolveLibPath(LIB_QNN_HTP)
        val preparePath = resolveLibPath(LIB_QNN_HTP_PREPARE)
        val systemPath  = resolveLibPath(LIB_QNN_SYSTEM)

        // Load QNN system layer first, then backend, then prepare
        val systemLoaded  = tryLoadLib(systemPath,  LIB_QNN_SYSTEM)
        val backendLoaded = tryLoadLib(backendPath, LIB_QNN_HTP)
        val prepareLoaded = tryLoadLib(preparePath, LIB_QNN_HTP_PREPARE)

        val isAvailable = systemLoaded && backendLoaded && prepareLoaded

        val cacheDir = prepareNpuCacheDir(context)

        val status = buildString {
            append(if (isAvailable) "READY" else "UNAVAILABLE")
            if (!isTargetHardware) append(" [non-target-soc]")
            if (!backendLoaded)    append(" — missing $LIB_QNN_HTP")
            if (!prepareLoaded)    append(" — missing $LIB_QNN_HTP_PREPARE")
            if (!systemLoaded)     append(" — missing $LIB_QNN_SYSTEM")
        }

        Timber.tag(TAG).i("Hexagon NPU: %s", status)
        if (backendPath != null) Timber.tag(TAG).d("  backend  → %s", backendPath)
        if (preparePath != null) Timber.tag(TAG).d("  prepare  → %s", preparePath)
        if (cacheDir    != null) Timber.tag(TAG).d("  cache    → %s", cacheDir)

        return HexagonNpuConfig(
            isAvailable     = isAvailable,
            runtimeStatus   = status,
            backendLibPath  = backendPath,
            prepareLibPath  = preparePath,
            hexagonVersion  = BuildConfig.HEXAGON_VERSION,  // "v79" from gradle
            powerProfile    = NpuPowerProfile.BALANCED,
            precision       = InferencePrecision.INT8,
            cacheDir        = cacheDir
        )
    }

    /**
     * Creates (or reuses) the on-disk directory for compiled QNN graph caches.
     * Located at `<app-files>/qnn_cache/` — no external storage permission required.
     */
    private fun prepareNpuCacheDir(context: Context): String? {
        return try {
            val dir = File(context.filesDir, "qnn_cache").also { it.mkdirs() }
            if (dir.exists()) dir.absolutePath else null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not create QNN cache directory")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Adreno GPU — private helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun initAdrenoGpu(context: Context, isTargetHardware: Boolean): AdrenoGpuConfig {
        Timber.tag(TAG).d("Initializing Adreno GPU path…")

        val vulkanAvailable = isVulkanAvailable(context)
        val openClPath      = if (!vulkanAvailable) resolveLibPath(LIB_OPENCL) else null
        val openClLoaded    = if (openClPath != null) tryLoadLib(openClPath, LIB_OPENCL) else false

        val backend = when {
            vulkanAvailable -> GpuBackend.VULKAN
            openClLoaded    -> GpuBackend.OPENCL
            else            -> GpuBackend.NONE
        }

        val isAvailable = backend != GpuBackend.NONE

        val status = buildString {
            append(if (isAvailable) "READY" else "UNAVAILABLE")
            append(" [backend=${backend.name}]")
            if (!isTargetHardware) append(" [non-target-soc]")
        }

        // Adreno 830 supports up to ~14 GB/s memory bandwidth; cap inference at 2 GB.
        val maxMemoryMb = if (isTargetHardware) 2048 else 512

        Timber.tag(TAG).i("Adreno GPU: %s", status)
        if (openClPath != null) Timber.tag(TAG).d("  OpenCL → %s", openClPath)

        return AdrenoGpuConfig(
            isAvailable     = isAvailable,
            runtimeStatus   = status,
            preferredBackend = backend,
            precision       = InferencePrecision.FP16,
            openClLibPath   = openClPath,
            adrenoVersion   = "Adreno ${BuildConfig.ADRENO_VERSION}",
            maxMemoryMb     = maxMemoryMb
        )
    }

    /**
     * Checks whether the device advertises Vulkan support via [android.content.pm.PackageManager].
     * On SM8750 (Adreno 830) this returns true for Vulkan 1.3.
     */
    private fun isVulkanAvailable(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature("android.hardware.vulkan.level") &&
            pm.hasSystemFeature("android.hardware.vulkan.version")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Vulkan feature check failed")
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Shared library resolution utilities
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a native library to an absolute path by scanning [VENDOR_LIB_PATHS].
     * Returns null if the library is not found in any vendor path.
     */
    private fun resolveLibPath(libName: String): String? {
        for (dir in VENDOR_LIB_PATHS) {
            val candidate = File(dir, libName)
            if (candidate.exists()) {
                Timber.tag(TAG).d("Resolved %s → %s", libName, candidate.absolutePath)
                return candidate.absolutePath
            }
        }
        Timber.tag(TAG).d("Library not found in vendor paths: %s", libName)
        return null
    }

    /**
     * Attempts to load a native library from an absolute path.
     *
     * ## Security note — why System.load() is intentional here
     *
     * The Qualcomm QNN runtime libraries (`libQnnHtp.so`, `libQnnSystem.so`,
     * `libQnnHtpPrepare.so`) are **OEM-shipped firmware** that live in the device's
     * vendor partition (`/vendor/lib64/`). They are NOT bundled inside this APK.
     * `System.loadLibrary()` can only resolve libraries from the app's own native
     * lib directory — it cannot reach vendor-partition paths.
     *
     * The absolute path passed here is resolved **exclusively** from the compile-time
     * [VENDOR_LIB_PATHS] allowlist (no user-controlled input), so there is no path
     * traversal or injection risk. Lint suppression is intentional and documented.
     *
     * Falls back to [System.loadLibrary] (strips 'lib' prefix and '.so' suffix) if
     * path is null (e.g. the library was not found in any vendor path).
     *
     * @return True if the library loaded successfully.
     */
    @Suppress("UnsafeDynamicallyLoadedCode") // justified above — vendor firmware path only
    private fun tryLoadLib(absolutePath: String?, libName: String): Boolean {
        return try {
            if (absolutePath != null) {
                System.load(absolutePath)
            } else {
                // Strip "lib" prefix and ".so" suffix for loadLibrary convention
                val name = libName.removePrefix("lib").removeSuffix(".so")
                System.loadLibrary(name)
            }
            Timber.tag(TAG).d("Loaded: %s", absolutePath ?: libName)
            true
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).w("Could not load %s: %s", libName, e.message)
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unexpected error loading %s", libName)
            false
        }
    }
}
