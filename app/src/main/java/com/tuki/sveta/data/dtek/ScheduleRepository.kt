package com.tuki.sveta.data.dtek

import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.KyivZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class ScheduleState(
    /** Whose schedule [payload] is; null until the first load. */
    val region: DtekRegion? = null,
    val payload: DtekPayload? = null,
    /** Last time the mirror was reached, whether or not anything changed. */
    val checkedAt: Instant? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    /** The payload, unless it still belongs to the region the user has just switched away from. */
    fun payloadFor(region: DtekRegion): DtekPayload? = payload.takeIf { this.region == region }
}

/** Published days for a group that differ from what we had before a refresh. */
data class ScheduleChange(
    val changed: List<DaySchedule>,
    val added: List<DaySchedule>,
    /** The subset of [changed] that had outages before and has none now. */
    val cancelled: List<DaySchedule> = emptyList(),
) {
    val isEmpty: Boolean get() = changed.isEmpty() && added.isEmpty()
}

/** Holds the schedule of one region at a time; every region keeps its own cache file and etag. */
class ScheduleRepository(
    private val source: DtekSource,
    private val cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    private fun cacheFile(region: DtekRegion) = File(cacheDir, "${region.id}.json")
    private fun etagFile(region: DtekRegion) = File(cacheDir, "${region.id}.etag")

    /** Shows what is cached for [region]; a schedule held for another region is dropped from the state. */
    suspend fun loadCache(region: DtekRegion) = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current.region == region && current.payload != null) return@withContext
        adoptLegacyCache()
        val file = cacheFile(region)
        val cached = if (file.exists()) {
            runCatching { DtekParser.parse(file.readText()) }
                .onFailure { file.delete(); etagFile(region).delete() }
                .getOrNull()
        } else {
            null
        }
        val checkedAt = cached?.let { Instant.ofEpochMilli(file.lastModified()) }
        _state.value = if (current.region == region) {
            current.copy(payload = cached, checkedAt = checkedAt ?: current.checkedAt)
        } else {
            ScheduleState(region = region, payload = cached, checkedAt = checkedAt)
        }
    }

    /**
     * Pulls the latest schedule of [region]. Returns what changed for [group] among days from today
     * onwards, or null when there was nothing to compare against (first load, the region was just
     * switched, fetch failure, not modified).
     */
    suspend fun refresh(region: DtekRegion, group: String): ScheduleChange? = mutex.withLock {
        val switched = _state.value.region.let { it != null && it != region }
        loadCache(region)
        val before = _state.value.payload
        _state.value = _state.value.copy(loading = true)
        try {
            val etagFile = etagFile(region)
            val etag = if (before != null && etagFile.exists()) withContext(Dispatchers.IO) { etagFile.readText() } else null
            when (val result = source.fetch(region, etag)) {
                DtekFetch.NotModified -> {
                    _state.value = _state.value.copy(checkedAt = clock.instant(), error = null, loading = false)
                    null
                }
                is DtekFetch.Body -> {
                    // Callers are often on the main thread; the payload is 60–300 KB of nested maps.
                    val fresh = withContext(Dispatchers.Default) { DtekParser.parse(result.text) }
                    withContext(Dispatchers.IO) {
                        cacheDir.mkdirs()
                        cacheFile(region).writeText(result.text)
                        if (result.etag != null) etagFile.writeText(result.etag) else etagFile.delete()
                    }
                    _state.value = ScheduleState(region = region, payload = fresh, checkedAt = clock.instant())
                    // The cache of a region the user comes back to can be days old; measured against it
                    // the whole current schedule would be announced as a change.
                    if (switched) null else before?.let { diff(it, fresh, group, LocalDate.now(clock.withZone(KyivZone))) }
                }
            }
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(loading = false)
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName, loading = false)
            null
        }
    }

    /** Before regions existed the only cache was Kyiv region's, stored as dtek.json. */
    private fun adoptLegacyCache() {
        for ((legacy, current) in listOf("dtek.json" to cacheFile(DtekRegion.KYIV_REGION), "dtek.etag" to etagFile(DtekRegion.KYIV_REGION))) {
            val old = File(cacheDir, legacy)
            if (old.exists() && (current.exists() || !old.renameTo(current))) old.delete()
        }
    }

    companion object {
        fun diff(old: DtekPayload, new: DtekPayload, group: String, today: LocalDate): ScheduleChange {
            val was = DtekParser.factDays(old, group).associateBy { it.date }
            val now = DtekParser.factDays(new, group).filter { !it.date.isBefore(today) }
            val changed = now.filter { day -> was[day.date]?.let { it.slots != day.slots } == true }
            return ScheduleChange(
                changed = changed,
                added = now.filter { it.date !in was },
                cancelled = changed.filter { it.offMinutes == 0 && was.getValue(it.date).offMinutes > 0 },
            )
        }
    }
}
