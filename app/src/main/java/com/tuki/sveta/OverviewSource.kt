package com.tuki.sveta

import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.ScheduleRepository
import com.tuki.sveta.data.ecoflow.EcoflowRepository
import com.tuki.sveta.data.store.SettingsStore
import com.tuki.sveta.data.telegram.TelegramRepository
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Single place that folds the schedule, the station and the predictions into an [Overview]. */
class OverviewSource(
    private val schedule: ScheduleRepository,
    private val ecoflow: EcoflowRepository,
    private val telegram: TelegramRepository,
    settings: SettingsStore,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(clock.withZone(KyivZone)))
            delay(30_000)
        }
    }.distinctUntilChanged()

    /**
     * Today and tomorrow for the selected queue; rebuilt when the data, the region, the queue or the date
     * changes. Empty while the loaded schedule is still the previous region's.
     */
    val timeline: StateFlow<Timeline?> = combine(
        schedule.state.map { it.region to it.payload }.distinctUntilChanged(),
        settings.settings.map { it.dtekRegion to it.group }.distinctUntilChanged(),
        today,
    ) { (loaded, payload), (region, group), date ->
        payload?.takeIf { loaded == region }
            ?.let { DtekParser.days(it, group, date, count = 2) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::Timeline)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun current(): Overview {
        val station = ecoflow.state.value
        return Overview(
            now = clock.instant(),
            grid = station.grid.state,
            assumed = station.grid.assumed,
            volts = station.grid.volts,
            gridSince = station.grid.since,
            timeline = timeline.value,
            prediction = telegram.prediction.value,
            batteryPercent = station.snapshot.batteryPercent,
            chargeRemainMinutes = station.snapshot.chargeRemainMinutes,
            dischargeRemainMinutes = station.snapshot.dischargeRemainMinutes,
            inputWatts = station.snapshot.totalInWatts,
            outputWatts = station.snapshot.totalOutWatts,
        )
    }

    /** Re-emits on every input change and at least every [tick] so relative times stay fresh. */
    fun flow(tick: Duration): Flow<Overview> {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(tick.toMillis())
            }
        }
        return combine(ecoflow.state, timeline, telegram.prediction, ticker) { _, _, _, _ -> current() }
    }
}
