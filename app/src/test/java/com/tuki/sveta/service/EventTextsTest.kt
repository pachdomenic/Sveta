package com.tuki.sveta.service

import com.tuki.sveta.data.dtek.ScheduleChange
import com.tuki.sveta.domain.AlertPhase
import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.EmergencyStatus
import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.PlannedAlert
import com.tuki.sveta.domain.PlannedSwitch
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class EventTextsTest {

    private val nov9: LocalDate = LocalDate.of(2026, 11, 9)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(KyivZone).toInstant()
    private fun day(date: LocalDate, vararg off: IntRange) = DaySchedule(date, List(48) { i -> if (off.any { i in it }) SlotState.OFF else SlotState.ON }, true)

    @Test
    fun `heads-up before an outage`() {
        val alert = PlannedAlert(PlannedSwitch(at(9, 18), SwitchKind.OFF, until = at(9, 22)), AlertPhase.AHEAD, at(9, 17, 45))
        val text = EventTexts.planned(alert, at(9, 17, 45))
        assertEquals("Вимкнуть о 18:00 · через 15 хв", text.title)
        assertEquals("За графіком. Відключення 18:00–22:00, 4 год.", text.text)
    }

    @Test
    fun `a late heads-up tells the real remaining time`() {
        val alert = PlannedAlert(PlannedSwitch(at(9, 18), SwitchKind.OFF, until = null), AlertPhase.AHEAD, at(9, 17, 45))
        val text = EventTexts.planned(alert, at(9, 17, 53))
        assertEquals("Вимкнуть о 18:00 · через 7 хв", text.title)
        assertEquals("За графіком. Коли увімкнуть, графік ще не каже.", text.text)
    }

    @Test
    fun `heads-up before the power returns`() {
        val alert = PlannedAlert(PlannedSwitch(at(9, 22), SwitchKind.ON, until = at(10, 2)), AlertPhase.AHEAD, at(9, 21, 30))
        val text = EventTexts.planned(alert, at(9, 21, 30))
        assertEquals("Увімкнуть о 22:00 · через 30 хв", text.title)
        assertEquals("За графіком. Наступне відключення завтра о 02:00.", text.text)
    }

    @Test
    fun `alerts at the moment of the switch`() {
        val off = PlannedAlert(PlannedSwitch(at(9, 22), SwitchKind.OFF, until = at(10, 2)), AlertPhase.NOW, at(9, 22))
        assertEquals(EventText("За графіком зараз вимикають", "Відключення 22:00–02:00, 4 год."), EventTexts.planned(off, at(9, 22)))
        val on = PlannedAlert(PlannedSwitch(at(9, 22), SwitchKind.ON, until = null), AlertPhase.NOW, at(9, 22))
        assertEquals(EventText("За графіком зараз вмикають", "Більше відключень у графіку не видно."), EventTexts.planned(on, at(9, 22)))
    }

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

    private fun overview(now: Instant, grid: GridState) =
        Overview(now, grid, false, null, null, Timeline(listOf(day(nov9, 20..23, 36..43))), null, null, null, null, null, null)

    @Test
    fun `emergency start names the source and warns about the schedule`() {
        val status = EmergencyStatus(true, at(9, 13).toEpochMilli(), at(9, 13).toEpochMilli(), "Київська область: за командою Укренерго запроваджені екстрені відключення", "dtek_ua", 4806)
        val text = EventTexts.emergency(status, overview(at(9, 13, 2), GridState.ON))
        assertEquals("Екстрені відключення", text.title)
        assertTrue(text.text.startsWith("Київська область: за командою Укренерго запроваджені екстрені відключення (@dtek_ua)\n"))
        assertTrue(text.text.endsWith("Графік зараз не діє: світло можуть вимкнути й увімкнути будь-коли."))
    }

    @Test
    fun `emergency end says what the schedule expects next`() {
        val status = EmergencyStatus(false, at(9, 15).toEpochMilli(), at(9, 15).toEpochMilli(), "Київщина: повертаємось до графіків, екстрені відключення скасовано", "dtek_ua", 4771)
        val on = EventTexts.emergency(status, overview(at(9, 15, 2), GridState.ON))
        assertEquals("Екстрені відключення скасовано", on.title)
        assertTrue(on.text.endsWith("Графік знову діє: наступне відключення о 18:00."))
        val off = EventTexts.emergency(status, overview(at(9, 19), GridState.OFF))
        assertTrue(off.text.endsWith("Графік знову діє: увімкнуть о 22:00."))
    }
}
