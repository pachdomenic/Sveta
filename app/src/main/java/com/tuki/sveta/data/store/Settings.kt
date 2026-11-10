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
    /** Reminders about the switches in the published schedule. */
    val notifyPlanned: Boolean = true,
    /** How long before a scheduled switch to give a heads-up; 0 leaves only the alert at the switch itself. */
    val plannedLeadMinutes: Int = 15,
    /** Show the live card while monitoring. When off, the service keeps a quiet placeholder instead. */
    val liveCard: Boolean = true,
    /** How long the "Сховати" button on the card puts it away for. */
    val liveHideMinutes: Int = 60,
    /** Epoch millis until which the card stays put away by that button; 0 when it is not. */
    val liveHiddenUntil: Long = 0,
    /** What the status bar chip of the live card shows while the power is out. */
    val liveChip: LiveChip = LiveChip.COUNTDOWN,
    /** Public Telegram channel where DTEK announces emergency outages. */
    val emergencyChannel: String = DEFAULT_EMERGENCY_CHANNEL,

    /** Legacy: channels used to be one list shared by all rules. Emptied by [migrated]; each rule names its own now. */
    val channels: List<String> = emptyList(),
    val rules: List<WatchRule> = emptyList(),
    val offWords: List<String> = DEFAULT_OFF_WORDS,
    val onWords: List<String> = DEFAULT_ON_WORDS,
    /** Default window between a neighbour line going dark and ours, until enough lags are learned. */
    val predictMinMinutes: Int = 5,
    val predictMaxMinutes: Int = 20,
) {
    val ecoflowConfigured: Boolean get() = ecoflowEmail.isNotBlank() && ecoflowSerial.isNotBlank()

    /** The card is neither switched off for good nor put away for a while. */
    fun liveCardVisible(nowMillis: Long): Boolean = liveCard && nowMillis >= liveHiddenUntil

    /** What the "Сховати" button does: away for [liveHideMinutes], then back on its own. */
    fun liveCardHidden(nowMillis: Long): Settings = copy(liveHiddenUntil = nowMillis + liveHideMinutes * 60_000L)

    /** What "Показати картку" does, however the card was hidden. */
    fun liveCardShown(): Settings = copy(liveCard = true, liveHiddenUntil = 0)

    /**
     * Moves the old shared channel list onto the rules. A rule watched every channel, so it becomes one
     * rule per channel; the first keeps the id, and with it the lags already learned.
     */
    fun migrated(): Settings {
        if (channels.isEmpty()) return this
        val perChannel = rules.flatMap { rule ->
            if (rule.channel.isNotBlank()) return@flatMap listOf(rule)
            channels.mapIndexed { index, channel ->
                if (index == 0) rule.copy(channel = channel)
                else rule.copy(id = "${rule.id}@$channel", name = "${rule.name} (@$channel)", channel = channel)
            }
        }
        return copy(channels = emptyList(), rules = perChannel)
    }

    companion object {
        const val DEFAULT_ECOFLOW_HOST = "api.ecoflow.com"
        /** The DTEK group's only official channel; every region's announcements go through it. */
        const val DEFAULT_EMERGENCY_CHANNEL = "dtek_ua"
        val PLANNED_LEAD_OPTIONS = listOf(0, 5, 15, 30, 60)
        val LIVE_HIDE_OPTIONS = listOf(30, 60, 180, 480)
        val DEFAULT_OFF_WORDS = listOf("вимкн", "відключ", "виключ", "знеструм", "зникло", "без світла", "немає світла", "нема світла", "пропало")
        val DEFAULT_ON_WORDS = listOf("увімкн", "включ", "з'явил", "зʼявил", "заживлен", "є світло", "дали світло", "відновлен")
    }
}

/** The one thing that fits into the status bar chip during an outage. */
@Serializable
enum class LiveChip {
    /** Time left until the power is expected back. */
    COUNTDOWN,

    /** Station charge, "74%". */
    BATTERY,

    /** How long the station will last at the current load, "5г10х". */
    RUNTIME,
}

/**
 * A neighbouring line to watch in its own Telegram channel. A post of [channel] triggers the rule when
 * it mentions any of [keywords] together with an outage (or restore) word. With no keywords the channel
 * is taken to be about this line alone, and any post with such a word counts.
 */
@Serializable
data class WatchRule(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val enabled: Boolean = true,
    /** Public channel username without "@". Blank only in rules that predate per-line channels and had none. */
    val channel: String = "",
)
