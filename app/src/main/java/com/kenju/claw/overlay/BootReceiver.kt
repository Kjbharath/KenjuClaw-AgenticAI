package com.kenju.claw.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * BootReceiver
 *
 * Restarts [AgentOverlayService] after device boot / locked-boot events.
 * Both BOOT_COMPLETED and LOCKED_BOOT_COMPLETED are handled so the agent
 * can resume as early as possible in the boot sequence.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Timber.tag("KenjuClaw/Boot").i("Boot received (%s) — restarting AgentOverlayService", action)

        val serviceIntent = Intent(context, AgentOverlayService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
