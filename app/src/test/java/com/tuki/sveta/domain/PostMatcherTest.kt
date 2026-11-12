package com.tuki.sveta.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMatcherTest {

    private val off = listOf("вимкн", "відключ", "знеструм", "зникло", "без світла")
    private val on = listOf("увімкн", "з'явил", "заживлен", "дали світло")
    private val lisova = Watch("1", "Лісова", listOf("лісов", "Лесная"))
    private val sadova = Watch("2", "Садова", listOf("садов"))

    private fun match(text: String) = PostMatcher.match(text, listOf(lisova, sadova), off, on)
        .associate { it.watch.name to it.kind }

    private val ownChannel = Watch("3", "Польова", emptyList())

    private fun dedicated(text: String) = PostMatcher.match(text, listOf(ownChannel), off, on).map { it.kind }

    @Test
    fun `a line with its own channel needs no keywords`() {
        assertEquals(listOf(SwitchKind.OFF), dedicated("⚡️ Світло зникло о 14:05"))
        assertEquals(listOf(SwitchKind.ON), dedicated("💡 Світло з'явилось"))
        assertEquals(emptyList<SwitchKind>(), dedicated("Доброго ранку! Сьогодні працюємо за графіком."))
    }

    @Test
    fun `in a dedicated channel the first verb is the news`() {
        assertEquals(listOf(SwitchKind.OFF), dedicated("Світло вимкнули. Обіцяють увімкнути о 18:00"))
        assertEquals(listOf(SwitchKind.ON), dedicated("Дали світло! Не було 4 години, вимкнули о 10:00"))
    }

    @Test
    fun `lines with and without keywords can share a channel`() {
        val hits = PostMatcher.match("На Лісовій вимкнули світло", listOf(lisova, sadova, ownChannel), off, on).associate { it.watch.name to it.kind }
        assertEquals(mapOf("Лісова" to SwitchKind.OFF, "Польова" to SwitchKind.OFF), hits)
    }

    @Test
    fun `inflected outage post matches by stem`() {
        assertEquals(mapOf("Лісова" to SwitchKind.OFF), match("⚡️ На Лісовій вимкнули світло"))
    }

    @Test
    fun `restore post is recognised`() {
        assertEquals(mapOf("Садова" to SwitchKind.ON), match("Садова — світло з’явилось!"))
    }

    @Test
    fun `keyword without a power verb is ignored`() {
        assertTrue(match("На Лісовій сьогодні ярмарок").isEmpty())
    }

    @Test
    fun `power verb without a watched keyword is ignored`() {
        assertTrue(match("Вимкнули Центральну та Шкільну").isEmpty())
    }

    @Test
    fun `mixed post assigns the nearest verb to each line`() {
        assertEquals(
            mapOf("Садова" to SwitchKind.ON, "Лісова" to SwitchKind.OFF),
            match("Садову увімкнули, а от Лісову щойно вимкнули"),
        )
    }

    @Test
    fun `matching ignores case apostrophe style and extra whitespace`() {
        assertEquals(mapOf("Лісова" to SwitchKind.OFF), match("ЛІСОВА\n\n  БЕЗ   СВІТЛА"))
        assertEquals(mapOf("Лісова" to SwitchKind.ON), match("лісова: з`явилось"))
    }

    @Test
    fun `any keyword of a watch is enough`() {
        assertEquals(mapOf("Лісова" to SwitchKind.OFF), match("Лесная — знеструмлено"))
    }
}
