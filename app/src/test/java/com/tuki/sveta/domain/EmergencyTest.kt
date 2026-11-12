package com.tuki.sveta.domain

import com.tuki.sveta.domain.EmergencyKind.CANCELLED
import com.tuki.sveta.domain.EmergencyKind.STARTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Post texts are copied from @dtek_ua as published in 2024-2026. */
class EmergencyTest {

    private val tail = "\n\nНагадаємо, під час екстрених відключень графіки не діють.\n\n" +
        "▪️В разі змін будемо вас інформувати у нашому телеграм-каналі.\n\n‼️Підписуйтесь та поділіться з родичами і друзями."

    private val started = "‼️Київська область: за командою Укренерго запроваджені екстрені відключення$tail"
    private val cancelled = "‼️Київщина: повертаємось до графіків, екстрені відключення скасовано\n\n▪️У разі змін повідомимо в нашому Telegram-каналі."

    private fun kind(text: String) = EmergencyMatcher.match(text)?.kind

    @Test
    fun `start is recognised for every way the region is named`() {
        assertEquals(STARTED, kind(started))
        assertEquals(STARTED, kind("‼️Київ та Київська область: за командою Укренерго застосовано екстрені відключення$tail"))
        assertEquals(STARTED, kind("‼️Київ, Київська та Дніпропетровська область: за командою Укренерго запроваджені екстрені відключення$tail"))
        assertEquals(STARTED, kind("‼️Київ, Київська область та місто Дніпро: тривають екстрені відключення$tail"))
        assertEquals(
            STARTED,
            kind("‼️Київщина: за командою Укренерго застосовані екстрені відключення у Білоцерківському, Бучанському, Обухівському, Фастівському районах$tail"),
        )
    }

    @Test
    fun `cancellation is recognised`() {
        assertEquals(CANCELLED, kind(cancelled))
        assertEquals(CANCELLED, kind("💡Київ, Київська, Дніпропетровська, Донецька області: екстрені відключення скасовані\n\nПоступово повертаємо світло в усі домівки."))
    }

    @Test
    fun `an update appended to the post overrides its headline`() {
        assertEquals(CANCELLED, kind("$started\n\nUPD: екстрені відключення скасовані"))
        assertEquals(CANCELLED, kind("‼️Київ та Київська область: за командою Укренерго застосовано екстрені відключення$tail\n\nUPD: В Київській області екстрені відключення скасовані"))
    }

    @Test
    fun `an update about the city alone does not cancel the region`() {
        assertEquals(STARTED, kind("‼️Київ та Київська область: за командою Укренерго застосовано екстрені відключення$tail\n\nUPD: у Києві екстрені відключення скасовані"))
    }

    @Test
    fun `other regions and the city of Kyiv are not ours`() {
        assertNull(kind("‼️Київ: за командою Укренерго запроваджені екстрені відключення$tail"))
        assertNull(kind("💡Київ: повертаємось до графіків, екстрені відключення скасовано\n\nДе подивитись графіки?"))
        assertNull(kind("‼️Дніпро: за розпорядженням Укренерго в місті застосовані екстрені відключення$tail\n\nUPD: екстрені відключення скасовано"))
        assertNull(kind("‼️Одеса: екстрені відключення скасовані\n\nСитуація в Одесі та в Одеському районі дозволяє повернутись до графіків стабілізаційних відключень."))
    }

    @Test
    fun `posts that only mention the topic say nothing`() {
        assertNull(kind("‼️Київщина: графіки відключень на 10 листопада\n\n▪️У разі змін повідомимо в нашому Telegram-каналі."))
        assertNull(kind("❓ДТЕК, чому сьогодні то екстрені відключення, то їх скасовують, а потім знову повертають? І так по багато разів на день."))
        assertNull(kind("‼️ Київ: за командою Укренерго екстрені відключення тривають\n\nПоки що скасувати екстрені відключення в столиці не вдається. Графіки наразі не діють."))
    }

    @Test
    fun `a passing mention in an explainer is not an announcement`() {
        val explainer = "Чому зі світлом знову проблеми? Скільки це може тривати?\n\n" +
            "Через пошкодження мережі можуть передавати менше електроенергії. Тому в більшості областей діють погодинні відключення. " +
            "На Київщині сьогодні запровадили екстрені відключення."
        assertNull(kind(explainer))
    }

    @Test
    fun `a section labelled with our region counts in a digest`() {
        val digest = "Ситуація зі світлом станом на ранок\n\nОдещина: діють графіки.\n\nКиївщина: тривають екстрені відключення.\n\nДніпро: екстрені відключення скасовані."
        val notice = checkNotNull(EmergencyMatcher.match(digest))
        assertEquals(STARTED, notice.kind)
        assertEquals("Київщина: тривають екстрені відключення.", notice.summary)
        assertEquals(CANCELLED, kind("Оновлення\n\nУ Київській області екстрені відключення скасовано."))
    }

    @Test
    fun `a district by district report follows its last statement`() {
        val report = "‼️Київщина: ситуація зі світлом\n\nБригади ДТЕК цілодобово працюють в області.\n\n" +
            "Бучанський район: вдалося стабілізувати ситуацію. Екстрені відключення скасовані, переходимо до графіків.\n\n" +
            "Бориспільський та Броварський райони: тривають екстрені відключення."
        assertEquals(STARTED, kind(report))
    }

    @Test
    fun `a body line can carry the news`() {
        val post = "💡Київщина: 170 000 родин знову зі світлом після масованої атаки\n\n" +
            "Через пошкодження на магістральних мережах в частині Бучанського і Фастівського районах області діють екстрені відключення."
        val notice = checkNotNull(EmergencyMatcher.match(post))
        assertEquals(STARTED, notice.kind)
        assertTrue(notice.summary.startsWith("Через пошкодження"))
    }

    @Test
    fun `not cancelled yet means still on`() {
        assertEquals(STARTED, kind("‼️Київщина: екстрені відключення поки не скасовані"))
    }

    @Test
    fun `summary drops the leading emoji`() {
        assertEquals("Київська область: за командою Укренерго запроваджені екстрені відключення", EmergencyMatcher.match(started)?.summary)
    }

    // --- tracker ---

    private val t0: Instant = Instant.parse("2026-11-07T11:13:39Z")
    private fun min(n: Long): Instant = t0.plus(Duration.ofMinutes(n))

    private fun step(status: EmergencyStatus?, seen: Map<Long, EmergencyKind>, now: Instant, vararg posts: Bulletin) =
        EmergencyTracker.step(status, seen, posts.toList(), "dtek_ua", now)

    @Test
    fun `a fresh start is announced once`() {
        val first = step(null, emptyMap(), min(2), Bulletin(4806, t0, started))
        val event = checkNotNull(first.event)
        assertTrue(event.active)
        assertEquals(4806L, event.postId)
        assertEquals(t0.toEpochMilli(), event.since)

        val again = step(first.status, first.seen, min(4), Bulletin(4806, t0, started))
        assertNull(again.event)
        assertEquals(first.status, again.status)
    }

    @Test
    fun `an edit that adds the cancellation is announced as happening now`() {
        val first = step(null, emptyMap(), min(2), Bulletin(4806, t0, started))
        val edited = step(first.status, first.seen, min(95), Bulletin(4806, t0, "$started\n\nUPD: екстрені відключення скасовані"))
        val event = checkNotNull(edited.event)
        assertFalse(event.active)
        assertEquals(min(95).toEpochMilli(), event.since)
        assertNull(EmergencyTracker.current(edited.status, min(96)))
    }

    @Test
    fun `a separate cancellation post ends the emergency`() {
        val first = step(null, emptyMap(), min(2), Bulletin(4806, t0, started))
        val second = step(first.status, first.seen, min(62), Bulletin(4806, t0, started), Bulletin(4810, min(60), cancelled))
        assertFalse(checkNotNull(second.event).active)
    }

    @Test
    fun `a reminder that it continues is not news but keeps it alive`() {
        val first = step(null, emptyMap(), min(2), Bulletin(4806, t0, started))
        val nextDay = t0.plus(Duration.ofHours(21))
        val still = "‼️Київ, Київська область та місто Дніпро: тривають екстрені відключення$tail"
        val second = step(first.status, first.seen, nextDay.plusSeconds(60), Bulletin(4814, nextDay, still))
        assertNull(second.event)
        assertEquals(t0.toEpochMilli(), second.status?.since)
        assertNotNull(EmergencyTracker.current(second.status, nextDay.plus(Duration.ofHours(30))))
    }

    @Test
    fun `old news found on first run is recorded silently`() {
        val later = t0.plus(Duration.ofHours(5))
        val result = step(null, emptyMap(), later, Bulletin(4806, t0, started))
        assertNull(result.event)
        assertNotNull(EmergencyTracker.current(result.status, later))
    }

    @Test
    fun `a cancellation nobody saw the start of is not announced`() {
        val result = step(null, emptyMap(), min(2), Bulletin(4771, t0, cancelled))
        assertNull(result.event)
        assertNull(EmergencyTracker.current(result.status, min(2)))
    }

    @Test
    fun `start and end within one pass cancel out`() {
        val result = step(null, emptyMap(), min(62), Bulletin(4806, t0, started), Bulletin(4810, min(60), cancelled))
        assertNull(result.event)
        assertNull(EmergencyTracker.current(result.status, min(62)))
    }

    @Test
    fun `an unconfirmed emergency goes stale and a later one is new`() {
        val first = step(null, emptyMap(), min(2), Bulletin(4806, t0, started))
        val twoDays = t0.plus(Duration.ofHours(48))
        assertNull(EmergencyTracker.current(first.status, twoDays))

        val again = step(first.status, first.seen, twoDays.plusSeconds(120), Bulletin(4806, t0, started), Bulletin(4900, twoDays, started))
        val event = checkNotNull(again.event)
        assertTrue(event.active)
        assertEquals(twoDays.toEpochMilli(), event.since)
    }

    @Test
    fun `posts older than what is already known do not rewind the status`() {
        val first = step(null, emptyMap(), min(62), Bulletin(4810, min(60), cancelled))
        // The earlier start scrolls into view late (seen map lost, or a wider page).
        val second = step(first.status, emptyMap(), min(64), Bulletin(4806, t0, started), Bulletin(4810, min(60), cancelled))
        assertNull(second.event)
        assertNull(EmergencyTracker.current(second.status, min(64)))
    }
}
