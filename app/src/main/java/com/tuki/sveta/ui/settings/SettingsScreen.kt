package com.tuki.sveta.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.BuildConfig
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.data.dtek.DtekRegion
import com.tuki.sveta.data.dtek.PowerGroup
import com.tuki.sveta.data.dtek.ScheduleState
import com.tuki.sveta.data.ecoflow.Connection
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.ui.components.Dot
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.components.SectionCard
import com.tuki.sveta.ui.components.SectionTitle
import com.tuki.sveta.ui.components.SwitchRow
import com.tuki.sveta.service.Tone
import com.tuki.sveta.ui.theme.colors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Queues exist even before the first download, so the picker never shows up empty. */
val DefaultGroups: List<PowerGroup> = (1..6).flatMap { a -> (1..2).map { b -> PowerGroup("GPV$a.$b", "Черга $a.$b") } }

@Composable
fun GroupPicker(groups: List<PowerGroup>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            FilterChip(
                selected = group.id == selected,
                onClick = { onSelect(group.id) },
                label = { Text(group.short, style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

@Composable
fun RegionPicker(selected: DtekRegion, onSelect: (DtekRegion) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DtekRegion.entries.forEach { region ->
            FilterChip(
                selected = region == selected,
                onClick = { if (region != selected) onSelect(region) },
                label = { Text(region.title, style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

/** Region and queue together, as shown in onboarding and in settings. */
@Composable
fun RegionAndGroupPicker(app: AppContainer, settings: Settings, schedule: ScheduleState, modifier: Modifier = Modifier) {
    val region = settings.dtekRegion
    val payload = schedule.payloadFor(region)
    val groups = remember(payload) { payload?.let(DtekParser::groups)?.takeIf { it.isNotEmpty() } ?: DefaultGroups }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // On the app scope: the queue check that follows the download must outlive this screen.
        RegionPicker(region, onSelect = { picked -> app.scope.launch { app.selectRegion(picked) } })
        Hint("${region.operator}. Дізнатись свою чергу можна на ${region.site} → «Графіки відключень» за адресою.")
        GroupPicker(
            groups = groups,
            selected = settings.group,
            onSelect = { id -> app.scope.launch { app.settings.update { it.copy(group = id) } } },
        )
    }
}

@Composable
fun SettingsScreen(app: AppContainer, contentPadding: PaddingValues, monitoringActive: Boolean, onMonitoring: (Boolean) -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val schedule by app.schedule.state.collectAsStateWithLifecycle()
    val station by app.ecoflow.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun update(transform: (Settings) -> Settings) {
        scope.launch { app.settings.update(transform) }
    }

    // These can change while the user is away in system settings.
    var batteryExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    var liveUpdates by remember { mutableStateOf(app.notifications.liveUpdatesAvailable) }
    var canNotify by remember { mutableStateOf(app.notifications.canPost) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = context.isIgnoringBatteryOptimizations()
        liveUpdates = app.notifications.liveUpdatesAvailable
        canNotify = app.notifications.canPost
        onPauseOrDispose { }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item {
            SectionCard {
                SectionTitle("Регіон і черга", icon = Icons.Rounded.Numbers)
                RegionAndGroupPicker(app, settings, schedule)
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("EcoFlow", Modifier.weight(1f), icon = Icons.Rounded.Bolt)
                    val (label, color) = when (val c = station.connection) {
                        Connection.Connected -> "підключено" to Tone.ON.colors().accent
                        Connection.Connecting -> "підключення…" to MaterialTheme.colorScheme.outline
                        is Connection.Failed -> (if (c.auth) "невірний логін" else "немає зв'язку") to MaterialTheme.colorScheme.error
                        Connection.NotConfigured -> "не налаштовано" to MaterialTheme.colorScheme.outline
                        Connection.Idle -> "призупинено" to MaterialTheme.colorScheme.outline
                    }
                    Dot(color)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
                (station.connection as? Connection.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                EcoflowForm(app, settings)
            }
        }

        item {
            SectionCard {
                SectionTitle("Визначення світла", icon = Icons.Rounded.Sensors)
                var threshold by remember(settings.voltageThreshold) { mutableFloatStateOf(settings.voltageThreshold.toFloat()) }
                Text("Світло є, якщо на вході понад ${threshold.roundToInt()} В", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 90f..210f,
                    steps = 11,
                    onValueChangeFinished = { update { it.copy(voltageThreshold = threshold.roundToInt()) } },
                )
                SwitchRow(
                    title = "Станція офлайн = світла немає",
                    subtitle = "Увімкни, якщо роутер не живиться від EcoFlow і станція зникає з мережі разом зі світлом.",
                    checked = settings.offlineMeansOutage,
                    onChange = { on -> update { it.copy(offlineMeansOutage = on) } },
                )
            }
        }

        item {
            SectionCard {
                SectionTitle("Сповіщення", icon = Icons.Rounded.Notifications)
                SwitchRow(
                    title = "Фоновий моніторинг",
                    subtitle = if (monitoringActive) "Працює: жива картка в шторці та сповіщення" else "Вимкнено: дані оновлюються лише у відкритому застосунку",
                    checked = settings.monitoring,
                    onChange = onMonitoring,
                )
                AnimatedVisibility(settings.monitoring && !canNotify) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Сповіщення заборонені в системі — картки й попереджень не буде.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { context.openPromotedNotificationSettings() }) { Text("Відкрити налаштування") }
                    }
                }
                SwitchRow("Світло зникло / з'явилось", settings.notifyPower, { on -> update { it.copy(notifyPower = on) } })
                SwitchRow("Прогноз за сусідніми лініями", settings.notifyPredict, { on -> update { it.copy(notifyPredict = on) } })
                SwitchRow("Зміни графіка ДТЕК", settings.notifySchedule, { on -> update { it.copy(notifySchedule = on) } })

                Text(
                    when {
                        Build.VERSION.SDK_INT < 36 -> "Live Updates з'явились в Android 16. На цій версії показується звичайна постійна картка."
                        liveUpdates -> "Live Updates увімкнено: картка закріплена, у рядку стану — вольтаж або відлік."
                        else -> "Live Updates вимкнено в системі для цього застосунку."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= 36) {
                    OutlinedButton(onClick = { context.openPromotedNotificationSettings() }) { Text("Налаштування Live Updates") }
                }
            }
        }

        item {
            SectionCard {
                SectionTitle("Робота у фоні", icon = Icons.Rounded.BatterySaver)
                if (batteryExempt) {
                    Hint("Оптимізацію батареї вимкнено — система не присиплятиме моніторинг.")
                } else {
                    Text("Система може присипляти застосунок і сповіщення запізнюватимуться. Дозволь роботу без обмежень.")
                    FilledTonalButton(onClick = { context.requestIgnoreBatteryOptimizations() }) { Text("Дозволити") }
                }
            }
        }

        item { Diagnostics(app, raw = station.raw) }
    }
}

@Composable
private fun Diagnostics(app: AppContainer, raw: Map<String, String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Діагностика", Modifier.weight(1f), icon = Icons.Rounded.BugReport)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Згорнути" else "Показати") }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (BuildConfig.DEBUG) {
                    Hint("Імітація для перевірки сповіщень (лише debug-збірка):")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.ecoflow.simulateVoltage(0.0) }) { Text("Зникло") }
                        OutlinedButton(onClick = { app.ecoflow.simulateVoltage(229.0) }) { Text("З'явилось") }
                    }
                }
                val interesting = raw.filterKeys { it.startsWith("inv.") || it.startsWith("pd.watts") || it.startsWith("ems.") && it.endsWith("RemainTime") || it.endsWith(".soc") }
                if (interesting.isEmpty()) {
                    Hint("Станція ще не надіслала жодного параметра. Якщо підключено, а тут порожньо — перевір серійний номер.")
                } else {
                    Hint("Сирі параметри від станції (${raw.size} усього). Вольтаж мережі — inv.acInVol у мілівольтах.")
                    interesting.toSortedMap().forEach { (key, value) ->
                        Row {
                            Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Hint("Свєта ${BuildConfig.VERSION_NAME}")
            }
        }
    }
}
