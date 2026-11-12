package com.tuki.sveta.live

import com.tuki.sveta.data.dtek.DtekFetch
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.dtek.MirrorDtekSource
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.data.telegram.TelegramChannelScraper
import com.tuki.sveta.domain.Bulletin
import com.tuki.sveta.domain.EmergencyMatcher
import com.tuki.sveta.domain.EmergencyTracker
import com.tuki.sveta.domain.KyivZone
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Hits the real endpoints to catch upstream format changes. Skipped in normal runs; enable with
 * `./gradlew testDebugUnitTest -Psveta.live=true --tests "*LiveSourcesTest"`.
 */
class LiveSourcesTest {

    private val http = OkHttpClient()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("sveta.live") == "true")

    @Test
    fun `dtek mirror serves a fresh schedule for every queue of every region`() = runBlocking {
        val today = LocalDate.now(KyivZone)
        for (region in DtekRegion.entries) {
            val body = MirrorDtekSource(http).fetch(region, etag = null) as DtekFetch.Body
            val payload = DtekParser.parse(body.text)
            assertEquals(region.id, payload.regionId)
            val groups = DtekParser.groups(payload)
            assertTrue("${region.id}: only ${groups.size} queues", groups.size >= 12)

            for (group in groups) {
                val days = DtekParser.days(payload, group.id, today, count = 2)
                assertTrue("${region.id}: no days for ${group.id}", days.isNotEmpty())
            }
            // The mirror re-scrapes every few minutes; a day-old file means its pipeline is stuck.
            val age = Duration.between(Instant.parse(payload.lastUpdated), Instant.now())
            assertTrue("${region.id}: mirror is stale: $age", age < Duration.ofHours(24))
            val factual = groups.count { DtekParser.factDays(payload, it.id).isNotEmpty() }
            println("DTEK ${region.id} ok: updated ${payload.fact.update}, ${groups.size} queues, $factual with a published day")
        }
    }

    @Test
    fun `conditional request is honoured`() = runBlocking {
        val source = MirrorDtekSource(http)
        val first = source.fetch(DtekRegion.Default, null) as DtekFetch.Body
        assumeTrue("no etag from the mirror", first.etag != null)
        val second = source.fetch(DtekRegion.Default, first.etag)
        // Either unchanged, or the file was republished in between; both are valid.
        assertTrue(second is DtekFetch.NotModified || second is DtekFetch.Body)
        println("ETag revalidation: ${second::class.simpleName}")
    }

    @Test
    fun `public telegram preview is parsed`() = runBlocking {
        val posts = TelegramChannelScraper(http).latest("dtek_ua")
        assertTrue("expected several posts, got ${posts.size}", posts.size >= 3)
        assertEquals(posts.sortedBy { it.id }, posts)
        assertTrue(posts.all { it.text.isNotBlank() && it.at.isBefore(Instant.now().plusSeconds(300)) })
        println("Telegram ok: ${posts.size} posts, latest #${posts.last().id} at ${posts.last().at}")
    }

    @Test
    fun `emergency announcements channel is readable`() = runBlocking {
        val posts = TelegramChannelScraper(http).latest(Settings.DEFAULT_EMERGENCY_CHANNEL)
        assertTrue("expected several posts, got ${posts.size}", posts.size >= 3)
        // Nothing to assert about the verdicts (it depends on the day), but the output shows at a glance
        // whether the matcher still understands how DTEK words things.
        for (post in posts) {
            val notice = EmergencyMatcher.match(post.text)
            println("#${post.id} ${post.at} ${notice?.kind ?: "-"}: ${post.text.lineSequence().first().take(90)}")
        }
        val step = EmergencyTracker.step(null, emptyMap(), posts.map { Bulletin(it.id, it.at, it.text) }, Settings.DEFAULT_EMERGENCY_CHANNEL, Instant.now())
        println("Emergency now: ${EmergencyTracker.current(step.status, Instant.now())}")
    }

    @Test
    fun `missing channel is reported instead of returning nothing`() = runBlocking {
        val result = runCatching { TelegramChannelScraper(http).latest("sveta_no_such_channel_zz9") }
        assertTrue("expected a failure, got ${result.getOrNull()}", result.isFailure)
        println("Missing channel -> ${result.exceptionOrNull()?.message}")
    }
}
