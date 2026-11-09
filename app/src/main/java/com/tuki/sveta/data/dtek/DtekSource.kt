package com.tuki.sveta.data.dtek

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface DtekFetch {
    data object NotModified : DtekFetch
    data class Body(val text: String, val etag: String?) : DtekFetch
}

/** Where the raw `DisconSchedule` JSON comes from. */
fun interface DtekSource {
    /** @param etag validator from the previous successful fetch, if any */
    suspend fun fetch(etag: String?): DtekFetch
}

/**
 * dtek-krem.com.ua sits behind a bot wall that only a real browser passes, so the schedule is read from
 * the outage-data-ua mirror, which scrapes the same page with a headless browser every few minutes.
 */
class MirrorDtekSource(
    private val http: OkHttpClient,
    private val url: String = KYIV_REGION_URL,
) : DtekSource {

    override suspend fun fetch(etag: String?): DtekFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            if (etag != null) header("If-None-Match", etag)
        }.build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> DtekFetch.NotModified
                !response.isSuccessful -> throw IOException("DTEK mirror answered HTTP ${response.code}")
                else -> DtekFetch.Body(response.body.string(), response.header("ETag"))
            }
        }
    }

    companion object {
        const val KYIV_REGION_URL =
            "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv-region.json"
    }
}
