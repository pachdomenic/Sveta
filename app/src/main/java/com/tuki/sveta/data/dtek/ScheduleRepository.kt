package com.tuki.sveta.data.dtek

import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.KyivZone
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
    val payload: DtekPayload? = null,
    /** Last time the mirror was reached, whether or not anything changed. */
    val checkedAt: Instant? = null,
    val error: String? = null,
    val loading: Boolean = false,
)

/** Published days for a group that differ from what we had before a refresh. */
data class ScheduleChange(val changed: List<DaySchedule>, val added: List<DaySchedule>) {
    val isEmpty: Boolean get() = changed.isEmpty() && added.isEmpty()
}

class ScheduleRepository(
    private val source: DtekSource,
    private val cacheDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val cacheFile = File(cacheDir, "dtek.json")
    private val etagFile = File(cacheDir, "dtek.etag")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        if (_state.value.payload != null || !cacheFile.exists()) return@withContext
        runCatching { DtekParser.parse(cacheFile.readText()) }
            .onSuccess { cached -> _state.value = _state.value.copy(payload = cached, checkedAt = Instant.ofEpochMilli(cacheFile.lastModified())) }
            .onFailure { cacheFile.delete(); etagFile.delete() }
    }

    /**
     * Pulls the latest schedule. Returns what changed for [group] among days from today onwards,
     * or null when there was nothing to compare against (first load, fetch failure, not modified).
     */
    suspend fun refresh(group: String): ScheduleChange? = mutex.withLock {
        loadCache()
        val before = _state.value.payload
        _state.value = _state.value.copy(loading = true)
        try {
            val etag = if (before != null && etagFile.exists()) withContext(Dispatchers.IO) { etagFile.readText() } else null
            when (val result = source.fetch(etag)) {
                DtekFetch.NotModified -> {
                    _state.value = _state.value.copy(checkedAt = clock.instant(), error = null, loading = false)
                    null
                }
                is DtekFetch.Body -> {
                    // Callers are often on the main thread; the payload is ~70 KB of nested maps.
                    val fresh = withContext(Dispatchers.Default) { DtekParser.parse(result.text) }
                    withContext(Dispatchers.IO) {
                        cacheDir.mkdirs()
                        cacheFile.writeText(result.text)
                        if (result.etag != null) etagFile.writeText(result.etag) else etagFile.delete()
                    }
                    _state.value = ScheduleState(payload = fresh, checkedAt = clock.instant())
                    before?.let { diff(it, fresh, group, LocalDate.now(clock.withZone(KyivZone))) }
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName, loading = false)
            null
        }
    }

    companion object {
        fun diff(old: DtekPayload, new: DtekPayload, group: String, today: LocalDate): ScheduleChange {
            val was = DtekParser.factDays(old, group).associateBy { it.date }
            val now = DtekParser.factDays(new, group).filter { !it.date.isBefore(today) }
            return ScheduleChange(
                changed = now.filter { day -> was[day.date]?.let { it.slots != day.slots } == true },
                added = now.filter { it.date !in was },
            )
        }
    }
}
