package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant

/** A moment the published schedule switches our queue off or back on. */
data class PlannedSwitch(
    val at: Instant,
    val kind: SwitchKind,
    /** End of the stretch that begins at [at]: when the outage ends, or when the next one starts. Null when the schedule does not say. */
    val until: Instant?,
)

enum class AlertPhase {
    /** Heads-up some minutes before the switch. */
    AHEAD,

    /** The switch itself. */
    NOW,
}

data class PlannedAlert(val switch: PlannedSwitch, val phase: AlertPhase, val fireAt: Instant) {
    /** Identifies the alert across restarts so it is never shown twice. */
    val key: String get() = "${switch.at.epochSecond}:${switch.kind.name}:${phase.name}"
}

/** Reminders derived from the schedule alone: no station needed. */
object PlannedAlerts {

    /** How long after the switch a "happening now" alert is still worth showing. */
    val GRACE: Duration = Duration.ofMinutes(5)

    /**
     * Switches in the published days. Template days are ignored: "possible" outages are not worth a push.
     * An outage that touches the edge of what is published has no switch there, since nobody knows yet
     * whether it carries on into the next day.
     */
    fun switches(days: List<DaySchedule>): List<PlannedSwitch> {
        val intervals = Timeline(days.filter { it.factual }).intervals
        val out = ArrayList<PlannedSwitch>()
        intervals.forEachIndexed { i, outage ->
            if (outage.state != SlotState.OFF) return@forEachIndexed
            val before = intervals.getOrNull(i - 1)?.takeIf { it.end == outage.start }
            val after = intervals.getOrNull(i + 1)?.takeIf { it.start == outage.end }
            if (before != null) out += PlannedSwitch(outage.start, SwitchKind.OFF, until = outage.end.takeIf { after != null })
            if (after != null) {
                val nextOutage = intervals.drop(i + 1).firstOrNull { it.state == SlotState.OFF }
                out += PlannedSwitch(outage.end, SwitchKind.ON, until = nextOutage?.start)
            }
        }
        return out
    }

    /** All alerts for [switches], in firing order. A zero [lead] means no heads-up. */
    fun alerts(switches: List<PlannedSwitch>, lead: Duration): List<PlannedAlert> {
        val out = ArrayList<PlannedAlert>()
        var previous: Instant? = null
        for (switch in switches.sortedBy { it.at }) {
            if (lead > Duration.ZERO) {
                val fireAt = switch.at.minus(lead)
                // A stretch shorter than the lead: the alert about the previous switch already names this one.
                if (previous == null || fireAt.isAfter(previous)) out += PlannedAlert(switch, AlertPhase.AHEAD, fireAt)
            }
            out += PlannedAlert(switch, AlertPhase.NOW, switch.at)
            previous = switch.at
        }
        return out.sortedBy { it.fireAt }
    }

    /** Alerts whose time has come and that still make sense at [now]. */
    fun due(alerts: List<PlannedAlert>, handled: Set<String>, now: Instant): List<PlannedAlert> = alerts.filter { alert ->
        alert.key !in handled && !alert.fireAt.isAfter(now) && when (alert.phase) {
            AlertPhase.AHEAD -> now.isBefore(alert.switch.at)
            AlertPhase.NOW -> Duration.between(alert.switch.at, now) < GRACE
        }
    }

    /** When to wake up next. */
    fun next(alerts: List<PlannedAlert>, handled: Set<String>, now: Instant): Instant? =
        alerts.firstOrNull { it.key !in handled && it.fireAt.isAfter(now) }?.fireAt
}
