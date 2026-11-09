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
    val batteryPercent: Int? = null,
    val chargeRemainMinutes: Int? = null,
    val dischargeRemainMinutes: Int? = null,
    val updatedAt: Instant? = null,
)

sealed interface Connection {
    data object NotConfigured : Connection
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    data class Failed(val message: String, val auth: Boolean) : Connection
}

class EcoflowAuthException(message: String) : Exception(message)
