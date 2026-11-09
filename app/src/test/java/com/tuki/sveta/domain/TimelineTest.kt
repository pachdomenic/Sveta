package com.tuki.sveta.domain

import com.tuki.sveta.domain.SlotState.MAYBE
import com.tuki.sveta.domain.SlotState.OFF
import com.tuki.sveta.domain.SlotState.ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class TimelineTest {

    private val d1: LocalDate = LocalDate.of(2026, 11, 9)
    private val d2: LocalDate = d1.plusDays(1)

    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant =
        LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(KyivZone).toInstant()

    /** Builds a day from "HH:mm-HH:mm" outage ranges; 24:00 is allowed as an end. */
    private fun day(date: LocalDate, vararg off: String, maybe: List<String> = emptyList(), factual: Boolean = true): DaySchedule {
        val slots = MutableList(48) { ON }
        fun fill(range: String, state: SlotState) {
            val (a, b) = range.split("-").map { it.split(":").let { p -> p[0].toInt() * 2 + p[1].toInt() / 30 } }
            for (i in a until b) slots[i] = state
        }
        maybe.forEach { fill(it, MAYBE) }
        off.forEach { fill(it, OFF) }
        return DaySchedule(date, slots, factual)
    }

    @Test
    fun `outage spanning midnight becomes one interval`() {
        val t = Timeline(listOf(day(d1, "22:00-24:00"), day(d2, "00:00-03:30")))
        val off = t.intervals.single { it.state == OFF }
        assertEquals(at(d1, 22), off.start)
        assertEquals(at(d2, 3, 30), off.end)
        assertEquals(Duration.ofMinutes(330), off.duration)
    }

    @Test
    fun `days are sorted before merging`() {
        val t = Timeline(listOf(day(d2, "00:00-01:00"), day(d1, "23:00-24:00")))
        assertEquals(1, t.intervals.count { it.state == OFF })
    }

    @Test
    fun `interval lookup is start inclusive end exclusive`() {
        val t = Timeline(listOf(day(d1, "10:00-12:00")))
        assertEquals(ON, t.stateAt(at(d1, 9, 59)))
        assertEquals(OFF, t.stateAt(at(d1, 10)))
        assertEquals(ON, t.stateAt(at(d1, 12)))
        assertNull(t.stateAt(at(d2, 0)))
    }

    @Test
    fun `grid on - next switch is the next outage start`() {
        val t = Timeline(listOf(day(d1, "02:00-04:00", "10:00-12:00")))
        val e = t.expectation(gridOn = true, now = at(d1, 6))
        assertEquals(at(d1, 10), e.nextSwitch)
        assertEquals(at(d1, 4), e.since)
        assertFalse(e.offSchedule)
    }

    @Test
    fun `grid on - no more outages gives no switch`() {
        val t = Timeline(listOf(day(d1, "02:00-04:00")))
        assertNull(t.expectation(gridOn = true, now = at(d1, 6)).nextSwitch)
    }

    @Test
    fun `grid on - possible outages are not treated as definite`() {
        val t = Timeline(listOf(day(d1, "18:00-20:00", maybe = listOf("08:00-12:00"))))
        assertEquals(at(d1, 18), t.expectation(gridOn = true, now = at(d1, 6)).nextSwitch)
    }

    @Test
    fun `grid on during a scheduled outage is off schedule`() {
        val t = Timeline(listOf(day(d1, "10:00-12:00")))
        val e = t.expectation(gridOn = true, now = at(d1, 10, 7))
        assertTrue(e.offSchedule)
        assertNull(e.nextSwitch)
        assertEquals(at(d1, 10), e.since)
    }

    @Test
    fun `grid off during outage - next switch is its end`() {
        val t = Timeline(listOf(day(d1, "10:00-12:00")))
        val e = t.expectation(gridOn = false, now = at(d1, 11))
        assertEquals(at(d1, 12), e.nextSwitch)
        assertEquals(at(d1, 10), e.since)
        assertFalse(e.offSchedule)
    }

    @Test
    fun `grid cut a few minutes early still maps to the coming outage`() {
        val t = Timeline(listOf(day(d1, "10:00-12:00")))
        val now = at(d1, 9, 45)
        val e = t.expectation(gridOn = false, now = now)
        assertEquals(at(d1, 12), e.nextSwitch)
        assertEquals(now, e.since)
    }

    @Test
    fun `grid off with no outage nearby is unplanned`() {
        val t = Timeline(listOf(day(d1, "10:00-12:00")))
        val e = t.expectation(gridOn = false, now = at(d1, 6))
        assertTrue(e.offSchedule)
        assertNull(e.nextSwitch)
    }

    @Test
    fun `outage minutes are counted per day`() {
        assertEquals(270, day(d1, "02:00-04:00", "10:00-12:30").offMinutes)
    }
}
