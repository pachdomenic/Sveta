package com.tuki.sveta.data.ecoflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.IOException
import java.util.UUID

/**
 * One MQTT connection lifetime. Reconnecting is deliberately left to the caller: broker credentials are
 * short-lived, so after a drop the whole login has to be redone anyway.
 */
class EcoflowMqttClient(
    private val credentials: MqttCredentials,
    private val userId: String,
    private val serial: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    private val dataTopic = EcoflowMessages.dataTopic(serial)
    private val getTopic = EcoflowMessages.getTopic(userId, serial)
    private val getReplyTopic = EcoflowMessages.getReplyTopic(userId, serial)

    private val lost = CompletableDeferred<Throwable>()
    private var client: MqttAsyncClient? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        // The broker only authorises ids shaped like the Android app's.
        val clientId = "ANDROID_${UUID.randomUUID().toString().replace("-", "").uppercase()}_$userId"
        val mqtt = MqttAsyncClient(credentials.serverUri, clientId, MemoryPersistence())
        client = mqtt
        mqtt.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                lost.complete(cause ?: IOException("MQTT connection lost"))
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                runCatching { onMessage(topic, message.payload) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            userName = credentials.username
            password = credentials.password.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = false
            keepAliveInterval = 15
            connectionTimeout = 20
        }
        try {
            mqtt.connect(options).waitForCompletion(25_000)
            mqtt.subscribe(arrayOf(dataTopic, getReplyTopic), intArrayOf(1, 1)).waitForCompletion(15_000)
        } catch (e: MqttException) {
            val auth = e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ||
                e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt()
            if (auth) throw EcoflowAuthException("MQTT: not authorised") else throw IOException("MQTT: ${e.message}", e)
        }
    }

    suspend fun requestLatestQuotas() = withContext(Dispatchers.IO) {
        val mqtt = client ?: return@withContext
        runCatching {
            mqtt.publish(getTopic, MqttMessage(EcoflowMessages.latestQuotasRequest().toByteArray()).apply { qos = 1 })
        }
    }

    fun isGetReply(topic: String): Boolean = topic == getReplyTopic

    /** Suspends until the broker drops us, then throws the cause. */
    suspend fun awaitDisconnect(): Nothing = throw lost.await()

    suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        val mqtt = client ?: return@withContext
        client = null
        runCatching { mqtt.disconnectForcibly(1_000, 1_000) }
        runCatching { mqtt.close(true) }
    }
}
