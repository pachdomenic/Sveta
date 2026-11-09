package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant

enum class GridState { UNKNOWN, ON, OFF }

/**
 * Turns raw AC input voltage samples into a debounced grid state.
 *
 * A flip has to hold for [Config.debounce] before it is reported, so a brown-out blip or a single odd
 * sample does not fire a notification. When samples stop arriving the state degrades to UNKNOWN, or to
 * an assumed OFF when the user told us the station loses its uplink together with the grid.
 */
class PowerMonitor(var config: Config = Config()) {

    data class Config(
        val thresholdVolts: Double = 150.0,
        val debounce: Duration = Duration.ofSeconds(5),
        val silence: Duration = Duration.ofSeconds(150),
        val offlineMeansOutage: Boolean = false,
    )

    var state: GridState = GridState.UNKNOWN
        private set

    /** True when [state] is inferred from the station being unreachable rather than measured. */
    var assumed: Boolean = false
        private set

    var volts: Double? = null
        private set

    private var pending: GridState? = null
    private var pendingSince: Instant? = null
    private var lastSample: Instant? = null

    /** @return true when [state] changed */
    fun onVoltage(volts: Double, now: Instant): Boolean {
        this.volts = volts
        lastSample = now
        val target = if (volts >= config.thresholdVolts) GridState.ON else GridState.OFF
        return when {
            // Nothing measured yet, or only a guess: the first real sample wins immediately.
            state == GridState.UNKNOWN || assumed -> commit(target)
            target == state -> { pending = null; false }
            pending != target -> { pending = target; pendingSince = now; false }
            else -> settle(now)
        }
    }

    /** Any other message from the station: proves it is alive without saying anything about the grid. */
    fun onAlive(now: Instant) {
        if (volts != null) lastSample = now
    }

    /** The cloud says the station is not connected. */
    fun onOffline(connected: Boolean): Boolean = lost(connected)

    /** Call about once a second. [connected] tells whether our own link to the cloud is up. */
    fun onTick(now: Instant, connected: Boolean): Boolean {
        if (settle(now)) return true
        val last = lastSample ?: return false
        if (Duration.between(last, now) >= config.silence) {
            lastSample = null
            return lost(connected)
        }
        return false
    }

    /** We stopped watching: whatever we knew is stale from here on. */
    fun reset() {
        state = GridState.UNKNOWN
        assumed = false
        volts = null
        pending = null
        pendingSince = null
        lastSample = null
    }

    private fun settle(now: Instant): Boolean {
        val target = pending ?: return false
        val since = pendingSince ?: return false
        return if (Duration.between(since, now) >= config.debounce) commit(target) else false
    }

    private fun commit(target: GridState): Boolean {
        pending = null
        pendingSince = null
        val changed = state != target
        state = target
        assumed = false
        return changed
    }

    private fun lost(connected: Boolean): Boolean {
        pending = null
        pendingSince = null
        volts = null
        return when {
            // Already dark: the station running flat or the router dying changes nothing.
            state == GridState.OFF -> false
            config.offlineMeansOutage && connected -> {
                state = GridState.OFF
                assumed = true
                true
            }
            state == GridState.UNKNOWN -> false
            else -> {
                state = GridState.UNKNOWN
                assumed = false
                true
            }
        }
    }
}
