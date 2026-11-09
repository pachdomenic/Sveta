package com.tuki.sveta.data

import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.domain.SlotState.MAYBE
import com.tuki.sveta.domain.SlotState.OFF
import com.tuki.sveta.domain.SlotState.ON
import com.tuki.sveta.domain.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import com.tuki.sveta.domain.KyivZone

/** Runs against a real snapshot of the mirror taken on 2026-11-09. */
class DtekParserTest {

    private val payload = DtekParser.parse(
        checkNotNull(javaClass.getResource("/kyiv-region.json")).readText()
    )
    private val nov9: LocalDate = LocalDate.of(2026, 11, 9)

    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).atZone(KyivZone).toInstant()

    @Test
    fun `lists all twelve groups in natural order`() {
        val groups = DtekParser.groups(payload)
        assertEquals(12, groups.size)
        assertEquals("GPV1.1", groups.first().id)
        assertEquals("Черга 1.1", groups.first().title)
        assertEquals("GPV6.2", groups.last().id)
    }

    @Test
    fun `day keys resolve to kyiv calendar dates`() {
        val days = DtekParser.factDays(payload, "GPV3.1")
        assertEquals(listOf(nov9, nov9.plusDays(1)), days.map { it.date })
        assertTrue(days.all { it.factual })
    }

    @Test
    fun `half hour cells are split correctly`() {
        val day = DtekParser.factDays(payload, "GPV3.1").first()
        // 06:00-07:00 is "first": dark for the first half only
        assertEquals(OFF, day.slots[12])
        assertEquals(ON, day.slots[13])
        // 13:00-14:00 is "second": dark for the second half only
        assertEquals(ON, day.slots[26])
        assertEquals(OFF, day.slots[27])
    }

    @Test
    fun `timeline merges outages for the snapshot`() {
        val timeline = Timeline(DtekParser.factDays(payload, "GPV3.1"))
        val off = timeline.intervals.filter { it.state == OFF }.map { it.start to it.end }
        assertEquals(
            listOf(
                at(9, 2, 0) to at(9, 6, 30),
                at(9, 9, 0) to at(9, 10, 30),
                at(9, 13, 30) to at(9, 21, 0),
                at(10, 0, 0) to at(10, 6, 30),
                at(10, 10, 30) to at(10, 17, 0),
                at(10, 20, 0) to at(11, 0, 0),
            ),
            off,
        )
    }

    @Test
    fun `preset fills days without a published schedule`() {
        val days = DtekParser.days(payload, "GPV3.1", today = nov9.plusDays(1), count = 2)
        assertEquals(2, days.size)
        assertTrue(days[0].factual)
        assertFalse(days[1].factual)
        assertTrue(days[1].slots.any { it == MAYBE })
        assertFalse(days[1].slots.any { it == OFF })
    }

    @Test
    fun `unknown group yields nothing`() {
        assertTrue(DtekParser.factDays(payload, "GPV9.9").isEmpty())
        assertEquals(null, DtekParser.presetDay(payload, "GPV9.9", nov9))
    }

    @Test
    fun `payload survives a cache round trip`() {
        val again = DtekParser.parse(DtekParser.encode(payload))
        assertEquals(payload, again)
        assertNotNull(again.fact.update)
    }
}
