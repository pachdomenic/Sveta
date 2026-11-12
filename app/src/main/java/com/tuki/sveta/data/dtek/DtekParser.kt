package com.tuki.sveta.data.dtek

import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.domain.SlotState.MAYBE
import com.tuki.sveta.domain.SlotState.OFF
import com.tuki.sveta.domain.SlotState.ON
import com.tuki.sveta.domain.Texts
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

object DtekParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): DtekPayload = json.decodeFromString(DtekPayload.serializer(), text)

    fun encode(payload: DtekPayload): String = json.encodeToString(DtekPayload.serializer(), payload)

    /**
     * Queues that have a schedule of either kind. The name table alone is not enough to go by: Kyiv
     * lists twice as many names as it has queues.
     */
    fun groups(payload: DtekPayload): List<PowerGroup> {
        val ids = (payload.preset.data.keys + payload.fact.data.values.flatMap { it.keys })
            .ifEmpty { payload.preset.groupNames.keys }
        return ids
            .sortedWith(compareBy({ it.groupNumber() }, { it }))
            .map { PowerGroup(it, groupTitle(payload, it)) }
    }

    /** "Черга 3.1", or whatever DTEK calls the queue ("ЦЕК 1.1"); works before anything is downloaded. */
    fun groupTitle(payload: DtekPayload?, group: String): String =
        payload?.preset?.groupNames?.get(group)?.takeIf { it.isNotBlank() } ?: Texts.queue(group)

    /** Published days for [group], oldest first. */
    fun factDays(payload: DtekPayload, group: String): List<DaySchedule> =
        payload.fact.data.mapNotNull { (epoch, groups) ->
            val seconds = epoch.toLongOrNull() ?: return@mapNotNull null
            val hours = groups[group] ?: return@mapNotNull null
            // Keys are local midnight; add a couple of hours so a DST shift cannot push us into the previous day.
            val date = Instant.ofEpochSecond(seconds + 2 * 3600).atZone(KyivZone).toLocalDate()
            DaySchedule(date, hoursToSlots(hours), factual = true)
        }.sortedBy { it.date }

    /** Weekly "possible outages" template for [date], or null when the group has none. */
    fun presetDay(payload: DtekPayload, group: String, date: LocalDate): DaySchedule? {
        val hours = payload.preset.data[group]?.get(date.dayOfWeek.value.toString()) ?: return null
        return DaySchedule(date, hoursToSlots(hours), factual = false)
    }

    /**
     * Days to show starting from [today]: published schedule where it exists, preset otherwise.
     * Stale published days before [today] are dropped.
     */
    fun days(payload: DtekPayload, group: String, today: LocalDate, count: Int = 2): List<DaySchedule> {
        val fact = factDays(payload, group).associateBy { it.date }
        return (0 until count).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            fact[date] ?: presetDay(payload, group, date)
        }
    }

    fun hoursToSlots(hours: Map<String, String>): List<SlotState> {
        val slots = ArrayList<SlotState>(DaySchedule.SLOTS_PER_DAY)
        for (h in 1..24) {
            val (first, second) = cell(hours[h.toString()])
            slots += first
            slots += second
        }
        return slots
    }

    private fun cell(value: String?): Pair<SlotState, SlotState> = when (value) {
        "no" -> OFF to OFF
        "first" -> OFF to ON
        "second" -> ON to OFF
        "maybe" -> MAYBE to MAYBE
        "mfirst" -> MAYBE to ON
        "msecond" -> ON to MAYBE
        else -> ON to ON
    }

    private fun String.groupNumber(): Double = removePrefix("GPV").toDoubleOrNull() ?: Double.MAX_VALUE
}
