package com.tuki.sveta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuki.sveta.container

/** Brings monitoring back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.container.settings.settings.value.monitoring) {
            MonitorService.startIfAllowed(context)
        }
        ScheduleRefreshWorker.schedule(context)
    }
}
