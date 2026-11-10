package com.tuki.sveta.data

import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.data.store.WatchRule
import com.tuki.sveta.data.telegram.TelegramRepository
import com.tuki.sveta.domain.Watch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRulesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val lisova = WatchRule("a", "Лісова", listOf("лісов"))
    private val sadova = WatchRule("b", "Садова", listOf("садов"), enabled = false)

    @Test
    fun `settings saved with a shared channel list move the channel onto every line`() {
        val old = """{"channels":["svitlo_selo"],"rules":[{"id":"a","name":"Лісова","keywords":["лісов"]},{"id":"b","name":"Садова","keywords":["садов"],"enabled":false}]}"""
        val settings = json.decodeFromString(Settings.serializer(), old).migrated()
        assertTrue(settings.channels.isEmpty())
        assertEquals(listOf(lisova.copy(channel = "svitlo_selo"), sadova.copy(channel = "svitlo_selo")), settings.rules)
    }

    @Test
    fun `a line that watched several channels becomes one line per channel and keeps its id once`() {
        val settings = Settings(channels = listOf("first", "second"), rules = listOf(lisova)).migrated()
        assertEquals(
            listOf(
                lisova.copy(channel = "first"),
                WatchRule("a@second", "Лісова (@second)", listOf("лісов"), channel = "second"),
            ),
            settings.rules,
        )
    }

    @Test
    fun `migration leaves lines that already have a channel alone and runs once`() {
        val own = lisova.copy(channel = "lisova_svitlo")
        val settings = Settings(channels = listOf("old"), rules = listOf(own, sadova)).migrated()
        assertEquals(listOf(own, sadova.copy(channel = "old")), settings.rules)
        assertEquals(settings, settings.migrated())
        assertEquals(settings, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), settings)).migrated())
    }

    @Test
    fun `each channel is read for its own lines only`() {
        val rules = listOf(
            lisova.copy(channel = "lisova_svitlo"),
            WatchRule("c", "Польова", emptyList(), channel = "polova_svitlo"),
            WatchRule("d", "Вишнева", listOf("вишнев"), channel = "lisova_svitlo"),
            sadova.copy(channel = "sadova_svitlo"),
            WatchRule("e", "Без каналу", listOf("щось")),
        )
        assertEquals(
            mapOf(
                "lisova_svitlo" to listOf(Watch("a", "Лісова", listOf("лісов")), Watch("d", "Вишнева", listOf("вишнев"))),
                "polova_svitlo" to listOf(Watch("c", "Польова", emptyList())),
            ),
            TelegramRepository.watchesByChannel(rules),
        )
    }
}
