package com.tuki.sveta.data.telegram

import com.tuki.sveta.data.store.ChannelMatch
import com.tuki.sveta.data.store.HistoryStore
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.domain.PostMatcher
import com.tuki.sveta.domain.Prediction
import com.tuki.sveta.domain.PredictionEngine
import com.tuki.sveta.domain.PredictionEngine.Effect
import com.tuki.sveta.domain.Trigger
import com.tuki.sveta.domain.Watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ChannelStatus(val checkedAt: Instant? = null, val error: String? = null, val lastPostAt: Instant? = null)

/** Polls the watched channels and keeps the neighbour-line predictions up to date. */
class TelegramRepository(
    private val scraper: TelegramChannelScraper,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The service loop and the "check now" button may overlap; posts must be handled once. */
    private val pollLock = Mutex()
    private val engine = PredictionEngine { key -> history.history.value.lags[key].orEmpty() }

    private val _prediction = MutableStateFlow<Prediction?>(null)
    val prediction: StateFlow<Prediction?> = _prediction.asStateFlow()

    private val _announcements = MutableSharedFlow<Prediction>(extraBufferCapacity = 8)
    val announcements: SharedFlow<Prediction> = _announcements.asSharedFlow()

    private val _channels = MutableStateFlow<Map<String, ChannelStatus>>(emptyMap())
    val channels: StateFlow<Map<String, ChannelStatus>> = _channels.asStateFlow()

    val hasWork: Boolean
        get() = settings.settings.value.let { s -> s.channels.isNotEmpty() && s.rules.any { it.enabled } }

    /** One pass over all channels. [gridOn] is the measured grid state, null when unknown. */
    suspend fun poll(gridOn: Boolean?) = pollLock.withLock {
        val s = settings.settings.value
        val watches = s.rules.filter { it.enabled }.map { Watch(it.id, it.name, it.keywords) }
        for (channel in s.channels) {
            try {
                val posts = scraper.latest(channel)
                val now = clock.instant()
                _channels.update { it + (channel to ChannelStatus(now, null, posts.lastOrNull()?.at)) }
                val seen = history.history.value.seenPosts[channel] ?: 0
                val fresh = posts.filter { it.id > seen }
                if (fresh.isEmpty()) continue
                history.update { it.copy(seenPosts = it.seenPosts + (channel to fresh.maxOf { p -> p.id })) }
                for (post in fresh) handle(post, watches, s.offWords, s.onWords, gridOn, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _channels.update { it + (channel to (it[channel] ?: ChannelStatus()).copy(checkedAt = clock.instant(), error = e.message ?: e.javaClass.simpleName)) }
            }
        }
        _channels.update { known -> known.filterKeys { it in s.channels } }
    }

    private fun handle(post: ChannelPost, watches: List<Watch>, offWords: List<String>, onWords: List<String>, gridOn: Boolean?, now: Instant) {
        if (Duration.between(post.at, now) > MATCH_MAX_AGE) return
        val s = settings.settings.value
        engine.config = PredictionEngine.Config(
            Duration.ofMinutes(s.predictMinMinutes.toLong()),
            Duration.ofMinutes(maxOf(s.predictMaxMinutes, s.predictMinMinutes + 1).toLong()),
        )
        for (hit in PostMatcher.match(post.text, watches, offWords, onWords)) {
            history.update {
                it.copy(matches = listOf(ChannelMatch(post.at.toEpochMilli(), post.channel, post.id, post.text.take(400), hit.watch.id, hit.watch.name, hit.kind)) + it.matches)
            }
            val trigger = Trigger(hit.watch.id, hit.watch.name, hit.kind, post.channel, post.text.take(400), post.at)
            synchronized(engine) { apply(engine.onTrigger(trigger, gridOn, now)) }
        }
    }

    fun onGrid(on: Boolean, at: Instant) = synchronized(engine) { apply(engine.onGrid(on, at)) }

    fun onTick() = synchronized(engine) { apply(engine.onTick(clock.instant())) }

    /** Validates a channel before it is saved; returns how many text posts the preview shows. */
    suspend fun probe(channel: String): Result<Int> = try {
        Result.success(scraper.latest(channel).size)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Drops everything remembered about a rule that was deleted or edited. */
    fun forgetRule(ruleId: String) {
        history.update { h ->
            h.copy(lags = h.lags.filterKeys { !it.startsWith("$ruleId:") }, misses = h.misses.filterKeys { !it.startsWith("$ruleId:") })
        }
    }

    private fun apply(effects: List<Effect>) {
        for (effect in effects) when (effect) {
            is Effect.Announce -> _announcements.tryEmit(effect.prediction)
            is Effect.Hit -> history.update { it.copy(lags = it.lags + (effect.key to (it.lags[effect.key].orEmpty() + effect.lagSeconds))) }
            is Effect.Miss -> history.update { it.copy(misses = it.misses + (effect.key to ((it.misses[effect.key] ?: 0) + 1))) }
        }
        _prediction.value = engine.active
    }

    private companion object {
        val MATCH_MAX_AGE: Duration = Duration.ofHours(24)
    }
}
