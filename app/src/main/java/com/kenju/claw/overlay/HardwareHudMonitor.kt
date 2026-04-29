package com.kenju.claw.overlay

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File

/**
 * HardwareHudMonitor
 *
 * Polls the Snapdragon 8 Elite (SM8750) hardware sensors at a configurable
 * interval and delivers live [HudSnapshot] readings to a callback.
 *
 * ## Data sources
 *
 * | Metric             | Source                                                         |
 * |--------------------|----------------------------------------------------------------|
 * | CPU usage          | `/proc/stat` delta between two polls                           |
 * | RAM usage          | [ActivityManager.getMemoryInfo]                                |
 * | GPU load           | `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` (Adreno sysfs) |
 * | NPU busy           | `/sys/devices/…/npu_busy` (Hexagon sysfs, vendor-specific)    |
 * | Skin temperature   | `/sys/class/thermal/thermal_zone[*]/temp` zone scan            |
 * | CPU freq           | `/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq`     |
 *
 * All sysfs paths may return `null` on non-rooted devices or emulators;
 * the monitor degrades gracefully by reporting "N/A" strings.
 *
 * @param context    Application context for [ActivityManager] queries.
 * @param intervalMs Polling interval in milliseconds. Default: 1 000 ms.
 * @param onUpdate   Callback invoked on the main thread with the latest snapshot.
 */
class HardwareHudMonitor(
    private val context: Context,
    private val intervalMs: Long = 1_000L,
    private val onUpdate: (HudSnapshot) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var prevCpuTotal  = 0L
    private var prevCpuIdle   = 0L
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val snapshot = buildSnapshot()
            onUpdate(snapshot)
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        Timber.tag(TAG).d("HardwareHudMonitor starting (interval=%dms)", intervalMs)
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        Timber.tag(TAG).d("HardwareHudMonitor stopped")
    }

    // ── Snapshot construction ─────────────────────────────────────────────────

    private fun buildSnapshot(): HudSnapshot {
        val cpuPercent  = readCpuUsage()
        val gpuPercent  = readSysfsInt(GPU_BUSY_PATH)
        val npuPercent  = readSysfsInt(NPU_BUSY_PATH)
        val thermalC    = readThermalZone()
        val ramMb       = readRamUsage()
        val cpuFreqMhz  = readSysfsInt(CPU_FREQ_PATH)?.let { it / 1000 }

        return HudSnapshot(
            cpuUsagePercent = cpuPercent,
            gpuUsagePercent = gpuPercent,
            npuBusyPercent  = npuPercent,
            thermalCelsius  = thermalC,
            ramUsedMb       = ramMb,
            cpuFreqMhz      = cpuFreqMhz
        )
    }

    // ── CPU: /proc/stat delta ─────────────────────────────────────────────────

    private fun readCpuUsage(): Int? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val tokens = line.split(" ").filter { it.isNotEmpty() }
            // tokens: cpu user nice system idle iowait irq softirq
            val total = tokens.drop(1).sumOf { it.toLongOrNull() ?: 0L }
            val idle  = tokens.getOrNull(4)?.toLongOrNull() ?: 0L

            val deltaTot  = total - prevCpuTotal
            val deltaIdle = idle  - prevCpuIdle
            prevCpuTotal  = total
            prevCpuIdle   = idle

            if (deltaTot == 0L) return 0
            ((deltaTot - deltaIdle) * 100L / deltaTot).toInt().coerceIn(0, 100)
        } catch (_: Exception) { null }
    }

    // ── RAM ──────────────────────────────────────────────────────────────────

    private fun readRamUsage(): Long? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem - info.availMem) / (1024L * 1024L)
        } catch (_: Exception) { null }
    }

    // ── Generic sysfs int reader ──────────────────────────────────────────────

    private fun readSysfsInt(path: String?): Int? {
        if (path == null) return null
        return try {
            File(path).readText().trim().toIntOrNull()
        } catch (_: Exception) { null }
    }

    // ── Thermal zone scan ─────────────────────────────────────────────────────

    private fun readThermalZone(): Float? {
        // Walk thermal zones, pick max temperature (in milli-Celsius → Celsius)
        return try {
            val thermalDir = File("/sys/class/thermal/")
            if (!thermalDir.exists()) return null
            thermalDir.listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.mapNotNull { zone ->
                    try { File(zone, "temp").readText().trim().toIntOrNull() } catch (_: Exception) { null }
                }
                ?.maxOrNull()
                ?.let { milliC -> milliC / 1000f }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val TAG = "KenjuClaw/HudMonitor"

        // Adreno 830 sysfs — available on SM8750 unrooted devices
        private const val GPU_BUSY_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"

        // Hexagon NPU sysfs — vendor-specific, may not be exposed on all ROMs
        private val NPU_BUSY_PATH: String? = null   // TODO: validate path on S25 Ultra

        // Big core cluster freq
        private const val CPU_FREQ_PATH = "/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq"
    }
}

/**
 * Point-in-time snapshot of hardware utilisation metrics.
 *
 * All fields are nullable — `null` means the metric could not be read on this device.
 *
 * @param cpuUsagePercent  Combined CPU utilisation [0, 100].
 * @param gpuUsagePercent  Adreno GPU busy percentage [0, 100].
 * @param npuBusyPercent   Hexagon NPU busy percentage [0, 100].
 * @param thermalCelsius   Peak thermal zone temperature in °C.
 * @param ramUsedMb        RAM consumed by all processes in MB.
 * @param cpuFreqMhz       Current big-core CPU frequency in MHz.
 */
data class HudSnapshot(
    val cpuUsagePercent:       Int?   = null,
    val gpuUsagePercent:       Int?   = null,
    val npuBusyPercent:        Int?   = null,
    val thermalCelsius:        Float? = null,
    val ramUsedMb:             Long?  = null,
    val cpuFreqMhz:            Int?   = null,
    val inferenceTokensPerSec: Float? = null
) {
    /** Thermal tier — drives HUD colour. */
    val thermalStatus: ThermalStatus get() = when {
        thermalCelsius == null      -> ThermalStatus.UNKNOWN
        thermalCelsius >= TEMP_HOT  -> ThermalStatus.HOT
        thermalCelsius >= TEMP_WARM -> ThermalStatus.WARM
        else                        -> ThermalStatus.COOL
    }

    companion object {
        private const val TEMP_WARM = 38f   // °C
        private const val TEMP_HOT  = 48f   // °C

        val EMPTY = HudSnapshot()
    }
}

enum class ThermalStatus { COOL, WARM, HOT, UNKNOWN }
