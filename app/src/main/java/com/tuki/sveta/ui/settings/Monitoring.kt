package com.tuki.sveta.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.tuki.sveta.AppContainer
import com.tuki.sveta.service.MonitorService
import kotlinx.coroutines.launch

/**
 * Returns a callback that switches background monitoring on or off. Turning it on asks for the
 * notification permission first; monitoring still starts if it is denied, just without alerts.
 */
@Composable
fun rememberMonitoringToggle(app: AppContainer): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun apply(enabled: Boolean) {
        scope.launch {
            app.settings.update { it.copy(monitoring = enabled) }
            if (enabled) MonitorService.startIfAllowed(context) else MonitorService.stop(context)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { apply(true) }

    return { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= 33 && !app.notifications.canPost) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            apply(enabled)
        }
    }
}

fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Sideloaded personal tool that must hold a socket open all day: the direct exemption dialog is appropriate. */
@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizations() {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    runCatching { startActivity(direct) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

fun Context.openPromotedNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= 36) {
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    runCatching { startActivity(intent) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } }
}
