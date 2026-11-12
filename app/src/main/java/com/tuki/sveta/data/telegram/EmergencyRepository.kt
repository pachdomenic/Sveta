package com.tuki.sveta.data.telegram

import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.store.HistoryStore
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.domain.Bulletin
import com.tuki.sveta.domain.EmergencyStatus
import com.tuki.sveta.domain.EmergencyTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * Watches DTEK's announcement channel for emergency outages. Neither the DTEK site mirror nor its
 * JSON carries that flag, but every start and cancellation is posted to the channel within minutes.
 */
class EmergencyRepository(
    private val scraper: TelegramChannelScraper,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The service, the hourly worker and the open app may all ask at once; a post must be handled once. */
    private val lock = Mutex()

    private val _source = MutableStateFlow(ChannelStatus())
    /** Health of the announcement channel itself, for the settings screen. */
    val source: StateFlow<ChannelStatus> = _source.asStateFlow()

    /** Emergency outages in effect right now, or null. */
    fun current(now: Instant = clock.instant()): EmergencyStatus? =
        if (!covered()) null else EmergencyTracker.current(history.history.value.emergency, now)

    /**
     * The posts are only read for Kyiv oblast ([com.tuki.sveta.domain.EmergencyMatcher] looks for
     * "Київщина"), so in any other region they would announce someone else's outages.
     */
    private fun covered(): Boolean = settings.settings.value.dtekRegion == DtekRegion.KYIV_REGION

    /**
     * Reads the channel once.
     * @return the new status when emergency outages have just begun or have just been called off
     */
    suspend fun poll(): EmergencyStatus? = lock.withLock {
        if (!covered()) return null
        val channel = settings.settings.value.emergencyChannel.takeIf { it.isNotBlank() } ?: return null
        try {
            val posts = scraper.latest(channel)
            val now = clock.instant()
            val known = history.history.value
            val step = EmergencyTracker.step(known.emergency, known.emergencyPosts, posts.map { Bulletin(it.id, it.at, it.text) }, channel, now)
            if (step.status != known.emergency || step.seen != known.emergencyPosts) {
                history.update { it.copy(emergency = step.status, emergencyPosts = step.seen) }
            }
            _source.value = ChannelStatus(now, null, posts.lastOrNull()?.at)
            step.event
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _source.value = _source.value.copy(checkedAt = clock.instant(), error = e.message ?: e.javaClass.simpleName)
            null
        }
    }
}
