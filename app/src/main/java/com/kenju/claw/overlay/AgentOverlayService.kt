package com.kenju.claw.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kenju.claw.KenjuClawApplication
import com.kenju.claw.orchestrator.ClawMode
import com.kenju.claw.orchestrator.ClawOrchestratorService
import com.kenju.claw.ui.theme.KenjuClawTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * AgentOverlayService
 *
 * Foreground service that:
 *  1. Inflates a [ComposeView] and attaches it to [WindowManager] as a
 *     `TYPE_APPLICATION_OVERLAY` window — the KenjuClaw HUD.
 *  2. Owns the [OverlayUiState] — a lightweight Compose-observable state holder.
 *  3. Drives [HardwareHudMonitor] to push live hardware snapshots into the HUD.
 *  4. Proxies mode-switch events to [ClawOrchestratorService].
 *
 * ## Window positioning
 *
 * The overlay starts at the top-right corner.  Drag events from [ClawFab]
 * call [repositionWindow] which updates [WindowManager.LayoutParams] directly,
 * allowing pixel-accurate repositioning without recreating the View.
 *
 * ## Compose inside a Service
 *
 * A [ComposeView] requires a [LifecycleOwner], [ViewModelStoreOwner], and
 * [SavedStateRegistryOwner] on its view tree.  This service implements all
 * three (following the standard pattern for Service-hosted Compose) and sets
 * them on the view tree before [ComposeView.setContent] is called.
 */
class AgentOverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ── Lifecycle / SavedState owners ─────────────────────────────────────────

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // ── Window management ─────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView:   ComposeView
    private lateinit var layoutParams:  WindowManager.LayoutParams

    // ── UI state ──────────────────────────────────────────────────────────────

    private val uiState = OverlayUiState()

    // ── HUD monitor ───────────────────────────────────────────────────────────

    private lateinit var hudMonitor: HardwareHudMonitor

    // ── Chat controller ───────────────────────────────────────────────────────

    private lateinit var chatController: ChatController

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(AndroidUiDispatcher.Main + serviceJob)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        Timber.tag(TAG).i("AgentOverlayService created")
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        hudMonitor = HardwareHudMonitor(context = this) { snapshot ->
            uiState.hud = snapshot
        }

        chatController = ChatController(KenjuClawApplication.orchestrator, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        attachOverlayWindow()
        hudMonitor.start()

        Timber.tag(TAG).i("Overlay window attached, HUD monitor started")
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        hudMonitor.stop()
        detachOverlayWindow()
        serviceJob.cancel()
        viewModelStore.clear()

        Timber.tag(TAG).i("AgentOverlayService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Window attachment
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachOverlayWindow() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x       = INITIAL_MARGIN_PX
            y       = INITIAL_MARGIN_PX
        }

        overlayView = ComposeView(this).apply {
            // Wire up the view-tree owners required by Compose in a non-Activity host
            setViewTreeLifecycleOwner(this@AgentOverlayService)
            setViewTreeViewModelStoreOwner(this@AgentOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AgentOverlayService)

            // Create a Recomposer bound to the UI dispatcher
            val recomposer = Recomposer(AndroidUiDispatcher.Main)
            compositionContext = recomposer
            serviceScope.launch { recomposer.runRecomposeAndApplyChanges() }

            setContent {
                KenjuClawTheme {
                    ClawOverlayContent(
                        state          = uiState,
                        chatController = chatController,
                        onTap          = {
                            uiState.panelExpanded = !uiState.panelExpanded
                            updateWindowFocus(uiState.panelExpanded)
                        },
                        onDrag         = { dx, dy -> repositionWindow(dx, dy) },
                        onModeSelect   = { mode -> switchMode(mode) }
                    )
                }
            }
        }

        windowManager.addView(overlayView, layoutParams)
    }

    private fun detachOverlayWindow() {
        runCatching { windowManager.removeView(overlayView) }
            .onFailure { Timber.tag(TAG).w(it, "Error removing overlay view") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag repositioning
    // ─────────────────────────────────────────────────────────────────────────

    private fun repositionWindow(dx: Float, dy: Float) {
        uiState.fabOffsetX += dx
        uiState.fabOffsetY += dy

        layoutParams.x -= dx.toInt()   // Gravity.END means x is right margin — negate
        layoutParams.y += dy.toInt()

        // Clamp to screen bounds
        val dm = resources.displayMetrics
        layoutParams.x = layoutParams.x.coerceIn(0, dm.widthPixels)
        layoutParams.y = layoutParams.y.coerceIn(0, dm.heightPixels - 200)

        runCatching { windowManager.updateViewLayout(overlayView, layoutParams) }
            .onFailure { Timber.tag(TAG).w(it, "Could not reposition overlay window") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode switching
    // ─────────────────────────────────────────────────────────────────────────

    private fun switchMode(mode: ClawMode) {
        uiState.activeMode = mode
        KenjuClawApplication.orchestrator.setMode(mode)
        ClawOrchestratorService.setMode(this, mode)
        Timber.tag(TAG).i("Mode switched to %s via overlay toggle", mode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KenjuClaw Agent Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent channel for the KenjuClaw agentic overlay service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KenjuClaw Agent")
            .setContentText("Overlay active · ${uiState.activeMode.name}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Focus toggle (keyboard support)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggles [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] so the software
     * keyboard can appear when the chat panel is expanded.
     *
     * When [expanded] is true, FLAG_NOT_FOCUSABLE is removed (overlay steals focus).
     * When false, it is re-added (background apps receive touches normally).
     */
    private fun updateWindowFocus(expanded: Boolean) {
        if (!::layoutParams.isInitialized) return
        if (expanded) {
            layoutParams.flags = layoutParams.flags and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            layoutParams.flags = layoutParams.flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(overlayView, layoutParams) }
            .onFailure { Timber.tag(TAG).w(it, "Could not update window focus") }
    }

    companion object {
        private const val TAG             = "KenjuClaw/Overlay"
        private const val CHANNEL_ID      = "kenju_claw_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val INITIAL_MARGIN_PX = 48
    }
}
