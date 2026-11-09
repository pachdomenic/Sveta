package com.tuki.sveta.data.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class TelegramChannelScraper(private val http: OkHttpClient) {

    /** Latest ~20 text posts of a public channel, oldest first. */
    suspend fun latest(channel: String): List<ChannelPost> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://t.me/s/$channel")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "uk,en;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("t.me answered HTTP ${response.code} for $channel")
            val html = response.body.string()
            // Channels without a public preview redirect to the plain t.me/<name> landing page.
            if (response.request.url.encodedPath.startsWith("/s/").not() || !TelegramParser.isChannelPage(html)) {
                throw IOException("Канал @$channel не має публічного перегляду")
            }
            TelegramParser.parse(html, channel)
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
