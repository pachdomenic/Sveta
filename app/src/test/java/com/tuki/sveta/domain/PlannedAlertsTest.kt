package com.tuki.sveta.domain

import com.tuki.sveta.domain.AlertPhase.AHEAD
import com.tuki.sveta.domain.AlertPhase.NOW
import com.tuki.sveta.domain.SwitchKind.OFF
import com.tuki.sveta.domain.SwitchKind.ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class PlannedAlertsTest {

    private val nov9: LocalDate = LocalDate.of(2026, 11, 9)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(KyivZone).toInstant()

    /** @param off half-hour slot ranges without power */
    private fun day(date: LocalDate, vararg off: IntRange, factual: Boolean = true, maybe: IntRange? = null) = DaySchedule(
        date,
        List(48) { i ->
            when {
                off.any { i in it } -> SlotState.OFF
                maybe != null && i in maybe -> SlotState.MAYBE
                else -> SlotState.ON
            }
        },
        factual,
    )

    /** 9 Nov: 10:00-12:00 and 18:00-22:00. */
    private val today = day(nov9, 20..23, 36..43)
    private val lead = Duration.ofMinutes(15)

    @Test
    fun `each outage gives a switch off and a switch on`() {
        assertEquals(
            listOf(
                PlannedSwitch(at(9, 10), OFF, until = at(9, 12)),
                PlannedSwitch(at(9, 12), ON, until = at(9, 18)),
                PlannedSwitch(at(9, 18), OFF, until = at(9, 22)),
                PlannedSwitch(at(9, 22), ON, until = null),
            ),
            PlannedAlerts.switches(listOf(today)),
        )
    }

    @Test
    fun `an outage running over midnight is one outage`() {
        val switches = PlannedAlerts.switches(listOf(day(nov9, 44..47), day(nov9.plusDays(1), 0..3)))
        assertEquals(
            listOf(PlannedSwitch(at(9, 22), OFF, until = at(10, 2)), PlannedSwitch(at(10, 2), ON, until = null)),
            switches,
        )
    }

    @Test
    fun `an outage at the edge of the published days has no switch there`() {
        // Dark from 22:00 with tomorrow unknown: nobody can say when it ends.
        assertEquals(listOf(PlannedSwitch(at(9, 22), OFF, until = null)), PlannedAlerts.switches(listOf(day(nov9, 44..47))))
        // Dark from midnight with yesterday unknown: that is not a switch off we can vouch for.
        assertEquals(listOf(PlannedSwitch(at(9, 2), ON, until = null)), PlannedAlerts.switches(listOf(day(nov9, 0..3))))
    }

    @Test
    fun `template days and possible outages are ignored`() {
        assertTrue(PlannedAlerts.switches(listOf(day(nov9, 20..23, factual = false))).isEmpty())
        assertTrue(PlannedAlerts.switches(listOf(day(nov9, maybe = 20..23))).isEmpty())
    }

    @Test
    fun `every switch gets a heads-up and an alert at the moment`() {
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), lead)
        assertEquals(
            listOf(
                at(9, 9, 45) to AHEAD, at(9, 10) to NOW,
                at(9, 11, 45) to AHEAD, at(9, 12) to NOW,
                at(9, 17, 45) to AHEAD, at(9, 18) to NOW,
                at(9, 21, 45) to AHEAD, at(9, 22) to NOW,
            ),
            alerts.map { it.fireAt to it.phase },
        )
    }

    @Test
    fun `zero lead leaves only the alerts at the moment`() {
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), Duration.ZERO)
        assertEquals(4, alerts.size)
        assertTrue(alerts.all { it.phase == NOW })
    }

    @Test
    fun `no heads-up for a stretch shorter than the lead`() {
        // 10:00-10:30 only: with an hour's lead the restore heads-up would come before the outage itself.
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(day(nov9, 20..20))), Duration.ofMinutes(60))
        assertEquals(listOf(at(9, 9) to AHEAD, at(9, 10) to NOW, at(9, 10, 30) to NOW), alerts.map { it.fireAt to it.phase })
    }

    @Test
    fun `due and next follow the clock`() {
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), lead)
        assertTrue(PlannedAlerts.due(alerts, emptySet(), at(9, 9, 44)).isEmpty())
        assertEquals(at(9, 9, 45), PlannedAlerts.next(alerts, emptySet(), at(9, 9, 44)))

        val due = PlannedAlerts.due(alerts, emptySet(), at(9, 9, 45))
        assertEquals(listOf(AHEAD), due.map { it.phase })
        assertEquals(OFF, due.single().switch.kind)

        val handled = setOf(due.single().key)
        assertTrue(PlannedAlerts.due(alerts, handled, at(9, 9, 50)).isEmpty())
        assertEquals(at(9, 10), PlannedAlerts.next(alerts, handled, at(9, 9, 50)))
    }

    @Test
    fun `a late heads-up is still shown until the switch, a late moment alert only briefly`() {
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), lead)
        // Phone was off until 09:58.
        assertEquals(listOf(AHEAD), PlannedAlerts.due(alerts, emptySet(), at(9, 9, 58)).map { it.phase })
        // At 10:03 the heads-up is pointless, the switch itself is recent enough.
        assertEquals(listOf(NOW), PlannedAlerts.due(alerts, emptySet(), at(9, 10, 3)).map { it.phase })
        // At 10:20 neither is.
        assertTrue(PlannedAlerts.due(alerts, emptySet(), at(9, 10, 20)).isEmpty())
    }

    @Test
    fun `nothing left means no alarm`() {
        val alerts = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), lead)
        assertNull(PlannedAlerts.next(alerts, emptySet(), at(9, 22)))
    }

    @Test
    fun `an outage that moves gets new alerts`() {
        val before = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(today)), lead)
        val handled = PlannedAlerts.due(before, emptySet(), at(9, 17, 50)).map { it.key }.toSet()
        // DTEK pushes the evening outage half an hour later.
        val after = PlannedAlerts.alerts(PlannedAlerts.switches(listOf(day(nov9, 20..23, 37..43))), lead)
        assertEquals(at(9, 18, 15), PlannedAlerts.next(after, handled, at(9, 17, 50)))
    }
}
