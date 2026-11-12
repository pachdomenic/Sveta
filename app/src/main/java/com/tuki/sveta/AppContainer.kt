package com.tuki.sveta

import android.content.Context
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.dtek.MirrorDtekSource
import com.tuki.sveta.data.dtek.ScheduleRepository
import com.tuki.sveta.data.ecoflow.EcoflowAuthApi
import com.tuki.sveta.data.ecoflow.EcoflowRepository
import com.tuki.sveta.data.store.HistoryStore
import com.tuki.sveta.data.store.SecretStore
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.data.telegram.EmergencyRepository
import com.tuki.sveta.data.telegram.TelegramChannelScraper
import com.tuki.sveta.data.telegram.TelegramRepository
import com.tuki.sveta.service.Alerts
import com.tuki.sveta.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired singletons; the graph is too small to justify a DI framework. */
class AppContainer(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(context, scope)
    val secrets = SecretStore(context)
    val history = HistoryStore(File(context.filesDir, "history.json"), scope)

    val schedule = ScheduleRepository(MirrorDtekSource(http), File(context.filesDir, "dtek"))
    val ecoflow = EcoflowRepository(EcoflowAuthApi(http), settings, secrets, history, scope)
    private val scraper = TelegramChannelScraper(http)
    val telegram = TelegramRepository(scraper, settings, history)
    val emergency = EmergencyRepository(scraper, settings, history)

    val overview = OverviewSource(schedule, ecoflow, telegram, emergency, history, settings, scope)
    val notifications = Notifications(context.applicationContext)
    val alerts = Alerts(context.applicationContext, schedule, emergency, ecoflow, overview, settings, history, notifications)

    init {
        // Re-arms the schedule reminders on every process start and whenever what they depend on changes.
        scope.launch {
            settings.settings
                .map { listOf(it.onboarded, it.region, it.group, it.notifyPlanned, it.plannedLeadMinutes) }
                .distinctUntilChanged()
                .collectLatest { alerts.syncPlanned() }
        }
    }

    /** Quick taps on several regions must finish in the order they were made, or the last download wins instead of the last tap. */
    private val regionSwitch = Mutex()

    /**
     * Switches the DTEK network and loads its schedule. Queues are numbered per network (Kyiv has sixty,
     * Dnipro adds its own "ЦЕК" set), so a queue the new network does not have is replaced by its first.
     */
    suspend fun selectRegion(region: DtekRegion) = regionSwitch.withLock {
        settings.update { it.copy(region = region.id) }
        // Through alerts, so the reminders are re-armed for the new region's schedule once it is in.
        alerts.refreshSchedule(region, settings.settings.value.group)
        val groups = schedule.state.value.payloadFor(region)?.let(DtekParser::groups).orEmpty()
        if (groups.isNotEmpty()) {
            settings.update { current ->
                if (current.region == region.id && groups.none { it.id == current.group }) current.copy(group = groups.first().id) else current
            }
        }
    }
}
