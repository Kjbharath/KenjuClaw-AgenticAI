package com.kenju.claw.orchestrator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ClawOrchestratorService
 *
 * A started foreground service that owns the [ClawOrchestrator] lifecycle. Running
 * the orchestrator inside a [Service] ensures it survives Activity destruction and
 * keeps the inference engines warm while the KenjuClaw overlay is active.
 *
 * ## Responsibilities
 *  1. Start the [ClawOrchestrator] with the desired initial [ClawMode].
 *  2. Expose a simple command channel via `startService(intent)` actions for mode switching.
 *  3. Shut down both engines cleanly in [onDestroy].
 *
 * ## Starting the service
 * ```kotlin
 * // Start in EFFICIENT_NPU mode (default)
 * ClawOrchestratorService.start(context)
 *
 * // Start in POWER_GPU mode
 * ClawOrchestratorService.start(context, ClawMode.POWER_GPU)
 *
 * // Switch mode at runtime (service must already be running)
 * ClawOrchestratorService.setMode(context, ClawMode.HYBRID)
 *
 * // Stop the service and shut down engines
 * ClawOrchestratorService.stop(context)
 * ```
 *
 * ## Manifest registration
 * Declared in `AndroidManifest.xml` with `foregroundServiceType="specialUse"`.
 */
class ClawOrchestratorService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var orchestrator: ClawOrchestrator

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("ClawOrchestratorService created.")
        createNotificationChannel()
        orchestrator = ClawOrchestrator.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API 34+ requirement: must call startForeground() within ~10 s of
        // startForegroundService(), or the system kills this process.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        val action = intent?.action ?: ACTION_START

        Timber.tag(TAG).d("onStartCommand: action=%s", action)

        when (action) {
            ACTION_START -> {
                val modeName = intent?.getStringExtra(EXTRA_MODE) ?: ClawMode.EFFICIENT_NPU.name
                val mode = runCatching { ClawMode.valueOf(modeName) }.getOrDefault(ClawMode.EFFICIENT_NPU)
                startOrchestrator(mode)
            }
            ACTION_SET_MODE -> {
                val modeName = intent?.getStringExtra(EXTRA_MODE) ?: return START_STICKY
                val mode = runCatching { ClawMode.valueOf(modeName) }.getOrElse {
                    Timber.tag(TAG).w("Unknown ClawMode value: %s", modeName)
                    return START_STICKY
                }
                orchestrator.setMode(mode)
                Timber.tag(TAG).i("Mode set via intent: %s", mode)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null   // started service, not bound

    override fun onDestroy() {
        Timber.tag(TAG).i("ClawOrchestratorService destroying — shutting down orchestrator…")
        serviceScope.launch {
            orchestrator.shutdown()
        }.invokeOnCompletion {
            serviceScope.cancel()
            Timber.tag(TAG).i("ClawOrchestratorService destroyed.")
        }
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun startOrchestrator(mode: ClawMode) {
        serviceScope.launch {
            Timber.tag(TAG).i("Initializing ClawOrchestrator with mode=%s…", mode)
            orchestrator.initialize(initialMode = mode)
            Timber.tag(TAG).i(
                "ClawOrchestrator ready — state=%s",
                orchestrator.orchestratorStateFlow.value
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Notification — required for startForeground() on API 34+
    // ────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KenjuClaw Inference Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent channel for the KenjuClaw dual-engine inference orchestrator"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KenjuClaw Engine")
            .setContentText("Inference orchestrator active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Companion — static factory methods and intent constants
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "KenjuClaw/OrchestratorSvc"
        private const val CHANNEL_ID      = "kenju_claw_orchestrator"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START    = "com.kenju.claw.orchestrator.ACTION_START"
        const val ACTION_SET_MODE = "com.kenju.claw.orchestrator.ACTION_SET_MODE"
        const val ACTION_STOP     = "com.kenju.claw.orchestrator.ACTION_STOP"
        const val EXTRA_MODE      = "com.kenju.claw.orchestrator.EXTRA_MODE"

        /**
         * Starts the [ClawOrchestratorService] with the specified [ClawMode].
         * Idempotent — if the service is already running, this updates the mode.
         *
         * @param context Application or activity context.
         * @param mode    Initial [ClawMode]. Defaults to [ClawMode.EFFICIENT_NPU].
         */
        fun start(context: Context, mode: ClawMode = ClawMode.EFFICIENT_NPU) {
            val intent = Intent(context, ClawOrchestratorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
            }
            context.startForegroundService(intent)
        }

        /**
         * Sends a mode-switch command to an already-running [ClawOrchestratorService].
         *
         * @param context Application or activity context.
         * @param mode    The [ClawMode] to switch to.
         */
        fun setMode(context: Context, mode: ClawMode) {
            val intent = Intent(context, ClawOrchestratorService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, mode.name)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stops the [ClawOrchestratorService] and shuts down both inference engines.
         *
         * @param context Application or activity context.
         */
        fun stop(context: Context) {
            val intent = Intent(context, ClawOrchestratorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
