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

    @Test
    fun `apostrophe style does not matter at all`() {
        for (text in listOf("Світло з'явилося", "Світло з’явилося", "Світло зʼявилося", "Світло з‘явилося", "Світло зявилося")) {
            assertEquals(text, listOf(SwitchKind.ON), dedicated(text))
        }
    }

    @Test
    fun `bot status posts are read by their headline`() {
        assertEquals(listOf(SwitchKind.ON), dedicated("🟢 20:02 Світло з'явилося\n🕓 Його не було 6год 33хв\n🗓️ Наступне планове: 22:00 - 00:00"))
        assertEquals(listOf(SwitchKind.OFF), dedicated("🔴 21:55 Світло зникло\n🕓 Воно було 1год 53хв\n🗓️ Очікуємо за розкладом о 02:00"))
    }

    @Test
    fun `warnings schedules and news in a line channel are not switches`() {
        // Words a user might add on top of the defaults, to show the filter is not just a short list.
        val off = off + listOf("відсутн", "не було", "виключ")
        val on = on + listOf("включ", "відновлен")
        val noise = listOf(
            "⏰ Через 15 хв планове відключення",
            "⚠️Екстрені відключення у Бучанському районі з 10:40\nСвітла може не бути поза графіком.",
            "🚨Увага! З 10:40 у Бучанському районі ДТЕК застосовує екстрені відключення\nСвітла може не бути поза графіком.",
            "Графік відключень на п'ятницю, 13 листопада\n💡Група 4.1\n02:00 - 05:30 (3,5 год.)",
            "Заплановані ремонтні роботи.\n\n🕦 Час початку: 20.11.2026 08:00.\nВ цей час електроенергія буде відсутня.",
            "Частина Київщини може залишатися без світла до 18-20 годин через аварію на лінії 110 кВ.\n\n" +
                "За інформацією ДТЕК, аварійне відключення охопило частину Фастівського, Бучанського та Обухівського районів.",
            "10 енергооб’єктів пошкоджено за тиждень: чому на Київщині тривають відключення світла\n\n" +
                "Наші енергетики чотири доби повертали світло понад 203 тисячам родин. Щодня над відновленням працюють понад 200 бригад.",
            "❓ Чому немає світла?\n❓ Коли увімкнуть електроенергію?\n❓ Чому відключили поза графіком?",
            "Просимо утриматися від їх використання або користуватися ними виключно на власний ризик.",
            "Тому графік працює як черга: щоб подати світло одним споживачам, потрібно знеструмити інших. " +
                "Вимкнення відбувається точно за часом. Увімкнення проводять поетапно.",
            "Будь ласка, не вимикайте потужні прилади хоча б 30 хв",
            "Світло не відключали, це планові роботи на сусідній вулиці",
        )
        for (text in noise) {
            assertEquals(text, emptyList<RuleHit>(), PostMatcher.match(text, listOf(ownChannel), off, on))
        }
        assertEquals(
            listOf(SwitchKind.ON),
            PostMatcher.match("🟢 20:02 Світло з'явилося\n🕓 Його не було 6год 33хв", listOf(ownChannel), off, on).map { it.kind },
        )
    }

    @Test
    fun `a report followed by a forecast is still a report`() {
        assertEquals(listOf(SwitchKind.OFF), dedicated("Світло зникло, можуть увімкнути о 18:00"))
        assertEquals(listOf(SwitchKind.ON), dedicated("Світло з'явилось! Наступне відключення буде о 22:00"))
    }

    @Test
    fun `filters apply to lines named by keywords too`() {
        assertTrue(match("На Лісовій можуть вимкнути світло о 18:00").isEmpty())
        assertTrue(match("Графік відключень для Садової").isEmpty())
        assertEquals(mapOf("Лісова" to SwitchKind.OFF), match("Лісова: світло зникло. Садову вимкнуть о 18:00"))
    }
}
