package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** DTEK publishes everything in Kyiv local time. "Europe/Kiev" is the id every tzdata version knows. */
val KyivZone: ZoneId = ZoneId.of("Europe/Kiev")

enum class SlotState { ON, OFF, MAYBE }

/**
 * One calendar day as 48 half-hour slots.
 * [factual] is false when the day comes from the weekly preset rather than a published schedule.
 */
data class DaySchedule(
    val date: LocalDate,
    val slots: List<SlotState>,
    val factual: Boolean,
) {
    init {
        require(slots.size == SLOTS_PER_DAY) { "expected $SLOTS_PER_DAY slots, got ${slots.size}" }
    }

    fun slotStart(index: Int): Instant =
        date.atStartOfDay(KyivZone).plusMinutes(index * 30L).toInstant()

    val offMinutes: Int get() = slots.count { it == SlotState.OFF } * 30

    companion object {
        const val SLOTS_PER_DAY = 48
    }
}

data class Interval(val start: Instant, val end: Instant, val state: SlotState) {
    val duration: Duration get() = Duration.between(start, end)
    operator fun contains(t: Instant) = !t.isBefore(start) && t.isBefore(end)
}

/** What we expect to happen next, given the schedule and the real grid state. */
data class Expectation(
    /** Moment the grid is expected to flip, or null when the schedule gives no answer. */
    val nextSwitch: Instant?,
    /** Start of the stretch we are currently in, used as the origin for progress. */
    val since: Instant?,
    /** True when the real grid state contradicts the schedule right now. */
    val offSchedule: Boolean,
)

/** Consecutive days merged into intervals of equal state. */
class Timeline(days: List<DaySchedule>) {

    val days: List<DaySchedule> = days.sortedBy { it.date }
    val intervals: List<Interval> = merge(this.days)

    fun intervalAt(t: Instant): Interval? = intervals.firstOrNull { t in it }

    fun stateAt(t: Instant): SlotState? = intervalAt(t)?.state

    /** Start of the first definite outage that begins after [t]. */
    fun nextOffStart(t: Instant): Instant? =
        intervals.firstOrNull { it.state == SlotState.OFF && it.start.isAfter(t) }?.start

    /** First outage that is running at [t] or starts within [tolerance] after it. */
    fun outageAround(t: Instant, tolerance: Duration): Interval? =
        intervals.firstOrNull {
            it.state == SlotState.OFF && it.end.isAfter(t) && !it.start.isAfter(t.plus(tolerance))
        }

    /**
     * Combines the schedule with the measured grid state.
     *
     * Grid on: the next switch is the next scheduled outage start. If the schedule says we should be
     * dark already, the outage is simply late and can start any minute, so it is reported as off-schedule
     * with the scheduled start as the reference.
     *
     * Grid off: the next switch is the end of the outage that is running or about to start
     * ([earlyTolerance] covers lines being cut a bit ahead of the slot). Anything else is an unplanned
     * outage with no known end.
     */
    fun expectation(
        gridOn: Boolean,
        now: Instant,
        earlyTolerance: Duration = Duration.ofMinutes(30),
    ): Expectation {
        val current = intervalAt(now)
        return if (gridOn) {
            if (current?.state == SlotState.OFF) {
                Expectation(nextSwitch = null, since = current.start, offSchedule = true)
            } else {
                Expectation(
                    nextSwitch = nextOffStart(now),
                    since = lastOffEnd(now) ?: current?.start,
                    offSchedule = false,
                )
            }
        } else {
            val outage = outageAround(now, earlyTolerance)
            if (outage != null) {
                Expectation(
                    nextSwitch = outage.end,
                    since = minOf(outage.start, now),
                    offSchedule = false,
                )
            } else {
                Expectation(nextSwitch = null, since = null, offSchedule = true)
            }
        }
    }

    private fun lastOffEnd(t: Instant): Instant? =
        intervals.lastOrNull { it.state == SlotState.OFF && !it.end.isAfter(t) }?.end

    companion object {
        private val HALF_HOUR: Duration = Duration.ofMinutes(30)

        private fun merge(days: List<DaySchedule>): List<Interval> {
            val out = ArrayList<Interval>()
            for (day in days) {
                day.slots.forEachIndexed { i, state ->
                    val start = day.slotStart(i)
                    val end = start.plus(HALF_HOUR)
                    val last = out.lastOrNull()
                    if (last != null && last.state == state && last.end == start) {
                        out[out.lastIndex] = last.copy(end = end)
                    } else {
                        out += Interval(start, end, state)
                    }
                }
            }
            return out
        }
    }
}
