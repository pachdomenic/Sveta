package com.tuki.sveta.service

import com.tuki.sveta.data.store.LiveChip
import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.EmergencyStatus
import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.LagWindow
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.Prediction
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Timeline
import com.tuki.sveta.domain.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class LiveContentTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, 9, h, m).atZone(KyivZone).toInstant()

    /** Outages 10:00-12:00 and 18:00-22:00. */
    private val timeline = Timeline(listOf(DaySchedule(date, List(48) { i -> if (i in 20..23 || i in 36..43) SlotState.OFF else SlotState.ON }, true)))

    private fun overview(
        now: Instant,
        grid: GridState,
        volts: Double? = null,
        since: Instant? = null,
        prediction: Prediction? = null,
        assumed: Boolean = false,
        timeline: Timeline? = this.timeline,
    ) = Overview(now, grid, assumed, volts, since, timeline, prediction, 74, 93, 310, 800, 150)

    private fun prediction(kind: SwitchKind, postedAt: Instant): Prediction {
        val window = LagWindow(Duration.ofMinutes(5), Duration.ofMinutes(20), null, 0)
        return Prediction(Trigger("1", "Лісова", kind, "svitlo_test", "…", postedAt), postedAt.plus(window.min), postedAt.plus(window.max), postedAt.plus(Duration.ofMinutes(40)), window)
    }

    @Test
    fun `grid on shows voltage and the next outage`() {
        val c = LiveContentBuilder.build(overview(at(15, 46), GridState.ON, volts = 228.3, since = at(12)))
        assertEquals("Світло є · 228 В", c.title)
        assertEquals("Вимкнуть о 18:00 · через 2 год 14 хв", c.text)
        assertEquals("228 В", c.chip)
        assertNull(c.countdownTo)
        assertEquals(Tone.ON, c.tone)
        assertEquals(226f / 360f, c.progress!!, 0.001f)
        assertEquals("Батарея 74 % · до повного 1 год 33 хв", c.detail)
    }

    private val emergency = EmergencyStatus(true, at(13).toEpochMilli(), at(13).toEpochMilli(), "Київська область: запроваджені екстрені відключення", "dtek_ua", 1)

    @Test
    fun `emergency outages take the schedule out of the card`() {
        val on = LiveContentBuilder.build(overview(at(15, 46), GridState.ON, volts = 228.3, since = at(12)).copy(emergency = emergency))
        assertEquals("Світло є · 228 В", on.title)
        assertEquals("Екстрені відключення: графік не діє, можуть вимкнути будь-якої миті", on.text)
        assertNull(on.progress)

        val off = LiveContentBuilder.build(overview(at(19), GridState.OFF, since = at(17, 52)).copy(emergency = emergency))
        assertEquals("Світла немає", off.title)
        assertEquals("Екстрені відключення: графік не діє, коли увімкнуть — невідомо", off.text)
        assertNull(off.countdownTo)
        assertNull(off.progress)
    }

    @Test
    fun `emergency outages without a station admit there is nothing to count down to`() {
        val c = LiveContentBuilder.build(overview(at(19), GridState.UNKNOWN).copy(emergency = emergency))
        assertEquals("Екстрені відключення", c.title)
        assertEquals(Tone.WARNING, c.tone)
        assertNull(c.countdownTo)
        assertNull(c.chip)
    }

    @Test
    fun `grid off counts down to the scheduled restore`() {
        val c = LiveContentBuilder.build(overview(at(19), GridState.OFF, since = at(17, 52)))
        assertEquals("Світла немає", c.title)
        assertEquals("Увімкнуть о 22:00 · через 3 год", c.text)
        assertEquals(at(22), c.countdownTo)
        // No chip text, so the chip shows the countdown.
        assertNull(c.chip)
        assertEquals("Батарея 74 % · вистачить на 5 год 10 хв", c.detail)
        assertEquals(Tone.OFF, c.tone)
    }

    @Test
    fun `chip can show the station charge or what is left of it instead of the countdown`() {
        val o = overview(at(19), GridState.OFF, since = at(17, 52))
        val battery = LiveContentBuilder.build(o, LiveChip.BATTERY)
        assertEquals("74%", battery.chip)
        // The header of the card keeps counting down.
        assertEquals(at(22), battery.countdownTo)

        val runtime = LiveContentBuilder.build(o, LiveChip.RUNTIME)
        assertEquals("5г10х", runtime.chip)
        assertEquals(at(22), runtime.countdownTo)
        assertEquals(battery.text, runtime.text)
    }

    @Test
    fun `chip falls back to the countdown when the station has nothing to say`() {
        val silent = overview(at(19), GridState.OFF, since = at(17, 52)).copy(batteryPercent = null, dischargeRemainMinutes = null)
        for (choice in LiveChip.entries) {
            val c = LiveContentBuilder.build(silent, choice)
            assertNull(c.chip)
            assertEquals(at(22), c.countdownTo)
        }
        // Charge known, runtime not (the station reports 0 while idle).
        val idle = overview(at(19), GridState.OFF, since = at(17, 52)).copy(dischargeRemainMinutes = 0)
        assertNull(LiveContentBuilder.build(idle, LiveChip.RUNTIME).chip)
    }

    @Test
    fun `chip choice only matters while the power is out`() {
        val on = overview(at(15, 46), GridState.ON, volts = 228.3, since = at(12))
        assertEquals(LiveContentBuilder.build(on), LiveContentBuilder.build(on, LiveChip.BATTERY))
        assertEquals("228 В", LiveContentBuilder.build(on, LiveChip.RUNTIME).chip)
    }

    @Test
    fun `unplanned outage admits the end is unknown`() {
        val c = LiveContentBuilder.build(overview(at(14), GridState.OFF, since = at(13, 30)))
        assertEquals("Поза графіком — коли увімкнуть, невідомо", c.text)
        assertNull(c.countdownTo)
        assertEquals("Нема", c.chip)
        assertEquals("74%", LiveContentBuilder.build(overview(at(14), GridState.OFF, since = at(13, 30)), LiveChip.BATTERY).chip)
        assertNull(c.progress)
    }

    @Test
    fun `late outage is called out`() {
        val c = LiveContentBuilder.build(overview(at(18, 6), GridState.ON, volts = 230.0))
        assertTrue(c.text.startsWith("За графіком уже відключення"))
    }

    @Test
    fun `assumed outage is worded as a guess`() {
        assertEquals("Схоже, світла немає", LiveContentBuilder.build(overview(at(19), GridState.OFF, assumed = true)).title)
    }

    @Test
    fun `neighbour outage turns the card into a warning with a countdown`() {
        val c = LiveContentBuilder.build(overview(at(17, 44), GridState.ON, volts = 229.0, prediction = prediction(SwitchKind.OFF, at(17, 42))))
        assertEquals("Скоро можуть вимкнути · 229 В", c.title)
        assertEquals("«Лісова» без світла з 17:42. Наша черга: 17:47–18:02", c.text)
        assertEquals(at(17, 47), c.countdownTo)
        assertEquals(Tone.WARNING, c.tone)
        // past the earliest moment the countdown switches to the end of the window
        val later = LiveContentBuilder.build(overview(at(17, 50), GridState.ON, prediction = prediction(SwitchKind.OFF, at(17, 42))))
        assertEquals(at(18, 2), later.countdownTo)
    }

    @Test
    fun `neighbour restore hint replaces the scheduled time while dark`() {
        val c = LiveContentBuilder.build(overview(at(21, 30), GridState.OFF, prediction = prediction(SwitchKind.ON, at(21, 28))))
        assertEquals("«Лісова» уже зі світлом — чекай 21:33–21:48", c.text)
        assertEquals(at(21, 48), c.countdownTo)
    }

    @Test
    fun `without the station the schedule speaks, clearly labelled`() {
        val c = LiveContentBuilder.build(overview(at(11), GridState.UNKNOWN))
        assertEquals("За графіком світла немає", c.title)
        assertEquals("Увімкнуть о 12:00 · через 1 год", c.text)
        val on = LiveContentBuilder.build(overview(at(13), GridState.UNKNOWN))
        assertEquals("За графіком світло є", on.title)
        assertNull(on.chip)
        assertEquals(Tone.UNKNOWN, on.tone)
    }

    @Test
    fun `battery power is shown while charging and discharging`() {
        fun detail(grid: GridState, now: Instant, percent: Int, inW: Int, outW: Int, watts: Boolean = true) =
            LiveContentBuilder.build(
                Overview(now, grid, false, null, null, timeline, null, percent, 93, 310, inW, outW),
                watts = watts,
            ).detail

        // Grid load passes through, only the rest goes into the battery.
        assertEquals("Батарея 74 % · заряд 650 Вт · до повного 1 год 33 хв", detail(GridState.ON, at(15), 74, 800, 150))
        assertEquals("Батарея 74 % · розряд 150 Вт · вистачить на 5 год 10 хв", detail(GridState.OFF, at(19), 74, 0, 150))
        // Discharging shows what the load draws, not what is left after the input.
        assertEquals("Батарея 74 % · розряд 400 Вт · вистачить на 5 год 10 хв", detail(GridState.OFF, at(19), 74, 120, 400))
        // Full battery feeding the load from the grid: nothing flows, inverter losses are not charging.
        assertEquals("Батарея 100 %", detail(GridState.ON, at(15), 100, 158, 150))
        assertEquals("Батарея 74 % · до повного 1 год 33 хв", detail(GridState.ON, at(15), 74, 800, 150, watts = false))
    }

    @Test
    fun `nothing known at all`() {
        assertEquals("Немає даних", LiveContentBuilder.build(overview(at(11), GridState.UNKNOWN, timeline = null)).title)
    }
}
