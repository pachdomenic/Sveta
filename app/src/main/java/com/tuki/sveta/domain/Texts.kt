package com.tuki.sveta.domain

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Ukrainian wording shared by the UI and notifications. The app ships in one language only. */
object Texts {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("dd.MM")

    fun duration(d: Duration): String {
        val minutes = (d.seconds + 30) / 60
        return when {
            minutes < 1 -> "менше хвилини"
            minutes < 60 -> "$minutes хв"
            minutes < 24 * 60 -> (minutes / 60).let { h -> if (minutes % 60 == 0L) "$h год" else "$h год ${minutes % 60} хв" }
            else -> "${minutes / (24 * 60)} д ${minutes % (24 * 60) / 60} год"
        }
    }

    fun minutes(total: Int): String = duration(Duration.ofMinutes(total.toLong()))

    fun clock(t: Instant): String = CLOCK.format(t.atZone(KyivZone))

    /** "о 18:00", "об 11:30", "завтра о 06:30", "12.11 о 09:00". */
    fun at(t: Instant, now: Instant): String {
        val time = t.atZone(KyivZone)
        val today = now.atZone(KyivZone).toLocalDate()
        val preposition = if (time.hour == 11) "об" else "о"
        val clock = "$preposition ${CLOCK.format(time)}"
        return when (time.toLocalDate()) {
            today -> clock
            today.plusDays(1) -> "завтра $clock"
            else -> "${DAY.format(time)} $clock"
        }
    }

    /** A moment in the past: "18:04", "вчора 18:04", "07.11 18:04". */
    fun past(t: Instant, now: Instant): String {
        val time = t.atZone(KyivZone)
        val today = now.atZone(KyivZone).toLocalDate()
        return when (time.toLocalDate()) {
            today -> CLOCK.format(time)
            today.minusDays(1) -> "вчора ${CLOCK.format(time)}"
            else -> "${DAY.format(time)} ${CLOCK.format(time)}"
        }
    }

    fun range(from: Instant, to: Instant): String = "${clock(from)}–${endClock(from, to)}"

    /** Midnight as the end of a range reads better as 24:00. */
    private fun endClock(from: Instant, to: Instant): String {
        val end = to.atZone(KyivZone)
        val nextDay = end.toLocalDate() != from.atZone(KyivZone).toLocalDate()
        return if (nextDay && end.hour == 0 && end.minute == 0) "24:00" else CLOCK.format(end)
    }

    fun volts(v: Double): String = "${v.roundToInt()} В"

    fun queue(groupId: String): String = "Черга ${groupId.removePrefix("GPV")}"

    /** "02:00–06:30, 13:30–21:00" or a note that the day is clear. */
    fun outages(day: DaySchedule): String {
        val intervals = Timeline(listOf(day)).intervals.filter { it.state == SlotState.OFF }
        return if (intervals.isEmpty()) "без відключень" else intervals.joinToString(", ") { range(it.start, it.end) }
    }

    fun dayName(day: DaySchedule, now: Instant): String {
        val today = now.atZone(KyivZone).toLocalDate()
        return when (day.date) {
            today -> "Сьогодні"
            today.plusDays(1) -> "Завтра"
            else -> DAY.format(day.date)
        }
    }
}
