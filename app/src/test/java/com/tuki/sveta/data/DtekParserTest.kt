package com.tuki.sveta.data

import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
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
    fun `queues come from the schedule tables, not from the list of names`() {
        // Shape of the Kyiv and Dnipro files: more names than queues, and a sub-network published only as a preset.
        val hours = (1..24).joinToString(",") { "\"$it\":\"yes\"" }
        val week = (1..7).joinToString(",") { "\"$it\":{$hours}" }
        val other = DtekParser.parse(
            """{"fact":{"data":{"1794175200":{"GPV10.1":{$hours},"GPV2.1":{$hours}}},"today":1794175200},
               "preset":{"data":{"GPV2.1":{$week},"GPV1001.1":{$week}},
                         "sch_names":{"GPV2.1":"Черга 2.1","GPV2.2":"Черга 2.2","GPV10.1":"Черга 10.1","GPV1001.1":"ЦЕК 1.1"}}}"""
        )
        val groups = DtekParser.groups(other)
        assertEquals(listOf("GPV2.1", "GPV10.1", "GPV1001.1"), groups.map { it.id })
        assertEquals(listOf("2.1", "10.1", "ЦЕК 1.1"), groups.map { it.short })
        assertEquals("ЦЕК 1.1", DtekParser.groupTitle(other, "GPV1001.1"))
        assertEquals("Черга 4.2", DtekParser.groupTitle(other, "GPV4.2"))
        assertEquals("Черга 4.2", DtekParser.groupTitle(null, "GPV4.2"))

        // No published day for the sub-network: it gets the weekly template instead of nothing.
        val days = DtekParser.days(other, "GPV1001.1", today = nov9, count = 2)
        assertEquals(2, days.size)
        assertFalse(days.any { it.factual })
    }

    @Test
    fun `empty schedule printed as an array is read as nothing published`() {
        // What dtek-kem.com.ua serves right after midnight, before the day's schedule is out.
        val hours = (1..24).joinToString(",") { "\"$it\":\"maybe\"" }
        val week = (1..7).joinToString(",") { "\"$it\":{$hours}" }
        val empty = DtekParser.parse(
            """{"regionId":"kyiv","fact":{"data":[],"update":"28.10.2026 23:35","today":1793311200},
               "preset":{"data":{"GPV45.1":{$week}},"sch_names":{"GPV45.1":"Черга 45.1"}}}"""
        )
        assertTrue(empty.fact.data.isEmpty())
        assertEquals(listOf("GPV45.1"), DtekParser.groups(empty).map { it.id })
        val day = DtekParser.days(empty, "GPV45.1", today = nov9, count = 1).single()
        assertFalse(day.factual)
        assertTrue(day.slots.all { it == MAYBE })
        assertEquals(empty, DtekParser.parse(DtekParser.encode(empty)))
    }

    @Test
    fun `stored region id resolves, anything else falls back to kyiv region`() {
        assertEquals(DtekRegion.entries.toList(), DtekRegion.entries.map { DtekRegion.byId(it.id) })
        assertEquals(DtekRegion.entries.size, DtekRegion.entries.map { it.id }.toSet().size)
        assertEquals(DtekRegion.KYIV_REGION, DtekRegion.byId("donetsk"))
        assertEquals(DtekRegion.KYIV_REGION, DtekRegion.byId(""))
    }

    @Test
    fun `payload survives a cache round trip`() {
        val again = DtekParser.parse(DtekParser.encode(payload))
        assertEquals(payload, again)
        assertNotNull(again.fact.update)
    }
}
