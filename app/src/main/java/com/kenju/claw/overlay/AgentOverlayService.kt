package com.kenju.claw.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import timber.log.Timber

/**
 * AgentOverlayService
 *
 * Foreground service that hosts the KenjuClaw agentic overlay window.
 * Declared with `foregroundServiceType="specialUse"` in the manifest to
 * comply with Android 14+ (API 34) foreground service requirements.
 *
 * This stub is ready for extension — attach your WindowManager-based
 * overlay view in [onStartCommand] after the service enters the foreground.
 */
class AgentOverlayService : Service() {

    companion object {
        private const val TAG            = "KenjuClaw/Overlay"
        private const val CHANNEL_ID     = "kenju_claw_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("AgentOverlayService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).i("AgentOverlayService starting foreground")
        startForeground(NOTIFICATION_ID, buildNotification())
        // TODO: attach WindowManager overlay view here
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("AgentOverlayService destroyed")
        // TODO: remove overlay view from WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KenjuClaw Agent Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent channel for the KenjuClaw agentic overlay service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KenjuClaw Agent")
            .setContentText("Agentic overlay is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
