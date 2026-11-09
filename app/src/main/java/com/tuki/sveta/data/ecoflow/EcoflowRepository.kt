package com.tuki.sveta.data.ecoflow

import com.tuki.sveta.data.store.HistoryStore
import com.tuki.sveta.data.store.PowerEvent
import com.tuki.sveta.data.store.SecretStore
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.PowerMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class GridStatus(
    val state: GridState = GridState.UNKNOWN,
    /** When the grid entered [state]; null if we were not watching at the time. */
    val since: Instant? = null,
    val volts: Double? = null,
    val assumed: Boolean = false,
)

/** A confirmed flip of the grid, emitted once per transition while we were watching. */
data class GridTransition(
    val on: Boolean,
    val at: Instant,
    val volts: Double?,
    val assumed: Boolean,
    /** When the state that just ended had begun, if known. */
    val previousSince: Instant? = null,
)

data class StationState(
    val connection: Connection = Connection.Idle,
    val snapshot: StationSnapshot = StationSnapshot(),
    /** What the cloud says about the station's own link; null until asked. */
    val online: Boolean? = null,
    val grid: GridStatus = GridStatus(),
    /** Raw quotas for the diagnostics screen. */
    val raw: Map<String, String> = emptyMap(),
)

/**
 * Owns the cloud connection to the station and derives the grid state from it.
 * Runs while at least one client holds it via [acquire] (the monitor service, or the visible UI).
 */
class EcoflowRepository(
    private val api: EcoflowAuthApi,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val history: HistoryStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow(StationState())
    val state: StateFlow<StationState> = _state.asStateFlow()

    private val _transitions = MutableSharedFlow<GridTransition>(extraBufferCapacity = 8)
    val transitions: SharedFlow<GridTransition> = _transitions.asSharedFlow()

    private val monitor = PowerMonitor()
    private val lock = Any()
    private var holders = 0
    private var job: Job? = null
    private var pendingStop: Job? = null
    /** Bumped to force a fresh login, e.g. after the password was changed. */
    private val restart = MutableStateFlow(0)

    fun acquire() = synchronized(lock) {
        holders++
        pendingStop?.cancel()
        pendingStop = null
        if (job == null) job = scope.launch { run() }
    }

    /**
     * The link is dropped a few seconds after the last holder leaves, so a rotation or a quick hop to
     * system settings does not cost a fresh login.
     */
    fun release() = synchronized(lock) {
        if (--holders > 0) return@synchronized
        holders = 0
        pendingStop = scope.launch {
            delay(RELEASE_GRACE.toMillis())
            synchronized(lock) {
                if (holders == 0) {
                    job?.cancel()
                    job = null
                    // Nobody is watching any more, so the last reading must not pass for the current one.
                    synchronized(monitor) { monitor.reset() }
                    _state.update { it.copy(connection = Connection.Idle, online = null, grid = GridStatus(since = it.grid.since)) }
                }
            }
        }
    }

    fun reconnect() = restart.update { it + 1 }

    /** Debug aid: pretend the station reported this input voltage. */
    fun simulateVoltage(volts: Double) = synchronized(monitor) {
        val now = clock.instant()
        monitor.onVoltage(volts, now.minus(monitor.config.debounce))
        applyMonitor(monitor.onVoltage(volts, now), now)
    }

    private data class Account(val host: String, val email: String, val serial: String, val epoch: Int)

    private suspend fun run() = coroutineScope {
        launch { tick() }
        launch {
            settings.settings.collect { s ->
                monitor.config = monitor.config.copy(
                    thresholdVolts = s.voltageThreshold.toDouble(),
                    offlineMeansOutage = s.offlineMeansOutage,
                )
            }
        }
        combine(settings.settings, restart) { s, epoch ->
            Account(s.ecoflowHost, s.ecoflowEmail, s.ecoflowSerial.trim().uppercase(), epoch)
        }.distinctUntilChanged().collectLatest { account -> connectLoop(account) }
    }

    private suspend fun connectLoop(account: Account) {
        val password = secrets.get(SecretStore.ECOFLOW_PASSWORD)
        if (account.email.isBlank() || account.serial.isBlank() || password.isNullOrEmpty()) {
            _state.update { it.copy(connection = Connection.NotConfigured) }
            // Nothing to do until the account changes; the password lives outside settings, hence reconnect().
            awaitCancellation()
        }
        var attempt = 0
        while (true) {
            _state.update { it.copy(connection = Connection.Connecting) }
            try {
                val session = api.login(account.host, account.email, password)
                val credentials = api.certification(account.host, session)
                session(credentials, session.userId, account.serial) { attempt = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EcoflowAuthException) {
                _state.update { it.copy(connection = Connection.Failed(e.message ?: "auth", auth = true)) }
                // Hammering the login endpoint with a bad password gets accounts rate limited.
                delay(AUTH_RETRY.toMillis())
            } catch (e: Exception) {
                _state.update { it.copy(connection = Connection.Failed(e.message ?: e.javaClass.simpleName, auth = false)) }
                delay(BACKOFF[minOf(attempt++, BACKOFF.lastIndex)].toMillis())
            }
        }
    }

    private suspend fun session(credentials: MqttCredentials, userId: String, serial: String, onConnected: () -> Unit) {
        lateinit var client: EcoflowMqttClient
        client = EcoflowMqttClient(credentials, userId, serial) { topic, payload ->
            if (client.isGetReply(topic)) onGetReply(payload) else onProperty(payload)
        }
        try {
            client.connect()
            onConnected()
            _state.update { it.copy(connection = Connection.Connected) }
            coroutineScope {
                launch {
                    // The station pushes on change; ask explicitly at start and whenever it has been quiet.
                    client.requestLatestQuotas()
                    while (isActive) {
                        delay(QUOTA_CHECK.toMillis())
                        val last = _state.value.snapshot.updatedAt
                        if (last == null || Duration.between(last, clock.instant()) >= QUOTA_SILENCE) {
                            client.requestLatestQuotas()
                        }
                    }
                }
                client.awaitDisconnect()
            }
        } finally {
            client.close()
        }
    }

    private fun onProperty(payload: ByteArray) {
        val params = EcoflowMessages.parseProperty(payload) ?: return
        ingest(params, online = true)
    }

    private fun onGetReply(payload: ByteArray) {
        val reply = EcoflowMessages.parseGetReply(payload) ?: return
        if (reply.online == false) {
            val now = clock.instant()
            _state.update { it.copy(online = false) }
            synchronized(monitor) { applyMonitor(monitor.onOffline(connected = true), now) }
            return
        }
        if (reply.quotas.isNotEmpty()) ingest(reply.quotas, online = reply.online ?: true)
    }

    private fun ingest(params: Map<String, JsonElement>, online: Boolean) {
        val now = clock.instant()
        _state.update { old ->
            old.copy(
                snapshot = EcoflowMessages.merge(old.snapshot, params, now),
                online = online,
                raw = old.raw + params.mapValues { it.value.toString().trim('"') },
            )
        }
        synchronized(monitor) {
            val volts = (params["inv.acInVol"] as? JsonPrimitive)
                ?.content?.toDoubleOrNull()?.let(EcoflowMessages::millivoltsToVolts)
            if (volts != null) {
                applyMonitor(monitor.onVoltage(volts, now), now)
            } else {
                monitor.onAlive(now)
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            delay(1_000)
            val now = clock.instant()
            val connected = _state.value.connection == Connection.Connected
            synchronized(monitor) { applyMonitor(monitor.onTick(now, connected), now) }
        }
    }

    /** Publishes the monitor's view; call with the monitor lock held. */
    private fun applyMonitor(changed: Boolean, now: Instant) {
        val previous = _state.value.grid
        if (!changed) {
            if (previous.volts != monitor.volts || previous.assumed != monitor.assumed) {
                _state.update { it.copy(grid = it.grid.copy(volts = monitor.volts, assumed = monitor.assumed)) }
            }
            return
        }
        val state = monitor.state
        if (state == GridState.UNKNOWN) {
            _state.update { it.copy(grid = GridStatus(GridState.UNKNOWN, previous.since, null, false)) }
            return
        }
        val on = state == GridState.ON
        val recorded = history.history.value.power.firstOrNull()
        val wasBlind = previous.state == GridState.UNKNOWN
        // Regaining sight of the state we already logged (process restart, link hiccup) is not a new event.
        val resumed = wasBlind && recorded?.on == on
        val since = when {
            resumed -> Instant.ofEpochMilli(recorded!!.at)
            // It flipped while we were not looking; the real moment is unknown.
            wasBlind -> null
            else -> now
        }
        _state.update { it.copy(grid = GridStatus(state, since, monitor.volts, monitor.assumed)) }
        if (resumed) return
        history.update { it.copy(power = listOf(PowerEvent(now.toEpochMilli(), on, monitor.volts, monitor.assumed)) + it.power) }
        // The very first reading after install has nothing to be compared with, so it is logged silently.
        if (wasBlind && recorded == null) return
        val previousSince = if (wasBlind) recorded?.at?.let(Instant::ofEpochMilli) else previous.since
        _transitions.tryEmit(GridTransition(on, now, monitor.volts, monitor.assumed, previousSince))
    }

    private companion object {
        val BACKOFF = listOf(2L, 5L, 15L, 30L, 60L).map(Duration::ofSeconds)
        val AUTH_RETRY: Duration = Duration.ofMinutes(10)
        val QUOTA_CHECK: Duration = Duration.ofSeconds(20)
        val QUOTA_SILENCE: Duration = Duration.ofSeconds(60)
        val RELEASE_GRACE: Duration = Duration.ofSeconds(8)
    }
}
