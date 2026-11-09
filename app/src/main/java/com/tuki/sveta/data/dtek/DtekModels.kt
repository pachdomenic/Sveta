package com.tuki.sveta.data.dtek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val data: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    /** "dd.MM.yyyy HH:mm" in Kyiv time */
    val update: String = "",
    val today: Long = 0,
)

@Serializable
data class DtekPreset(
    /** group -> weekday "1" (Mon) .. "7" (Sun) -> hour "1".."24" -> cell */
    val data: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    @SerialName("sch_names") val groupNames: Map<String, String> = emptyMap(),
    val updateFact: String = "",
)

data class PowerGroup(val id: String, val title: String)
