package com.tuki.sveta.service

import com.tuki.sveta.data.dtek.ScheduleChange
import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.SlotState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class EventTextsTest {

    private val nov9: LocalDate = LocalDate.of(2026, 11, 9)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(KyivZone).toInstant()
    private fun day(date: LocalDate, vararg off: IntRange) = DaySchedule(date, List(48) { i -> if (off.any { i in it }) SlotState.OFF else SlotState.ON }, true)

    @Test
    fun `new schedule for tomorrow`() {
        val text = EventTexts.schedule(ScheduleChange(changed = emptyList(), added = listOf(day(nov9.plusDays(1), 0..12, 21..33))), at(9, 21))
        assertEquals("З'явився графік на завтра", text.title)
        assertEquals("Завтра: 00:00–06:30, 10:30–17:00 · разом 13 год", text.text)
    }

    @Test
    fun `changed schedule lists the new hours`() {
        val text = EventTexts.schedule(ScheduleChange(changed = listOf(day(nov9, 36..43)), added = emptyList()), at(9, 12))
        assertEquals("Графік відключень змінено", text.title)
        assertEquals("Сьогодні: 18:00–22:00 · разом 4 год", text.text)
    }

    @Test
    fun `a day that lost all outages is announced as cancelled`() {
        val clear = day(nov9)
        val text = EventTexts.schedule(ScheduleChange(changed = listOf(clear), added = emptyList(), cancelled = listOf(clear)), at(9, 12))
        assertEquals("Відключення на сьогодні скасовано", text.title)
        assertEquals("Сьогодні: без відключень", text.text)
    }

    @Test
    fun `cancelled today but changed tomorrow is just a change`() {
        val clear = day(nov9)
        val text = EventTexts.schedule(ScheduleChange(changed = listOf(clear, day(nov9.plusDays(1), 4..9)), added = emptyList(), cancelled = listOf(clear)), at(9, 12))
        assertEquals("Графік відключень змінено", text.title)
        assertEquals("Сьогодні: без відключень\nЗавтра: 02:00–05:00 · разом 3 год", text.text)
    }
}
