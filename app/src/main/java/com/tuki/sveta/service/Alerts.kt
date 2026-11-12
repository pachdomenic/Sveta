package com.tuki.sveta.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tuki.sveta.OverviewSource
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.dtek.ScheduleChange
import com.tuki.sveta.data.dtek.ScheduleRepository
import com.tuki.sveta.data.ecoflow.EcoflowRepository
import com.tuki.sveta.data.store.HistoryStore
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.data.telegram.EmergencyRepository
import com.tuki.sveta.domain.AlertPhase
import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.PlannedAlert
import com.tuki.sveta.domain.PlannedAlerts
import com.tuki.sveta.domain.SwitchKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Checks the sources and turns what changed into notifications. The foreground service, the hourly
 * worker, the alarm receiver and the open app all come through here, so an event is announced once
 * no matter who happened to notice it.
 */
class Alerts(
    private val context: Context,
    private val schedule: ScheduleRepository,
    private val emergency: EmergencyRepository,
    private val ecoflow: EcoflowRepository,
    private val overview: OverviewSource,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val notifications: Notifications,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val plannedLock = Mutex()

    /** What the pending alarm is set to, so the service's frequent ticks do not keep re-setting it. */
    private var armed: Pair<Instant?, Boolean>? = null

    /** Pulls the schedule of [region] and announces a new, changed or cancelled day for [group]. */
    suspend fun refreshSchedule(
        region: DtekRegion = settings.settings.value.dtekRegion,
        group: String = settings.settings.value.group,
    ): ScheduleChange? {
        val change = schedule.refresh(region, group)
        if (change != null && !change.isEmpty && settings.settings.value.let { it.onboarded && it.notifySchedule }) {
            notifications.ensureChannels()
            val text = EventTexts.schedule(change, clock.instant())
            notifications.scheduleChanged(text.title, text.text)
        }
        // The reminders were armed for the old schedule.
        syncPlanned()
        return change
    }

    /** Reads the announcement channel and tells the user when emergency outages begin or end. */
    suspend fun checkEmergency() {
        val event = emergency.poll() ?: return
        if (event.active) notifications.cancelPlanned()
        if (settings.settings.value.let { it.onboarded && it.notifyEmergency }) {
            notifications.ensureChannels()
            val text = EventTexts.emergency(event, overview.current())
            notifications.emergency(text.title, text.text, event.active)
        }
        syncPlanned()
    }

    /**
     * Shows the schedule reminders that are due and arms an alarm for the next one. Cheap and safe to
     * call at any time; calling it late only means a late reminder.
     *
     * @param alarmFired the pending alarm has just gone off, so it has to be set again even if the
     * next moment looks the same as before
     */
    suspend fun syncPlanned(alarmFired: Boolean = false) = plannedLock.withLock {
        if (alarmFired) armed = null
        val current = settings.settings.value
        // Before onboarding the queue is only a default; reminders about it would be about someone else's lights.
        if (!current.notifyPlanned || !current.onboarded) return@withLock arm(null)
        val region = current.dtekRegion
        schedule.loadCache(region)
        val payload = schedule.state.value.payloadFor(region) ?: return@withLock arm(null)

        val now = clock.instant()
        // Yesterday is kept so an outage running over midnight is not mistaken for a new one.
        val from = now.atZone(KyivZone).toLocalDate().minusDays(1)
        val days = DtekParser.factDays(payload, current.group).filter { !it.date.isBefore(from) }
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(days), Duration.ofMinutes(current.plannedLeadMinutes.toLong()))

        var handled = history.history.value.plannedHandled.toSet()
        val due = PlannedAlerts.due(alerts, handled, now)
        if (due.isNotEmpty()) {
            due.lastOrNull(::worthShowing)?.let { alert ->
                notifications.ensureChannels()
                val text = EventTexts.planned(alert, now)
                notifications.planned(text.title, text.text, off = alert.switch.kind == SwitchKind.OFF)
            }
            handled = handled + due.map { it.key }
            history.update { it.copy(plannedHandled = (it.plannedHandled + due.map { alert -> alert.key }).distinct()) }
            // The alarm receiver's process can be gone a moment later; a lost write would repeat the alert.
            history.flush()
        }
        arm(PlannedAlerts.next(alerts, handled, now))
    }

    private fun worthShowing(alert: PlannedAlert): Boolean = when {
        // The schedule is suspended; whatever it says for this hour is not happening.
        emergency.current() != null -> false
        // The station sees the real flip and reports it better than the schedule can.
        alert.phase == AlertPhase.NOW && ecoflow.state.value.grid.state != GridState.UNKNOWN -> false
        else -> true
    }

    private fun arm(at: Instant?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PlannedAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exact = at != null && exactAlarmsAllowed(context)
        if (armed == at to exact) return
        armed = at to exact
        if (at == null) {
            alarms.cancel(intent)
            return
        }
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            } else {
                // Without the exact-alarm permission the system may deliver this late; the running
                // service calls syncPlanned() on its own timer and usually gets there first.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    companion object {
        /** Android 12+ lets the user decide whether an app may wake the phone at an exact minute. */
        fun exactAlarmsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }
}
