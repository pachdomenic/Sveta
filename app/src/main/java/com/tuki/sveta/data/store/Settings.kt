package com.tuki.sveta.data.store

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    /** DTEK queue id, e.g. "GPV3.1". */
    val group: String = "GPV1.1",

    val ecoflowEmail: String = "",
    val ecoflowSerial: String = "",
    val ecoflowHost: String = DEFAULT_ECOFLOW_HOST,
    /** Grid counts as present above this AC input voltage. */
    val voltageThreshold: Int = 150,
    /** Treat the station dropping off the cloud as an outage (router is not on backup power). */
    val offlineMeansOutage: Boolean = false,

    val monitoring: Boolean = false,
    val notifyPower: Boolean = true,
    val notifySchedule: Boolean = true,
    val notifyPredict: Boolean = true,
    /** Emergency outages announced or called off in [emergencyChannel]. */
    val notifyEmergency: Boolean = true,
    /** Public Telegram channel where DTEK announces emergency outages. */
    val emergencyChannel: String = DEFAULT_EMERGENCY_CHANNEL,

    /** Public Telegram channel usernames without "@". */
    val channels: List<String> = emptyList(),
    val rules: List<WatchRule> = emptyList(),
    val offWords: List<String> = DEFAULT_OFF_WORDS,
    val onWords: List<String> = DEFAULT_ON_WORDS,
    /** Default window between a neighbour line going dark and ours, until enough lags are learned. */
    val predictMinMinutes: Int = 5,
    val predictMaxMinutes: Int = 20,
) {
    val ecoflowConfigured: Boolean get() = ecoflowEmail.isNotBlank() && ecoflowSerial.isNotBlank()

    companion object {
        const val DEFAULT_ECOFLOW_HOST = "api.ecoflow.com"
        /** The DTEK group's only official channel; every region's announcements go through it. */
        const val DEFAULT_EMERGENCY_CHANNEL = "dtek_ua"
        val DEFAULT_OFF_WORDS = listOf("вимкн", "відключ", "виключ", "знеструм", "зникло", "без світла", "немає світла", "нема світла", "пропало")
        val DEFAULT_ON_WORDS = listOf("увімкн", "включ", "з'явил", "зʼявил", "заживлен", "є світло", "дали світло", "відновлен")
    }
}

/**
 * A neighbouring line to watch. A channel post triggers the rule when it mentions any of [keywords]
 * together with an outage (or restore) word.
 */
@Serializable
data class WatchRule(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val enabled: Boolean = true,
)
