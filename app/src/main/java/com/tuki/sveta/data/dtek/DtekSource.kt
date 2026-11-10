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
    /** @param etag validator from the previous successful fetch of the same [region], if any */
    suspend fun fetch(region: DtekRegion, etag: String?): DtekFetch
}

/**
 * The DTEK sites sit behind a bot wall that only a real browser passes, so the schedule is read from
 * the outage-data-ua mirror, which scrapes the same pages with a headless browser every few minutes
 * and keeps one file per region.
 */
class MirrorDtekSource(
    private val http: OkHttpClient,
    private val baseUrl: String = MIRROR_URL,
) : DtekSource {

    override suspend fun fetch(region: DtekRegion, etag: String?): DtekFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/${region.id}.json").apply {
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
        const val MIRROR_URL = "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data"
    }
}
