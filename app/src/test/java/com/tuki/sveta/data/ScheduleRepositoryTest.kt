package com.tuki.sveta.data

import com.tuki.sveta.data.dtek.DtekFetch
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekSource
import com.tuki.sveta.data.dtek.ScheduleRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ScheduleRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val snapshot = checkNotNull(javaClass.getResource("/kyiv-region.json")).readText()
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val nov9: LocalDate = LocalDate.of(2026, 11, 9)

    /** Flips one hour of one group on one day in the raw JSON. */
    private fun edited(group: String, dayKey: String, hour: Int, value: String): String {
        val payload = DtekParser.parse(snapshot)
        val data = payload.fact.data.toMutableMap()
        val groups = data.getValue(dayKey).toMutableMap()
        groups[group] = groups.getValue(group) + (hour.toString() to value)
        data[dayKey] = groups
        return DtekParser.encode(payload.copy(fact = payload.fact.copy(data = data)))
    }

    private class FakeSource(var next: () -> DtekFetch) : DtekSource {
        val etags = ArrayList<String?>()
        override suspend fun fetch(etag: String?): DtekFetch { etags += etag; return next() }
    }

    @Test
    fun `first load reports no change and caches`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        assertNull(repo.refresh("GPV3.1"))
        assertNotNull(repo.state.value.payload)

        // A new instance starts from the cache and revalidates with the stored etag.
        val second = FakeSource { DtekFetch.NotModified }
        val repo2 = ScheduleRepository(second, tmp.root, clock)
        assertNull(repo2.refresh("GPV3.1"))
        assertEquals(listOf<String?>("v1"), second.etags)
        assertNotNull(repo2.state.value.payload)
    }

    @Test
    fun `changed day is reported after a refresh`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh("GPV3.1")

        source.next = { DtekFetch.Body(edited("GPV3.1", "1794261600", 9, "no"), "v2") }
        val change = checkNotNull(repo.refresh("GPV3.1"))
        assertEquals(listOf(nov9.plusDays(1)), change.changed.map { it.date })
        assertTrue(change.added.isEmpty())

    }

    @Test
    fun `other groups changing is not our change`() {
        val old = DtekParser.parse(snapshot)
        val new = DtekParser.parse(edited("GPV1.1", "1794261600", 9, "no"))
        assertTrue(ScheduleRepository.diff(old, new, "GPV3.1", nov9).isEmpty)
    }

    @Test
    fun `newly published day is reported as added`() {
        val full = DtekParser.parse(snapshot)
        val onlyToday = full.copy(fact = full.fact.copy(data = full.fact.data.filterKeys { it == "1794175200" }))
        val change = ScheduleRepository.diff(onlyToday, full, "GPV3.1", nov9)
        assertEquals(listOf(nov9.plusDays(1)), change.added.map { it.date })
        assertTrue(change.changed.isEmpty())
    }

    @Test
    fun `a day that loses every outage is reported as cancelled`() {
        val old = DtekParser.parse(snapshot)
        val day = old.fact.data.getValue("1794261600")
        val clear = day + ("GPV3.1" to day.getValue("GPV3.1").mapValues { "yes" })
        val new = old.copy(fact = old.fact.copy(data = old.fact.data + ("1794261600" to clear)))

        val change = ScheduleRepository.diff(old, new, "GPV3.1", nov9)
        assertEquals(listOf(nov9.plusDays(1)), change.changed.map { it.date })
        assertEquals(change.changed, change.cancelled)

        // Losing one hour is a change, not a cancellation.
        val shorter = ScheduleRepository.diff(old, DtekParser.parse(edited("GPV3.1", "1794261600", 1, "yes")), "GPV3.1", nov9)
        assertEquals(1, shorter.changed.size)
        assertTrue(shorter.cancelled.isEmpty())
    }

    @Test
    fun `past days are ignored in the diff`() {
        val old = DtekParser.parse(snapshot)
        val new = DtekParser.parse(edited("GPV3.1", "1794175200", 9, "no"))
        assertTrue(ScheduleRepository.diff(old, new, "GPV3.1", nov9.plusDays(1)).isEmpty)
    }

    @Test
    fun `fetch failure keeps the old schedule and surfaces the error`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh("GPV3.1")
        source.next = { throw IOException("boom") }
        assertNull(repo.refresh("GPV3.1"))
        assertEquals("boom", repo.state.value.error)
        assertNotNull(repo.state.value.payload)
    }
}
