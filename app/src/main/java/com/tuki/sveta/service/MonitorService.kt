package com.tuki.sveta.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tuki.sveta.container
import com.tuki.sveta.domain.GridState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Keeps the station connection, the schedule and the channel polling alive in the background and owns
 * the ongoing Live Update notification.
 */
class MonitorService : LifecycleService() {

    private val app by lazy { container }

    override fun onCreate() {
        super.onCreate()
        val notifications = app.notifications
        notifications.ensureChannels()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_LIVE,
            notifications.live(LiveContentBuilder.build(app.overview.current(), app.settings.settings.value.liveWatts)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        running.value = true
        app.ecoflow.acquire()

        lifecycleScope.launch { liveCard() }
        lifecycleScope.launch { powerEvents() }
        lifecycleScope.launch { predictionEvents() }
        lifecycleScope.launch { scheduleLoop() }
        lifecycleScope.launch { telegramLoop() }
        lifecycleScope.launch {
            // The user switched monitoring off in settings.
            app.settings.settings.map { it.monitoring }.distinctUntilChanged().collectLatest { if (!it) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        app.ecoflow.release()
        running.value = false
        super.onDestroy()
    }

    private suspend fun liveCard() {
        combine(app.overview.flow(LIVE_TICK), app.settings.settings.map { it.liveWatts }.distinctUntilChanged()) { o, watts ->
            LiveContentBuilder.build(o, watts)
        }
            .distinctUntilChanged()
            .conflate()
            .collect { content ->
                app.notifications.updateLive(content)
                // The station reports several times a second; the system throttles apps that update that often.
                delay(LIVE_MIN_INTERVAL.toMillis())
            }
    }

    private suspend fun powerEvents() {
        app.ecoflow.transitions.collect { transition ->
            app.telegram.onGrid(transition.on, transition.at)
            app.notifications.cancelPrediction()
            if (app.settings.settings.value.notifyPower) {
                val text = EventTexts.power(transition, app.overview.current(), transition.previousSince)
                app.notifications.powerEvent(text.title, text.text, transition.on)
            }
        }
    }

    private suspend fun predictionEvents() {
        app.telegram.announcements.collect { prediction ->
            if (app.settings.settings.value.notifyPredict) {
                val text = EventTexts.prediction(prediction)
                app.notifications.prediction(text.title, text.text)
            }
        }
    }

    private suspend fun scheduleLoop() {
        app.settings.settings.map { it.group }.distinctUntilChanged().collectLatest { group ->
            while (true) {
                val change = app.schedule.refresh(group)
                if (change != null && !change.isEmpty && app.settings.settings.value.notifySchedule) {
                    val text = EventTexts.schedule(change, Instant.now())
                    app.notifications.scheduleChanged(text.title, text.text)
                }
                delay(SCHEDULE_INTERVAL.toMillis())
            }
        }
    }

    private suspend fun telegramLoop() {
        var lastPoll = Instant.EPOCH
        while (true) {
            app.telegram.onTick()
            val grid = app.ecoflow.state.value.grid.state
            // While dark the only thing a post can tell us is that neighbours are back; no need to hurry.
            val interval = if (grid == GridState.OFF) TELEGRAM_INTERVAL_DARK else TELEGRAM_INTERVAL
            if (app.telegram.hasWork && Duration.between(lastPoll, Instant.now()) >= interval) {
                lastPoll = Instant.now()
                app.telegram.poll(
                    gridOn = when (grid) {
                        GridState.ON -> true
                        GridState.OFF -> false
                        GridState.UNKNOWN -> null
                    }
                )
            }
            delay(5_000)
        }
    }

    companion object {
        private val LIVE_TICK: Duration = Duration.ofSeconds(30)
        private val LIVE_MIN_INTERVAL: Duration = Duration.ofSeconds(2)
        private val SCHEDULE_INTERVAL: Duration = Duration.ofMinutes(10)
        private val TELEGRAM_INTERVAL: Duration = Duration.ofSeconds(30)
        private val TELEGRAM_INTERVAL_DARK: Duration = Duration.ofSeconds(120)

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> get() = running

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        /** Background starts can be refused by the system (Android 12+); the next app open will retry. */
        fun startIfAllowed(context: Context): Boolean = try {
            start(context)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}
