package com.kenju.claw.hardware

/**
 * Unified hardware acceleration configuration for the KenjuClaw inference stack.
 *
 * This data class is the single source of truth produced by [HardwareAccelInitializer]
 * and stored on [com.kenju.claw.KenjuClawApplication]. Both the agentic overlay service
 * and the model-ingestion pipeline read from this object at runtime.
 *
 * @param npu  Hexagon NPU (HTP) configuration and status.
 * @param gpu  Adreno GPU configuration and status.
 * @param isTargetHardware  True when the device is confirmed SM8750 (Snapdragon 8 Elite).
 * @param detectedSoc       Raw SoC platform string from `ro.board.platform`.
 */
data class HardwareAccelConfig(
    val npu: HexagonNpuConfig,
    val gpu: AdrenoGpuConfig,
    val isTargetHardware: Boolean,
    val detectedSoc: String
)

// ────────────────────────────────────────────────────────────────────────────
// Hexagon NPU (HTP — Hexagon Tensor Processor)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configuration for the Qualcomm Hexagon NPU backend.
 *
 * The Snapdragon 8 Elite (SM8750) features a Hexagon v79 DSP with an integrated
 * HTP (Hexagon Tensor Processor) capable of ~50 TOPS for AI workloads.
 *
 * Runtime libraries required on-device (shipped with QDSP firmware):
 *  - `libQnnHtp.so`        — Qualcomm Neural Network HTP backend
 *  - `libQnnHtpPrepare.so` — Graph compilation / cache preparation
 *  - `libQnnSystem.so`     — QNN core system layer
 *
 * @param isAvailable       True when all required QNN libraries are loadable.
 * @param runtimeStatus     Human-readable initialization status.
 * @param backendLibPath    Absolute path to the QNN HTP backend .so resolved at init.
 * @param prepareLibPath    Absolute path to the QNN HTP prepare .so resolved at init.
 * @param hexagonVersion    DSP architecture version (e.g. "v79" for SM8750).
 * @param powerProfile      Active NPU power profile (see [NpuPowerProfile]).
 * @param precision         Default inference precision (see [InferencePrecision]).
 * @param cacheDir          Directory used for compiled graph cache (.bin artifacts).
 */
data class HexagonNpuConfig(
    val isAvailable: Boolean,
    val runtimeStatus: String,
    val backendLibPath: String?,
    val prepareLibPath: String?,
    val hexagonVersion: String,
    val powerProfile: NpuPowerProfile,
    val precision: InferencePrecision,
    val cacheDir: String?
)

/**
 * NPU power/performance tradeoff profile.
 *
 * Maps directly to Qualcomm's SNPE / QNN power levels:
 *  - [HIGH_PERFORMANCE]  — Maximum NPU clock, minimal throttling. Best for real-time overlay.
 *  - [BALANCED]          — Default OS-managed DVFS. Recommended for sustained inference.
 *  - [POWER_SAVER]       — Reduced clocks to extend battery life.
 *  - [BURST]             — Short burst mode; auto-reverts to [BALANCED] after 500 ms.
 */
enum class NpuPowerProfile {
    HIGH_PERFORMANCE,
    BALANCED,
    POWER_SAVER,
    BURST
}

// ────────────────────────────────────────────────────────────────────────────
// Adreno GPU
// ────────────────────────────────────────────────────────────────────────────

/**
 * Configuration for the Qualcomm Adreno GPU backend.
 *
 * The SM8750 Adreno 830 GPU supports:
 *  - Vulkan 1.3 (compute shaders for ML inference)
 *  - OpenCL 3.0 (via Qualcomm's libOpenCL.so)
 *  - FP16 and INT8 precision
 *
 * @param isAvailable       True when at least one GPU backend (Vulkan or OpenCL) initialized.
 * @param runtimeStatus     Human-readable initialization status.
 * @param preferredBackend  Active GPU compute backend (see [GpuBackend]).
 * @param precision         Inference precision for GPU kernels (see [InferencePrecision]).
 * @param openClLibPath     Resolved path for `libOpenCL.so` (null if unavailable).
 * @param adrenoVersion     Adreno GPU version string (e.g. "Adreno 830").
 * @param maxMemoryMb       Maximum GPU memory budget for inference buffers (in MB).
 */
data class AdrenoGpuConfig(
    val isAvailable: Boolean,
    val runtimeStatus: String,
    val preferredBackend: GpuBackend,
    val precision: InferencePrecision,
    val openClLibPath: String?,
    val adrenoVersion: String,
    val maxMemoryMb: Int
)

/**
 * GPU compute backend selection.
 *
 *  - [VULKAN]   — Preferred on API 35. Lower latency, better async compute support.
 *  - [OPENCL]   — Fallback. Wider model compatibility via SNPE/QNN OpenCL delegate.
 *  - [NONE]     — GPU acceleration unavailable; will fall back to CPU.
 */
enum class GpuBackend {
    VULKAN,
    OPENCL,
    NONE
}

/**
 * Inference numerical precision.
 *
 *  - [FP32]   — Full precision. Rarely used on mobile due to memory/compute cost.
 *  - [FP16]   — Half precision. Default for Adreno GPU; native HW support on Adreno 830.
 *  - [INT8]   — 8-bit quantized. Used for Hexagon HTP path; best throughput/efficiency.
 *  - [INT4]   — 4-bit quantized. Experimental; requires QNN 2.28+.
 */
enum class InferencePrecision {
    FP32,
    FP16,
    INT8,
    INT4
}
