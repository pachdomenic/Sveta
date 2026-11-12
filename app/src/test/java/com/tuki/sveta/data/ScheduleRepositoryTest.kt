package com.tuki.sveta.data

import com.tuki.sveta.data.dtek.DtekFetch
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.dtek.DtekRegion.KYIV_REGION
import com.tuki.sveta.data.dtek.DtekRegion.ODESA
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
import java.io.File
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

    private class FakeSource(var next: (DtekRegion) -> DtekFetch) : DtekSource {
        val etags = ArrayList<String?>()
        val regions = ArrayList<DtekRegion>()
        override suspend fun fetch(region: DtekRegion, etag: String?): DtekFetch { regions += region; etags += etag; return next(region) }
    }

    @Test
    fun `first load reports no change and caches`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        assertNull(repo.refresh(KYIV_REGION, "GPV3.1"))
        assertNotNull(repo.state.value.payload)

        // A new instance starts from the cache and revalidates with the stored etag.
        val second = FakeSource { DtekFetch.NotModified }
        val repo2 = ScheduleRepository(second, tmp.root, clock)
        assertNull(repo2.refresh(KYIV_REGION, "GPV3.1"))
        assertEquals(listOf<String?>("v1"), second.etags)
        assertNotNull(repo2.state.value.payload)
    }

    @Test
    fun `changed day is reported after a refresh`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh(KYIV_REGION, "GPV3.1")

        source.next = { DtekFetch.Body(edited("GPV3.1", "1794261600", 9, "no"), "v2") }
        val change = checkNotNull(repo.refresh(KYIV_REGION, "GPV3.1"))
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
        repo.refresh(KYIV_REGION, "GPV3.1")
        source.next = { throw IOException("boom") }
        assertNull(repo.refresh(KYIV_REGION, "GPV3.1"))
        assertEquals("boom", repo.state.value.error)
        assertNotNull(repo.state.value.payload)
    }

    @Test
    fun `each region is fetched and cached on its own`() = runTest {
        val odesa = edited("GPV3.1", "1794261600", 9, "no")
        val source = FakeSource { region -> if (region == ODESA) DtekFetch.Body(odesa, "o1") else DtekFetch.Body(snapshot, "k1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh(KYIV_REGION, "GPV3.1")
        repo.refresh(ODESA, "GPV3.1")
        assertEquals(ODESA, repo.state.value.region)
        assertEquals(DtekParser.parse(odesa), repo.state.value.payloadFor(ODESA))
        assertNull(repo.state.value.payloadFor(KYIV_REGION))

        // Going back revalidates Kyiv region's own copy, not Odesa's.
        source.next = { DtekFetch.NotModified }
        repo.refresh(KYIV_REGION, "GPV3.1")
        assertEquals(listOf(KYIV_REGION, ODESA, KYIV_REGION), source.regions)
        assertEquals(listOf(null, null, "k1"), source.etags)
        assertEquals(DtekParser.parse(snapshot), repo.state.value.payloadFor(KYIV_REGION))
    }

    @Test
    fun `switching region is not reported as a schedule change`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh(ODESA, "GPV3.1")
        repo.refresh(KYIV_REGION, "GPV3.1")

        // Odesa's cache is now behind what the mirror serves, yet coming back to it is not news...
        source.next = { DtekFetch.Body(edited("GPV3.1", "1794261600", 9, "no"), "v2") }
        assertNull(repo.refresh(ODESA, "GPV3.1"))
        // ...while a change that arrives once we are there is.
        source.next = { DtekFetch.Body(edited("GPV3.1", "1794261600", 10, "no"), "v3") }
        assertEquals(listOf(nov9.plusDays(1)), checkNotNull(repo.refresh(ODESA, "GPV3.1")).changed.map { it.date })
    }

    @Test
    fun `failed fetch after a switch does not leave the previous region on screen`() = runTest {
        val source = FakeSource { DtekFetch.Body(snapshot, "v1") }
        val repo = ScheduleRepository(source, tmp.root, clock)
        repo.refresh(KYIV_REGION, "GPV3.1")
        source.next = { throw IOException("offline") }
        assertNull(repo.refresh(ODESA, "GPV3.1"))
        assertEquals(ODESA, repo.state.value.region)
        assertNull(repo.state.value.payload)
        assertEquals("offline", repo.state.value.error)
    }

    @Test
    fun `cache from before regions is adopted as kyiv region`() = runTest {
        File(tmp.root, "dtek.json").writeText(snapshot)
        File(tmp.root, "dtek.etag").writeText("old")
        val source = FakeSource { DtekFetch.NotModified }
        val repo = ScheduleRepository(source, tmp.root, clock)

        repo.loadCache(ODESA)
        assertNull(repo.state.value.payload)

        repo.refresh(KYIV_REGION, "GPV3.1")
        assertEquals(listOf<String?>("old"), source.etags)
        assertNotNull(repo.state.value.payloadFor(KYIV_REGION))
        assertEquals(listOf("kyiv-region.etag", "kyiv-region.json"), tmp.root.list()!!.sorted())
    }
}
