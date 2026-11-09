package com.tuki.sveta.data.ecoflow

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The same private endpoints the EcoFlow mobile app uses: e-mail login, then a request for per-user MQTT
 * credentials. Unofficial, so every response is validated defensively.
 */
class EcoflowAuthApi(private val http: OkHttpClient) {

    suspend fun login(host: String, email: String, password: String): EcoflowSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
            put("scene", "IOT_APP")
            put("userType", "ECOFLOW")
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://${host.trim()}/auth/login")
            .header("lang", "en_US")
            .post(body)
            .build()
        val data = execute(request, authCall = true)
        val user = data["user"] as? JsonObject ?: throw IOException("EcoFlow login: no user in response")
        EcoflowSession(
            token = data.string("token") ?: throw IOException("EcoFlow login: no token in response"),
            userId = user.string("userId") ?: throw IOException("EcoFlow login: no userId in response"),
            userName = user.string("name").orEmpty(),
        )
    }

    suspend fun certification(host: String, session: EcoflowSession): MqttCredentials = withContext(Dispatchers.IO) {
        val url = "https://${host.trim()}/iot-auth/app/certification".toHttpUrl().newBuilder()
            .addQueryParameter("userId", session.userId)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("lang", "en_US")
            .header("authorization", "Bearer ${session.token}")
            .get()
            .build()
        val data = execute(request, authCall = false)
        MqttCredentials(
            host = data.string("url") ?: throw IOException("EcoFlow certification: no broker url"),
            port = data.string("port")?.toIntOrNull() ?: 8883,
            username = data.string("certificateAccount") ?: throw IOException("EcoFlow certification: no account"),
            password = data.string("certificatePassword") ?: throw IOException("EcoFlow certification: no password"),
        )
    }

    private fun execute(request: Request, authCall: Boolean): JsonObject =
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val message = root?.string("message")
            val ok = response.isSuccessful && message.equals("success", ignoreCase = true)
            if (!ok) {
                val reason = message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                // The API answers 200 with an error message for bad credentials; 401/403 mean a dead token.
                if (authCall && root != null || response.code == 401 || response.code == 403) {
                    throw EcoflowAuthException(reason)
                }
                throw IOException("EcoFlow: $reason")
            }
            root?.get("data") as? JsonObject ?: throw IOException("EcoFlow: empty response")
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
