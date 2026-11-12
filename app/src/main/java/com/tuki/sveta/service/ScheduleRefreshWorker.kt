package com.tuki.sveta.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tuki.sveta.container
import java.util.concurrent.TimeUnit

/**
 * Hourly safety net: keeps the schedule fresh, looks for emergency outages and reports both even when
 * the foreground service is not running, and tries to revive the service if the system killed it.
 */
class ScheduleRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.container
        val settings = app.settings.settings.value
        if (settings.monitoring && !MonitorService.isRunning.value && MonitorService.startIfAllowed(applicationContext)) {
            return Result.success()
        }
        if (MonitorService.isRunning.value) return Result.success()

        app.alerts.refreshSchedule(settings.dtekRegion, settings.group)
        app.alerts.checkEmergency()
        return if (app.schedule.state.value.error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "schedule-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
