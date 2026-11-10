package com.tuki.sveta.data.dtek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * DTEK distribution networks the mirror republishes. [id] is the mirror's file name and is what gets
 * stored in settings, so it must stay stable.
 *
 * @param operator the company as it names itself
 * @param site where a user can look up their queue by address
 */
enum class DtekRegion(val id: String, val title: String, val operator: String, val site: String) {
    KYIV("kyiv", "Київ", "ДТЕК Київські електромережі", "dtek-kem.com.ua"),
    KYIV_REGION("kyiv-region", "Київська обл.", "ДТЕК Київські регіональні електромережі", "dtek-krem.com.ua"),
    DNIPRO("dnipro", "Дніпропетровська обл.", "ДТЕК Дніпровські електромережі", "dtek-dnem.com.ua"),
    ODESA("odesa", "Одеська обл.", "ДТЕК Одеські електромережі", "dtek-oem.com.ua");

    companion object {
        /** The only region before there was a choice; settings saved back then have no region at all. */
        val Default = KYIV_REGION

        fun byId(id: String): DtekRegion = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Mirror of the `DisconSchedule` object embedded in the DTEK shutdowns page, as republished by
 * https://github.com/Baskerville42/outage-data-ua.
 */
@Serializable
data class DtekPayload(
    val regionId: String = "",
    val lastUpdated: String = "",
    val fact: DtekFact = DtekFact(),
    val preset: DtekPreset = DtekPreset(),
)

@Serializable
data class DtekFact(
    /** unix seconds of local midnight -> group -> hour "1".."24" -> cell */
    @Serializable(with = ScheduleTableSerializer::class)
    val data: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    /** "dd.MM.yyyy HH:mm" in Kyiv time */
    val update: String = "",
    val today: Long = 0,
)

@Serializable
data class DtekPreset(
    /** group -> weekday "1" (Mon) .. "7" (Sun) -> hour "1".."24" -> cell */
    @Serializable(with = ScheduleTableSerializer::class)
    val data: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    @SerialName("sch_names") val groupNames: Map<String, String> = emptyMap(),
    val updateFact: String = "",
)

data class PowerGroup(val id: String, val title: String) {
    /** Chip-sized name: "3.1", or "ЦЕК 1.1" for a sub-network that numbers its queues separately. */
    val short: String get() = title.removePrefix("Черга ").trim()
}

/**
 * The DTEK backend prints an empty table as `[]` rather than `{}` — the Kyiv page does so right after
 * midnight, before the day's schedule is out. Read any such array as "nothing published".
 */
internal object ScheduleTableSerializer : JsonTransformingSerializer<Map<String, Map<String, Map<String, String>>>>(
    MapSerializer(String.serializer(), MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())))
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = element.arraysAsObjects()

    private fun JsonElement.arraysAsObjects(): JsonElement = when (this) {
        is JsonArray -> JsonObject(emptyMap())
        is JsonObject -> JsonObject(mapValues { it.value.arraysAsObjects() })
        else -> this
    }
}
