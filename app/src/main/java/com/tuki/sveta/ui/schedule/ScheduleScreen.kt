package com.tuki.sveta.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.EmergencyTracker
import com.tuki.sveta.domain.Interval
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.domain.Texts
import com.tuki.sveta.domain.Timeline
import com.tuki.sveta.ui.components.ChoiceGroup
import com.tuki.sveta.ui.components.DayStrip
import com.tuki.sveta.ui.components.Dot
import com.tuki.sveta.ui.components.EmergencyBanner
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.components.SectionCard
import com.tuki.sveta.ui.components.SectionTitle
import com.tuki.sveta.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ScheduleScreen(app: AppContainer, contentPadding: PaddingValues) {
    val state by app.schedule.state.collectAsStateWithLifecycle()
    val timeline by app.overview.timeline.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val history by app.history.history.collectAsStateWithLifecycle()
    val clock = remember {
        flow {
            while (true) {
                emit(Instant.now())
                delay(30_000)
            }
        }
    }
    val now by clock.collectAsStateWithLifecycle(remember { Instant.now() })
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    val days = timeline?.days.orEmpty()
    val day = days.getOrNull(selected) ?: days.firstOrNull()

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        EmergencyTracker.current(history.emergency, now)?.let { emergency ->
            item { EmergencyBanner(emergency, now) }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(DtekParser.groupTitle(state.payloadFor(settings.dtekRegion), settings.group), style = MaterialTheme.typography.headlineSmallEmphasized)
                        Hint(settings.dtekRegion.operator)
                    }
                    if (state.loading) {
                        LoadingIndicator(Modifier.size(48.dp))
                    } else {
                        FilledTonalIconButton(onClick = { scope.launch { app.alerts.refreshSchedule(settings.dtekRegion, settings.group) } }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Оновити графік")
                        }
                    }
                }
                state.payloadFor(settings.dtekRegion)?.fact?.update?.takeIf { it.isNotBlank() }?.let { Hint("ДТЕК оновив графік: $it") }
                state.checkedAt?.let { Hint("Востаннє перевірено: ${Texts.past(it, now)}") }
                state.error?.let { Text("Не вдалося оновити: $it", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (day == null) {
            item {
                SectionCard {
                    if (state.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator()
                            Spacer(Modifier.width(12.dp))
                            Text("Завантажую…")
                        }
                    } else {
                        Text("Графіка для цієї черги поки немає.")
                    }
                }
            }
            return@LazyColumn
        }

        if (days.size > 1) {
            item {
                ChoiceGroup(days.indices.toList(), days.indexOf(day), { selected = it }, Modifier.fillMaxWidth()) { index ->
                    Text(Texts.dayName(days[index], now))
                }
            }
        }

        item { DayCard(day, now) }

        item {
            SectionCard {
                SectionTitle("По годинах")
                Timeline(listOf(day)).intervals.forEach { IntervalRow(it) }
            }
        }

        item {
            SectionCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                Legend(SlotState.ON, "Світло є")
                Legend(SlotState.OFF, "Світла немає")
                Legend(SlotState.MAYBE, "Можливе відключення")
            }
        }
    }
}

@Composable
private fun DayCard(day: DaySchedule, now: Instant) {
    SectionCard {
        val zoned = now.atZone(KyivZone)
        val fraction = (zoned.toLocalTime().toSecondOfDay() / 86_400f).takeIf { zoned.toLocalDate() == day.date }
        Text(
            if (day.offMinutes == 0) "Без відключень" else "Без світла ${Texts.minutes(day.offMinutes)}",
            style = MaterialTheme.typography.headlineMediumEmphasized,
        )
        DayStrip(day, nowFraction = fraction)
        if (!day.factual) Hint("ДТЕК ще не опублікував графік на цей день. Показано тижневий шаблон можливих відключень.")
    }
}

@Composable
private fun IntervalRow(interval: Interval) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(interval.state.color())
        Spacer(Modifier.width(12.dp))
        Text(
            Texts.range(interval.start, interval.end),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(132.dp),
        )
        Text(
            when (interval.state) {
                SlotState.ON -> "є"
                SlotState.OFF -> "немає"
                SlotState.MAYBE -> "можливо"
            },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(Texts.duration(interval.duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Legend(state: SlotState, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(state.color())
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
