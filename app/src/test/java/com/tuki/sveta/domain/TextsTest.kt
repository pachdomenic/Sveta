package com.tuki.sveta.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

class TextsTest {

    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(KyivZone).toInstant()

    @Test
    fun `durations are compact`() {
        assertEquals("менше хвилини", Texts.duration(Duration.ofSeconds(20)))
        assertEquals("14 хв", Texts.duration(Duration.ofSeconds(14 * 60 + 10)))
        assertEquals("2 год", Texts.duration(Duration.ofMinutes(120)))
        assertEquals("2 год 14 хв", Texts.duration(Duration.ofMinutes(134)))
        assertEquals("1 д 3 год", Texts.duration(Duration.ofHours(27)))
    }

    @Test
    fun `times are relative to today`() {
        val now = at(9, 12)
        assertEquals("о 18:00", Texts.at(at(9, 18), now))
        assertEquals("об 11:30", Texts.at(at(9, 11, 30), now))
        assertEquals("завтра о 06:30", Texts.at(at(10, 6, 30), now))
        assertEquals("12.11 о 09:00", Texts.at(at(12, 9), now))
    }

    @Test
    fun `range ending at midnight reads as 24`() {
        assertEquals("20:00–24:00", Texts.range(at(9, 20), at(10, 0)))
        assertEquals("22:00–03:30", Texts.range(at(9, 22), at(10, 3, 30)))
    }

    @Test
    fun `queue and volts labels`() {
        assertEquals("Черга 3.1", Texts.queue("GPV3.1"))
        assertEquals("228 В", Texts.volts(228.4))
    }

    @Test
    fun `day outages are listed as ranges`() {
        val slots = MutableList(48) { SlotState.ON }
        for (i in 4 until 13) slots[i] = SlotState.OFF
        for (i in 40 until 48) slots[i] = SlotState.OFF
        val day = DaySchedule(java.time.LocalDate.of(2026, 11, 9), slots, true)
        assertEquals("02:00–06:30, 20:00–24:00", Texts.outages(day))
        assertEquals("без відключень", Texts.outages(day.copy(slots = List(48) { SlotState.ON })))
        assertEquals("Сьогодні", Texts.dayName(day, at(9, 12)))
        assertEquals("Завтра", Texts.dayName(day, at(8, 12)))
    }
}
