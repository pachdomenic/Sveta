package com.tuki.sveta.service

import com.tuki.sveta.OverviewSource
import com.tuki.sveta.data.dtek.ScheduleChange
import com.tuki.sveta.data.dtek.ScheduleRepository
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.data.telegram.EmergencyRepository
import java.time.Clock

/**
 * Checks the sources and turns what changed into notifications. The foreground service, the hourly
 * worker and the open app all come through here, so an event is announced once no matter who
 * happened to notice it.
 */
class Alerts(
    private val schedule: ScheduleRepository,
    private val emergency: EmergencyRepository,
    private val overview: OverviewSource,
    private val settings: SettingsStore,
    private val notifications: Notifications,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Pulls the schedule and announces a new, changed or cancelled day for [group]. */
    suspend fun refreshSchedule(group: String = settings.settings.value.group): ScheduleChange? {
        val change = schedule.refresh(group)
        if (change != null && !change.isEmpty && settings.settings.value.let { it.onboarded && it.notifySchedule }) {
            notifications.ensureChannels()
            val text = EventTexts.schedule(change, clock.instant())
            notifications.scheduleChanged(text.title, text.text)
        }
        return change
    }

    /** Reads the announcement channel and tells the user when emergency outages begin or end. */
    suspend fun checkEmergency() {
        val event = emergency.poll() ?: return
        if (settings.settings.value.let { it.onboarded && it.notifyEmergency }) {
            notifications.ensureChannels()
            val text = EventTexts.emergency(event, overview.current())
            notifications.emergency(text.title, text.text, event.active)
        }
    }
}
