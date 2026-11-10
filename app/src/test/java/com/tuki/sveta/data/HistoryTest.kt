package com.tuki.sveta.data

import com.tuki.sveta.data.store.History
import com.tuki.sveta.domain.EmergencyKind
import com.tuki.sveta.domain.EmergencyStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `alert state survives a round trip`() {
        val history = History(
            emergency = EmergencyStatus(true, 1_000, 2_000, "Київщина: тривають екстрені відключення", "dtek_ua", 4814),
            emergencyPosts = mapOf(4806L to EmergencyKind.STARTED, 4771L to EmergencyKind.CANCELLED),
            plannedHandled = listOf("1794240000:OFF:AHEAD", "1794240900:OFF:NOW"),
        )
        assertEquals(history, json.decodeFromString(History.serializer(), json.encodeToString(History.serializer(), history)))
    }

    @Test
    fun `a file written before alerts existed still loads`() {
        val old = """{"power":[{"at":1,"on":true}],"seenPosts":{"dtek_ua":4800}}"""
        val history = json.decodeFromString(History.serializer(), old)
        assertEquals(null, history.emergency)
        assertEquals(emptyList<String>(), history.plannedHandled)
        assertEquals(4800L, history.seenPosts["dtek_ua"])
    }
}
