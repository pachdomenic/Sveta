package com.tuki.sveta.data.ecoflow

import java.time.Instant

data class EcoflowSession(val token: String, val userId: String, val userName: String)

data class MqttCredentials(val host: String, val port: Int, val username: String, val password: String) {
    val serverUri: String get() = "ssl://$host:$port"
}

/** Latest known readings of a DELTA Max. Fields stay null until the station reports them. */
data class StationSnapshot(
    val acInVolts: Double? = null,
    val acInWatts: Int? = null,
    val acOutWatts: Int? = null,
    val totalInWatts: Int? = null,
    val totalOutWatts: Int? = null,
    /** Charge of the whole station, extra batteries included, as the station's own screen shows it. */
    val batteryPercent: Int? = null,
    /** Which quota [batteryPercent] came from; a less trusted one never overwrites a better one. */
    val batterySource: SocSource? = null,
    val chargeRemainMinutes: Int? = null,
    val dischargeRemainMinutes: Int? = null,
    val updatedAt: Instant? = null,
)

/**
 * Quotas that carry a state of charge, from least to most trusted. With an extra battery attached,
 * `bmsMaster.soc` is the main pack alone while the others are the combined charge, and they arrive in
 * separate pushes, so taking whichever came last makes the percentage jump back and forth.
 */
enum class SocSource(val key: String) {
    MAIN_PACK("bmsMaster.soc"),
    COMBINED("pd.soc"),
    DISPLAY("ems.lcdShowSoc"),
}

sealed interface Connection {
    data object NotConfigured : Connection
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    data class Failed(val message: String, val auth: Boolean) : Connection
}

class EcoflowAuthException(message: String) : Exception(message)
