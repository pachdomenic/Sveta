package com.tuki.sveta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuki.sveta.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Brings monitoring and the schedule reminders back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.container
        if (app.settings.settings.value.monitoring) {
            MonitorService.startIfAllowed(context)
        }
        ScheduleRefreshWorker.schedule(context)
        // A reboot wipes every alarm.
        val pending = goAsync()
        app.scope.launch {
            try {
                withTimeoutOrNull(8_000) { app.alerts.syncPlanned() }
            } finally {
                pending.finish()
            }
        }
    }
}
