package com.tuki.sveta.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class EventWatchTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, 9, h, m).atZone(KyivZone).toInstant()

    /** Outages 10:00-12:00 and 18:00-22:00. */
    private val timeline = Timeline(listOf(DaySchedule(date, List(48) { i -> if (i in 20..23 || i in 36..43) SlotState.OFF else SlotState.ON }, true)))

    private fun overview(
        now: Instant,
        grid: GridState = GridState.UNKNOWN,
        timeline: Timeline? = this.timeline,
        prediction: Prediction? = null,
        emergency: EmergencyStatus? = null,
        volts: Double? = null,
        battery: Int? = null,
    ) = Overview(now, grid, false, volts, null, timeline, prediction, battery, null, null, null, null, emergency)

    private fun warning(postedAt: Instant, kind: SwitchKind = SwitchKind.OFF): Prediction {
        val window = LagWindow(Duration.ofMinutes(5), Duration.ofMinutes(20), null, 0)
        return Prediction(Trigger("1", "Лісова", kind, "lisova_svitlo", "…", postedAt), postedAt.plus(window.min), postedAt.plus(window.max), postedAt.plus(Duration.ofMinutes(40)), window)
    }

    private val emergency = EmergencyStatus(true, at(13).toEpochMilli(), at(13).toEpochMilli(), "Київщина: екстрені відключення", "dtek_ua", 1)

    @Test
    fun `the first look only sets the baseline`() {
        assertFalse(EventWatch().happened(overview(at(9), GridState.ON)))
        assertFalse(EventWatch().happened(overview(at(11), GridState.OFF, emergency = emergency, prediction = warning(at(10, 50), SwitchKind.ON))))
    }

    @Test
    fun `the measured light going off and coming back are events`() {
        val watch = EventWatch()
        watch.happened(overview(at(9), GridState.ON))
        assertFalse(watch.happened(overview(at(9, 1), GridState.ON)))
        assertTrue(watch.happened(overview(at(9, 30), GridState.OFF)))
        assertFalse(watch.happened(overview(at(9, 31), GridState.OFF)))
        assertTrue(watch.happened(overview(at(9, 50), GridState.ON)))
    }

    @Test
    fun `readings that change without the light changing are not events`() {
        val watch = EventWatch()
        watch.happened(overview(at(9), GridState.ON, volts = 228.0, battery = 74))
        assertFalse(watch.happened(overview(at(9, 1), GridState.ON, volts = 231.0, battery = 75)))
        assertFalse(watch.happened(overview(at(9, 30), GridState.ON, volts = 219.0, battery = 80)))
    }

    @Test
    fun `without a station the scheduled switch is the event`() {
        val watch = EventWatch()
        watch.happened(overview(at(9, 59)))
        assertTrue(watch.happened(overview(at(10))))
        assertFalse(watch.happened(overview(at(11))))
        assertTrue(watch.happened(overview(at(12))))
    }

    @Test
    fun `a gap in knowledge does not hide a change`() {
        val watch = EventWatch()
        watch.happened(overview(at(9), GridState.ON))
        // Schedule gone and the station silent: nothing is known for a moment.
        assertFalse(watch.happened(overview(at(9, 5), timeline = null)))
        assertTrue(watch.happened(overview(at(9, 30), GridState.OFF)))
    }

    @Test
    fun `the schedule arriving after start is not an event`() {
        val watch = EventWatch()
        watch.happened(overview(at(9), timeline = null))
        assertFalse(watch.happened(overview(at(9, 1))))
    }

    @Test
    fun `emergency outages starting and ending are events`() {
        val watch = EventWatch()
        watch.happened(overview(at(13), GridState.ON))
        assertTrue(watch.happened(overview(at(13, 5), GridState.ON, emergency = emergency)))
        assertFalse(watch.happened(overview(at(13, 30), GridState.ON, emergency = emergency.copy(confirmedAt = at(13, 30).toEpochMilli()))))
        assertTrue(watch.happened(overview(at(15), GridState.ON)))
    }

    @Test
    fun `a new warning about the neighbours is an event, its expiry is not`() {
        val watch = EventWatch()
        watch.happened(overview(at(17), GridState.ON))
        assertTrue(watch.happened(overview(at(17, 30), GridState.ON, prediction = warning(at(17, 28)))))
        assertFalse(watch.happened(overview(at(17, 31), GridState.ON, prediction = warning(at(17, 28)))))
        assertFalse(watch.happened(overview(at(18, 10), GridState.ON)))
        // Another post later on is news again.
        assertTrue(watch.happened(overview(at(19), GridState.ON, prediction = warning(at(18, 58)))))
    }
}
