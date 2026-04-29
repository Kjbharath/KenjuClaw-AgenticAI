package com.kenju.claw

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kenju.claw.hardware.HardwareAccelConfig
import com.kenju.claw.ui.theme.KenjuClawTheme
import timber.log.Timber

/**
 * MainActivity — KenjuClaw entry point.
 *
 * Displays runtime hardware status and drives the permission grant flow for:
 *  - SYSTEM_ALERT_WINDOW (overlay)
 *  - MANAGE_EXTERNAL_STORAGE (all-files access for model ingestion)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hwConfig = KenjuClawApplication.hwConfig

        setContent {
            KenjuClawTheme {
                KenjuClawScreen(
                    hwConfig       = hwConfig,
                    hasOverlay     = Settings.canDrawOverlays(this),
                    hasAllFiles    = Environment.isExternalStorageManager(),
                    onGrantOverlay = { requestOverlayPermission() },
                    onGrantFiles   = { requestAllFilesPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("MainActivity resumed — overlay=%s allFiles=%s",
            Settings.canDrawOverlays(this),
            Environment.isExternalStorageManager()
        )
    }

    // ── Permission redirects ─────────────────────────────────────────────────

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAllFilesPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

// ── Compose UI ───────────────────────────────────────────────────────────────

@Composable
private fun KenjuClawScreen(
    hwConfig: HardwareAccelConfig,
    hasOverlay: Boolean,
    hasAllFiles: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantFiles: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text       = "KenjuClaw",
                style      = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text  = "Agentic Overlay System",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider()

            // Hardware Status
            SectionTitle("Hardware Acceleration")
            StatusRow("SoC",       hwConfig.detectedSoc)
            StatusRow("NPU (HTP)", hwConfig.npu.runtimeStatus)
            StatusRow("GPU",       hwConfig.gpu.runtimeStatus)
            StatusRow("Hexagon",   hwConfig.npu.hexagonVersion)
            StatusRow("Adreno",    hwConfig.gpu.adrenoVersion)

            HorizontalDivider()

            // Permissions
            SectionTitle("Permissions")
            PermissionRow(
                label     = "Overlay (SYSTEM_ALERT_WINDOW)",
                granted   = hasOverlay,
                onRequest = onGrantOverlay
            )
            PermissionRow(
                label     = "All Files Access (Model Ingestion)",
                granted   = hasAllFiles,
                onRequest = onGrantFiles
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.labelLarge,
        color      = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment   = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = if (granted) "Granted ✓" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
        }
        if (!granted) {
            TextButton(onClick = onRequest) { Text("Grant") }
        }
    }
}
