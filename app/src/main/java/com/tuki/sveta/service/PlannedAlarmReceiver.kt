package com.tuki.sveta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuki.sveta.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fires at the moment of the next schedule reminder, even when nothing else of the app is running. */
class PlannedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.container
        val pending = goAsync()
        app.scope.launch {
            try {
                // Local work only (cached schedule, a notification, the next alarm); well inside the receiver's budget.
                withTimeoutOrNull(8_000) { app.alerts.syncPlanned(alarmFired = true) }
            } finally {
                pending.finish()
            }
        }
    }
}
