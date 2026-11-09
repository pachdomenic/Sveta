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
