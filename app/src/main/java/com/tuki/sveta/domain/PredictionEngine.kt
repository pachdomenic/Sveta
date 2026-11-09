package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant

/** How long after a neighbour's post our own line follows. */
data class LagWindow(val min: Duration, val max: Duration, val median: Duration?, val samples: Int) {
    val learned: Boolean get() = median != null

    companion object {
        const val MIN_SAMPLES = 3

        /** Uses the observed spread once there are enough samples, the configured default before that. */
        fun of(lagsSeconds: List<Int>, defaultMin: Duration, defaultMax: Duration): LagWindow {
            if (lagsSeconds.size < MIN_SAMPLES) return LagWindow(defaultMin, defaultMax, null, lagsSeconds.size)
            val sorted = lagsSeconds.sorted()
            val median = sorted[sorted.size / 2]
            // Keep the window at least a couple of minutes wide so a tight cluster does not expire instantly.
            val min = Duration.ofSeconds(sorted.first().toLong())
            val max = Duration.ofSeconds(maxOf(sorted.last().toLong(), sorted.first() + 120L))
            return LagWindow(min, max, Duration.ofSeconds(median.toLong()), sorted.size)
        }
    }
}

data class Trigger(
    val watchId: String,
    val watchName: String,
    val kind: SwitchKind,
    val channel: String,
    val text: String,
    /** When the post was published; lags are measured from here. */
    val at: Instant,
) {
    val key: String get() = lagKey(watchId, kind)
}

data class Prediction(
    val trigger: Trigger,
    val earliest: Instant,
    val latest: Instant,
    val expiresAt: Instant,
    val window: LagWindow,
) {
    val kind: SwitchKind get() = trigger.kind
}

fun lagKey(watchId: String, kind: SwitchKind): String = "$watchId:${kind.name}"

/**
 * Tracks neighbour-line triggers until the grid follows (a hit, which teaches the lag) or the window
 * runs out (a miss). Pure state machine: feed it events, apply the returned effects.
 */
class PredictionEngine(private val lagsFor: (key: String) -> List<Int>) {

    data class Config(
        val defaultMin: Duration = Duration.ofMinutes(5),
        val defaultMax: Duration = Duration.ofMinutes(20),
    )

    sealed interface Effect {
        data class Announce(val prediction: Prediction) : Effect
        data class Hit(val key: String, val lagSeconds: Int) : Effect
        data class Miss(val key: String) : Effect
    }

    var config = Config()

    private val pending = ArrayList<Prediction>()

    /** The prediction to show: the one expected to come true soonest. */
    val active: Prediction? get() = pending.minByOrNull { it.earliest }

    /** @param gridOn measured grid state, null when unknown */
    fun onTrigger(trigger: Trigger, gridOn: Boolean?, now: Instant): List<Effect> {
        // An outage post only predicts something while we still have power, and vice versa.
        val relevant = when (trigger.kind) {
            SwitchKind.OFF -> gridOn != false
            SwitchKind.ON -> gridOn == false
        }
        if (!relevant || pending.any { it.trigger.key == trigger.key }) return emptyList()

        val window = LagWindow.of(lagsFor(trigger.key), config.defaultMin, config.defaultMax)
        val prediction = Prediction(
            trigger = trigger,
            earliest = trigger.at.plus(window.min),
            latest = trigger.at.plus(window.max),
            expiresAt = trigger.at.plus(window.max.multipliedBy(2).coerceAtLeast(MIN_LIFETIME)),
            window = window,
        )
        // Posts discovered too late (app was not running) are of no use.
        if (!now.isBefore(prediction.expiresAt)) return emptyList()

        val before = active
        pending += prediction
        return if (active != before) listOf(Effect.Announce(prediction)) else emptyList()
    }

    /** The grid really flipped at [at]. */
    fun onGrid(on: Boolean, at: Instant): List<Effect> {
        val fulfilled = if (on) SwitchKind.ON else SwitchKind.OFF
        val effects = pending.filter { it.kind == fulfilled }.mapNotNull { p ->
            val lag = Duration.between(p.trigger.at, at).seconds
            if (lag in 0..MAX_LAG.seconds) Effect.Hit(p.trigger.key, lag.toInt()) else null
        }
        // Whatever was pending for the other direction is moot now.
        pending.clear()
        return effects
    }

    fun onTick(now: Instant): List<Effect> {
        val expired = pending.filter { !now.isBefore(it.expiresAt) }
        if (expired.isEmpty()) return emptyList()
        pending.removeAll(expired.toSet())
        return expired.map { Effect.Miss(it.trigger.key) }
    }

    fun clear() = pending.clear()

    private fun Duration.coerceAtLeast(other: Duration): Duration = if (this < other) other else this

    companion object {
        val MIN_LIFETIME: Duration = Duration.ofMinutes(10)
        val MAX_LAG: Duration = Duration.ofMinutes(90)
    }
}
