package com.tuki.sveta.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.tuki.sveta.data.dtek.PowerGroup
import com.tuki.sveta.data.ecoflow.Connection
import com.tuki.sveta.data.store.LiveChip
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.domain.AlertPhase
import com.tuki.sveta.domain.EmergencyStatus
import com.tuki.sveta.domain.PlannedAlert
import com.tuki.sveta.domain.PlannedSwitch
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.service.Alerts
import com.tuki.sveta.service.EventTexts
import com.tuki.sveta.ui.components.ChoiceGroup
import com.tuki.sveta.ui.components.Dot
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.components.SectionCard
import com.tuki.sveta.ui.components.SectionTitle
import com.tuki.sveta.ui.components.SwitchRow
import com.tuki.sveta.service.Tone
import com.tuki.sveta.ui.theme.colors
import kotlinx.coroutines.launch
import java.time.Instant
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
                label = { Text(group.id.removePrefix("GPV"), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

@Composable
fun SettingsScreen(app: AppContainer, contentPadding: PaddingValues, monitoringActive: Boolean, onMonitoring: (Boolean) -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val schedule by app.schedule.state.collectAsStateWithLifecycle()
    val station by app.ecoflow.state.collectAsStateWithLifecycle()
    val emergencySource by app.emergency.source.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun update(transform: (Settings) -> Settings) {
        scope.launch { app.settings.update(transform) }
    }

    // These can change while the user is away in system settings.
    var batteryExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    var liveUpdates by remember { mutableStateOf(app.notifications.liveUpdatesAvailable) }
    var canNotify by remember { mutableStateOf(app.notifications.canPost) }
    var exactAlarms by remember { mutableStateOf(Alerts.exactAlarmsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = context.isIgnoringBatteryOptimizations()
        liveUpdates = app.notifications.liveUpdatesAvailable
        canNotify = app.notifications.canPost
        if (exactAlarms != Alerts.exactAlarmsAllowed(context)) {
            exactAlarms = !exactAlarms
            // The pending alarm was armed under the old permission.
            scope.launch { app.alerts.syncPlanned() }
        }
        onPauseOrDispose { }
    }

    val askNotifications = rememberNotificationPermissionRequest { canNotify = app.notifications.canPost }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item {
            SectionCard {
                SectionTitle("Черга відключень", icon = Icons.Rounded.Numbers)
                Hint("Дізнатись свою чергу можна на dtek-krem.com.ua → «Графіки відключень» за адресою.")
                GroupPicker(
                    groups = schedule.payload?.let(DtekParser::groups)?.takeIf { it.isNotEmpty() } ?: DefaultGroups,
                    selected = settings.group,
                    onSelect = { id -> update { it.copy(group = id) } },
                )
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
                    subtitle = if (monitoringActive) "Працює: жива картка в шторці та миттєві сповіщення" else "Вимкнено: без живої картки, графік та екстрені перевіряються раз на годину",
                    checked = settings.monitoring,
                    onChange = onMonitoring,
                )
                AnimatedVisibility(!canNotify) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Сповіщення заборонені в системі — картки й попереджень не буде.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = askNotifications) { Text("Дозволити сповіщення") }
                    }
                }
                SwitchRow(
                    title = "Світло зникло / з'явилось",
                    subtitle = "Справжній момент за даними EcoFlow",
                    checked = settings.notifyPower,
                    onChange = { on -> update { it.copy(notifyPower = on) } },
                )
                SwitchRow(
                    title = "Вимкнення й увімкнення за графіком",
                    subtitle = "Нагадування про кожне перемикання у графіку ДТЕК, станція не потрібна",
                    checked = settings.notifyPlanned,
                    onChange = { on -> update { it.copy(notifyPlanned = on) } },
                )
                AnimatedVisibility(settings.notifyPlanned) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Попереджати заздалегідь, хв")
                        ChoiceGroup(
                            options = Settings.PLANNED_LEAD_OPTIONS,
                            selected = settings.plannedLeadMinutes,
                            onSelect = { minutes -> update { it.copy(plannedLeadMinutes = minutes) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { minutes -> Text(if (minutes == 0) "ні" else minutes.toString()) }
                        if (!exactAlarms) {
                            Hint("Без дозволу на точні будильники система може показати нагадування із запізненням.")
                            OutlinedButton(onClick = { context.requestExactAlarms() }) { Text("Дозволити точний час") }
                        }
                    }
                }
                SwitchRow(
                    title = "Новий або змінений графік",
                    subtitle = "ДТЕК опублікував графік на завтра, змінив чи скасував відключення",
                    checked = settings.notifySchedule,
                    onChange = { on -> update { it.copy(notifySchedule = on) } },
                )
                SwitchRow(
                    title = "Екстрені відключення",
                    subtitle = "Запровадили або скасували — за дописами @${settings.emergencyChannel}",
                    checked = settings.notifyEmergency,
                    onChange = { on -> update { it.copy(notifyEmergency = on) } },
                )
                emergencySource.error?.let { Text("Канал @${settings.emergencyChannel} недоступний: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SwitchRow("Прогноз за сусідніми лініями", settings.notifyPredict, { on -> update { it.copy(notifyPredict = on) } })

                SwitchRow(
                    title = "Жива картка в шторці",
                    subtitle = if (settings.liveCard) {
                        "Сховати можна й кнопкою «Сховати» на самій картці"
                    } else {
                        "Сховано: моніторинг і сповіщення працюють, у шторці лишається тихий рядок, який можна змахнути"
                    },
                    checked = settings.liveCard,
                    onChange = { on -> update { it.copy(liveCard = on) } },
                    enabled = settings.monitoring,
                )
                if (settings.liveCard) Text(
                    when {
                        Build.VERSION.SDK_INT < 36 -> "Live Updates з'явились в Android 16. На цій версії показується звичайна постійна картка."
                        liveUpdates -> "Live Updates увімкнено: картка закріплена, у рядку стану — вольтаж, а без світла те, що обрано нижче."
                        else -> "Live Updates вимкнено в системі для цього застосунку."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= 36 && settings.liveCard) {
                    Hint("У рядку стану, коли світла немає")
                    ChoiceGroup(
                        options = LiveChip.entries,
                        selected = settings.liveChip,
                        onSelect = { choice -> update { it.copy(liveChip = choice) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { choice ->
                        Text(
                            when (choice) {
                                LiveChip.COUNTDOWN -> "Відлік"
                                LiveChip.BATTERY -> "Заряд"
                                LiveChip.RUNTIME -> "Залишок"
                            }
                        )
                    }
                    Hint(
                        when (settings.liveChip) {
                            LiveChip.COUNTDOWN -> "Час до увімкнення за графіком."
                            LiveChip.BATTERY -> "Заряд EcoFlow у відсотках. Відлік до увімкнення лишається в самій картці."
                            LiveChip.RUNTIME -> "На скільки вистачить EcoFlow за поточного навантаження. Відлік до увімкнення лишається в самій картці."
                        }
                    )
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
                    Hint("Зразки сповіщень:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val now = Instant.now()
                        val sample = EmergencyStatus(true, now.toEpochMilli(), now.toEpochMilli(), "Київська область: за командою Укренерго запроваджені екстрені відключення", "dtek_ua")
                        OutlinedButton(onClick = {
                            val text = EventTexts.emergency(sample, app.overview.current())
                            app.notifications.emergency(text.title, text.text, active = true)
                        }) { Text("Екстрені") }
                        OutlinedButton(onClick = {
                            val text = EventTexts.emergency(sample.copy(active = false, summary = "Київщина: повертаємось до графіків, екстрені відключення скасовано"), app.overview.current())
                            app.notifications.emergency(text.title, text.text, active = false)
                        }) { Text("Скасовано") }
                        OutlinedButton(onClick = {
                            val switch = PlannedSwitch(now.plusSeconds(15 * 60), SwitchKind.OFF, now.plusSeconds(4 * 3600 + 15 * 60))
                            val text = EventTexts.planned(PlannedAlert(switch, AlertPhase.AHEAD, now), now)
                            app.notifications.planned(text.title, text.text, off = true)
                        }) { Text("За графіком") }
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
