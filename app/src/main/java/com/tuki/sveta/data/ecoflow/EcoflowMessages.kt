package com.tuki.sveta.data.ecoflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.random.Random

/** Wire format of the private app MQTT API, as reverse engineered by hassio-ecoflow-cloud. */
object EcoflowMessages {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun dataTopic(serial: String) = "/app/device/property/$serial"
    fun getTopic(userId: String, serial: String) = "/app/$userId/$serial/thing/property/get"
    fun getReplyTopic(userId: String, serial: String) = "/app/$userId/$serial/thing/property/get_reply"

    /** Asks the cloud for every cached quota of the device; answered on the get_reply topic. */
    fun latestQuotasRequest(random: Random = Random.Default): String = buildJsonObject {
        put("from", "Android")
        put("id", (999_900_000 + random.nextInt(10_000, 100_000)).toString())
        put("version", "1.1")
        put("moduleType", 0)
        put("operateType", "latestQuotas")
        putJsonObject("params") {}
    }.toString()

    /** Flat `"module.field" -> value` map from a property push, or null if the payload is something else. */
    fun parseProperty(payload: ByteArray): Map<String, JsonElement>? {
        val root = parseObject(payload) ?: return null
        return (root["params"] as? JsonObject)?.takeIf { it.isNotEmpty() }
    }

    data class QuotaReply(val online: Boolean?, val quotas: Map<String, JsonElement>)

    fun parseGetReply(payload: ByteArray): QuotaReply? {
        val root = parseObject(payload) ?: return null
        if ((root["operateType"] as? JsonPrimitive)?.content != "latestQuotas") return null
        val data = root["data"] as? JsonObject ?: return QuotaReply(online = null, quotas = emptyMap())
        val online = (data["online"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }?.let { it == 1 }
        val quotas = (data["quotaMap"] as? JsonObject).orEmpty()
        return QuotaReply(online, quotas)
    }

    /** Folds new values into [previous]; keys that are absent keep their old value. */
    fun merge(previous: StationSnapshot, params: Map<String, JsonElement>, now: Instant): StationSnapshot {
        fun int(key: String): Int? = (params[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        fun double(key: String): Double? = (params[key] as? JsonPrimitive)?.doubleOrNull
        // The app reports 5999 / negative values while the estimate is not available.
        fun minutes(key: String): Int? = int(key)?.let { if (it < 0 || it > 5000) 0 else it }

        return previous.copy(
            acInVolts = double("inv.acInVol")?.let(::millivoltsToVolts) ?: previous.acInVolts,
            acInWatts = int("inv.inputWatts") ?: previous.acInWatts,
            acOutWatts = int("inv.outputWatts") ?: previous.acOutWatts,
            totalInWatts = int("pd.wattsInSum") ?: previous.totalInWatts,
            totalOutWatts = int("pd.wattsOutSum") ?: previous.totalOutWatts,
            batteryPercent = int("bmsMaster.soc") ?: int("pd.soc") ?: int("ems.lcdShowSoc") ?: previous.batteryPercent,
            chargeRemainMinutes = minutes("ems.chgRemainTime") ?: previous.chargeRemainMinutes,
            dischargeRemainMinutes = minutes("ems.dsgRemainTime") ?: previous.dischargeRemainMinutes,
            updatedAt = now,
        )
    }

    /** `inv.acInVol` is in millivolts; tolerate firmware that would already send volts. */
    fun millivoltsToVolts(raw: Double): Double = if (raw > 1000) raw / 1000.0 else raw

    private fun parseObject(payload: ByteArray): JsonObject? =
        runCatching { json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()
}
