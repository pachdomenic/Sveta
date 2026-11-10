package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant

/** Everything needed to describe "what is going on with the power right now". */
data class Overview(
    val now: Instant,
    val grid: GridState,
    /** Grid state is a guess from the station being offline. */
    val assumed: Boolean,
    val volts: Double?,
    /** When the grid entered its current state, if we saw it happen. */
    val gridSince: Instant?,
    val timeline: Timeline?,
    val prediction: Prediction?,
    val batteryPercent: Int?,
    val chargeRemainMinutes: Int?,
    val dischargeRemainMinutes: Int?,
    val inputWatts: Int?,
    val outputWatts: Int?,
    /** Emergency outages in effect: the schedule in [timeline] is not being followed. */
    val emergency: EmergencyStatus? = null,
) {
    val scheduleState: SlotState? = timeline?.stateAt(now)

    /** Measured state when we have one, otherwise what the schedule says should be happening. */
    val effectiveOn: Boolean? = when (grid) {
        GridState.ON -> true
        GridState.OFF -> false
        GridState.UNKNOWN -> scheduleState?.let { it != SlotState.OFF }
    }

    val measured: Boolean get() = grid != GridState.UNKNOWN

    val expectation: Expectation? = effectiveOn?.let { on -> timeline?.expectation(on, now) }

    val nextSwitch: Instant? get() = expectation?.nextSwitch

    /** Start of the current stretch: the real flip if we saw it, the scheduled one otherwise. */
    val since: Instant? get() = gridSince?.takeIf { measured } ?: expectation?.since

    /** 0..1 position between [since] and [nextSwitch]. */
    val progress: Float?
        get() {
            val from = since ?: return null
            val to = nextSwitch ?: return null
            val total = Duration.between(from, to).toMillis()
            if (total <= 0) return null
            return (Duration.between(from, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
        }

    /** A prediction that applies to the current state (outage warning while on, restore hint while off). */
    val activePrediction: Prediction?
        get() = prediction?.takeIf { p ->
            when (p.kind) {
                SwitchKind.OFF -> effectiveOn != false || !measured
                SwitchKind.ON -> effectiveOn == false
            }
        }
}
