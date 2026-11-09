package com.tuki.sveta.data.store

import com.tuki.sveta.domain.SwitchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PowerEvent(
    val at: Long,
    val on: Boolean,
    val volts: Double? = null,
    /** Inferred from the station going offline rather than measured. */
    val assumed: Boolean = false,
)

@Serializable
data class ChannelMatch(
    val at: Long,
    val channel: String,
    val postId: Long,
    val text: String,
    val ruleId: String,
    val ruleName: String,
    val kind: SwitchKind,
)

@Serializable
data class History(
    /** Newest first. */
    val power: List<PowerEvent> = emptyList(),
    /** Newest first. */
    val matches: List<ChannelMatch> = emptyList(),
    /** lag key (rule + direction) -> seconds between the neighbour's post and our own flip. */
    val lags: Map<String, List<Int>> = emptyMap(),
    /** lag key -> predictions that expired without the grid following. */
    val misses: Map<String, Int> = emptyMap(),
    /** channel -> highest post id already processed. */
    val seenPosts: Map<String, Long> = emptyMap(),
)

/** Small append-mostly log kept as one JSON file; lists are capped so it never grows unbounded. */
class HistoryStore(private val file: File, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    private val _history = MutableStateFlow(load())
    val history: StateFlow<History> = _history.asStateFlow()

    fun update(transform: (History) -> History) {
        _history.update { transform(it).capped() }
        val snapshot = _history.value
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // Skip stale snapshots if a newer one is already queued behind us.
                if (snapshot !== _history.value) return@withLock
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(History.serializer(), snapshot))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun load(): History =
        runCatching { json.decodeFromString(History.serializer(), file.readText()) }.getOrDefault(History())

    private fun History.capped() = copy(
        power = power.take(MAX_POWER),
        matches = matches.take(MAX_MATCHES),
        lags = lags.mapValues { it.value.takeLast(MAX_LAGS) },
    )

    private companion object {
        const val MAX_POWER = 200
        const val MAX_MATCHES = 100
        const val MAX_LAGS = 30
    }
}
