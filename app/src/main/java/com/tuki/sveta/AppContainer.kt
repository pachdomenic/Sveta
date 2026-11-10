package com.tuki.sveta

import android.content.Context
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
    val alerts = Alerts(schedule, emergency, overview, settings, notifications)
}
