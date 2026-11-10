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
import com.tuki.sveta.data.telegram.TelegramChannelScraper
import com.tuki.sveta.data.telegram.TelegramRepository
import com.tuki.sveta.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val telegram = TelegramRepository(TelegramChannelScraper(http), settings, history)

    val overview = OverviewSource(schedule, ecoflow, telegram, settings, scope)
    val notifications = Notifications(context.applicationContext)

    /** Quick taps on several regions must finish in the order they were made, or the last download wins instead of the last tap. */
    private val regionSwitch = Mutex()

    /**
     * Switches the DTEK network and loads its schedule. Queues are numbered per network (Kyiv has sixty,
     * Dnipro adds its own "ЦЕК" set), so a queue the new network does not have is replaced by its first.
     */
    suspend fun selectRegion(region: DtekRegion) = regionSwitch.withLock {
        settings.update { it.copy(region = region.id) }
        schedule.refresh(region, settings.settings.value.group)
        val groups = schedule.state.value.payloadFor(region)?.let(DtekParser::groups).orEmpty()
        if (groups.isNotEmpty()) {
            settings.update { current ->
                if (current.region == region.id && groups.none { it.id == current.group }) current.copy(group = groups.first().id) else current
            }
        }
    }
}
