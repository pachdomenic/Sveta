package com.tuki.sveta.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.ecoflow.Connection
import com.tuki.sveta.data.ecoflow.StationState
import com.tuki.sveta.data.store.PowerEvent
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.domain.KyivZone
import com.tuki.sveta.domain.Overview
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Texts
import com.tuki.sveta.service.LiveContent
import com.tuki.sveta.service.LiveContentBuilder
import com.tuki.sveta.service.Tone
import com.tuki.sveta.ui.components.DayStrip
import com.tuki.sveta.ui.components.Dot
import com.tuki.sveta.ui.components.EmergencyBanner
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.components.SectionCard
import com.tuki.sveta.ui.components.SectionTitle
import com.tuki.sveta.ui.theme.colors
import java.time.Duration
import java.time.Instant

@Composable
fun HomeScreen(
    app: AppContainer,
    contentPadding: PaddingValues,
    monitoringActive: Boolean,
    onEnableMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    // Remembered: a fresh flow per recomposition would restart collection every time it emits.
    val overviewFlow = remember(app) { app.overview.flow(Duration.ofSeconds(1)) }
    val overview by overviewFlow.collectAsStateWithLifecycle(remember(app) { app.overview.current() })
    val station by app.ecoflow.state.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val history by app.history.history.collectAsStateWithLifecycle()
    val schedule by app.schedule.state.collectAsStateWithLifecycle()
    val content = LiveContentBuilder.build(overview, settings.liveChip, settings.liveWatts)

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item { Hero(overview, content) }

        overview.emergency?.let { emergency ->
            item { EmergencyBanner(emergency, overview.now) }
        }

        overview.activePrediction?.takeIf { it.kind == SwitchKind.OFF }?.let { p ->
            item {
                val tone = Tone.WARNING.colors()
                SectionCard(container = tone.container, content = tone.onContainer) {
                    SectionTitle("Сусідів уже вимкнули", icon = Icons.Rounded.WarningAmber)
                    Text(
                        "«${p.trigger.watchName}» без світла з ${Texts.clock(p.trigger.at)}. Наша лінія очікується ${Texts.range(p.earliest, p.latest)}.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("@${p.trigger.channel}: ${p.trigger.text}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item { NextSwitchCard(overview, content, loading = schedule.loading && schedule.payload == null, error = schedule.error.takeIf { schedule.payload == null }, onOpenSchedule) }

        item { StationCard(station, settings, onOpenSettings) }

        if (!monitoringActive) {
            item {
                SectionCard(container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer) {
                    SectionTitle("Фоновий моніторинг вимкнено", icon = Icons.Rounded.NotificationsActive)
                    Text("Щоб отримувати сповіщення й бачити живу картку, коли застосунок закрито, увімкни моніторинг.")
                    Button(onClick = onEnableMonitoring) { Text("Увімкнути") }
                }
            }
        }

        if (history.power.isNotEmpty()) {
            item { RecentEvents(history.power.take(6), overview.now) }
        }
    }
}

@Composable
private fun Hero(overview: Overview, content: LiveContent) {
    val tone = content.tone.colors()
    val container by animateColorAsState(tone.container, label = "heroContainer")
    val onContainer by animateColorAsState(tone.onContainer, label = "heroContent")

    Surface(shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = onContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateBadge(content.tone, tone.accent, tone.container)
                Spacer(Modifier.width(16.dp))
                Column {
                    AnimatedContent(heroTitle(overview), label = "heroTitle") { title ->
                        Text(title, style = MaterialTheme.typography.headlineLargeEmphasized)
                    }
                    // With the schedule suspended and nothing measured there is no "since" to speak of.
                    overview.since?.takeIf { overview.measured || overview.emergency == null }?.let { since ->
                        val word = if (overview.measured) "вже" else "за графіком уже"
                        Text("$word ${Texts.duration(Duration.between(since, overview.now))}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            val volts = overview.volts
            if (overview.measured && overview.effectiveOn == true && volts != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Texts.volts(volts).removeSuffix(" В"),
                        style = MaterialTheme.typography.displayLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(" В", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Text(voltageVerdict(volts), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
    }
}

private fun heroTitle(o: Overview): String = when {
    !o.measured && o.emergency != null -> "Графік не діє"
    !o.measured && o.effectiveOn == null -> "Немає даних"
    !o.measured && o.effectiveOn == true -> "Має бути світло"
    !o.measured -> "Має бути темно"
    o.effectiveOn == true -> "Світло є"
    o.assumed -> "Схоже, темно"
    else -> "Світла немає"
}

private fun voltageVerdict(volts: Double): String = when {
    volts < 198 -> "низька напруга"
    volts > 253 -> "висока напруга"
    else -> "напруга в нормі"
}

/** Expressive shape that slowly spins while the grid is up and sits still when it is not. */
@Composable
private fun StateBadge(tone: Tone, accent: Color, onAccent: Color) {
    val polygon = when (tone) {
        Tone.ON -> MaterialShapes.Sunny
        Tone.OFF -> MaterialShapes.Cookie9Sided
        Tone.WARNING -> MaterialShapes.SoftBurst
        Tone.UNKNOWN -> MaterialShapes.Clover4Leaf
    }
    val spin by rememberInfiniteTransition(label = "badge").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (tone == Tone.WARNING) 6_000 else 24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val rotation = if (tone == Tone.ON || tone == Tone.WARNING) spin else 0f
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).rotate(rotation).clip(polygon.toShape()).background(accent))
        Icon(
            imageVector = when (tone) {
                Tone.ON -> Icons.Rounded.Bolt
                Tone.OFF -> Icons.Rounded.FlashOff
                Tone.WARNING -> Icons.Rounded.WarningAmber
                Tone.UNKNOWN -> Icons.Rounded.QuestionMark
            },
            contentDescription = null,
            tint = onAccent,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun NextSwitchCard(overview: Overview, content: LiveContent, loading: Boolean, error: String?, onOpenSchedule: () -> Unit) {
    SectionCard {
        val next = overview.nextSwitch
        when {
            overview.timeline == null && loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text("Завантажую графік ДТЕК…")
            }

            overview.timeline == null -> {
                SectionTitle("Графік недоступний")
                Hint(error ?: "Не вдалося отримати графік. Перевір інтернет.")
            }

            else -> {
                val goingOff = overview.effectiveOn == true
                if (overview.emergency != null) Hint("Це за графіком, а він зараз не виконується.")
                Text(
                    when {
                        next == null && overview.expectation?.offSchedule == true && goingOff -> "Відключення запізнюється"
                        next == null && goingOff -> "Відключень не видно"
                        next == null -> "Позапланове відключення"
                        goingOff -> "Вимкнуть ${Texts.at(next, overview.now)}"
                        else -> "Увімкнуть ${Texts.at(next, overview.now)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (next != null) {
                    Text(
                        "через ${Texts.duration(Duration.between(overview.now, next))}",
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(fontFeatureSettings = "tnum"),
                    )
                    val progress by animateFloatAsState(overview.progress ?: 0f, label = "progress")
                    val accent = content.tone.colors().accent
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(14.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.25f),
                    )
                } else {
                    Hint(
                        when {
                            overview.expectation?.offSchedule == true && goingOff -> "За графіком уже мало бути темно. Можуть вимкнути будь-якої миті."
                            goingOff -> "На сьогодні й завтра опублікований графік без відключень для твоєї черги."
                            else -> "Зараз за графіком світло має бути. Коли повернуть — невідомо."
                        }
                    )
                }

                overview.timeline.days.firstOrNull()?.let { today ->
                    val zoned = overview.now.atZone(KyivZone)
                    val fraction = (zoned.toLocalTime().toSecondOfDay() / 86_400f).takeIf { zoned.toLocalDate() == today.date }
                    Spacer(Modifier.height(4.dp))
                    DayStrip(today, nowFraction = fraction)
                    if (!today.factual) Hint("Графік на сьогодні ще не опубліковано — показано можливі відключення.")
                }
                FilledTonalButton(onClick = onOpenSchedule) { Text("Увесь графік") }
            }
        }
    }
}

@Composable
private fun StationCard(station: StationState, settings: Settings, onOpenSettings: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("EcoFlow", Modifier.weight(1f), icon = Icons.Rounded.Bolt)
            ConnectionLabel(station)
        }
        when (val connection = station.connection) {
            Connection.NotConfigured -> {
                Hint("Підключи станцію, щоб бачити реальний момент вимкнення й вольтаж у мережі.")
                Button(onClick = onOpenSettings) { Text("Підключити") }
            }

            is Connection.Failed -> {
                Text(
                    if (connection.auth) "Не вдалося увійти: ${connection.message}" else "Помилка зв'язку: ${connection.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                if (connection.auth) FilledTonalButton(onClick = onOpenSettings) { Text("Перевірити логін") }
                StationReadings(station)
            }

            else -> StationReadings(station)
        }
    }
}

@Composable
private fun StationReadings(station: StationState) {
    val s = station.snapshot
    val percent = s.batteryPercent
    if (percent == null) {
        if (station.connection == Connection.Connected || station.connection == Connection.Connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Hint(if (station.online == false) "Станція не в мережі — чекаю, поки з'явиться." else "Чекаю на дані від станції…")
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            val level by animateFloatAsState(percent / 100f, label = "battery")
            CircularWavyProgressIndicator(progress = { level }, modifier = Modifier.size(84.dp))
            Text("$percent%", style = MaterialTheme.typography.titleLargeEmphasized)
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Reading("Вхід", s.totalInWatts?.let { "$it Вт" })
            Reading("Вихід", s.totalOutWatts?.let { "$it Вт" })
            val charging = (s.totalInWatts ?: 0) > (s.totalOutWatts ?: 0)
            when {
                charging && percent < 100 && (s.chargeRemainMinutes ?: 0) > 0 -> Reading("До повного", Texts.minutes(s.chargeRemainMinutes!!))
                !charging && (s.dischargeRemainMinutes ?: 0) > 0 -> Reading("Вистачить на", Texts.minutes(s.dischargeRemainMinutes!!))
            }
        }
    }
    if (station.online == false) Hint("Станція зараз не в мережі, показано останні відомі дані.")
}

@Composable
private fun Reading(label: String, value: String?) {
    Row {
        Text("$label  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMediumEmphasized.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun ConnectionLabel(station: StationState) {
    val (text, color) = when (station.connection) {
        Connection.Connected -> if (station.online == false) "станція офлайн" to Tone.WARNING.colors().accent else "на зв'язку" to Tone.ON.colors().accent
        Connection.Connecting -> "підключення…" to MaterialTheme.colorScheme.outline
        is Connection.Failed -> "помилка" to MaterialTheme.colorScheme.error
        Connection.NotConfigured -> "не налаштовано" to MaterialTheme.colorScheme.outline
        Connection.Idle -> "призупинено" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecentEvents(events: List<PowerEvent>, now: Instant) {
    SectionCard {
        SectionTitle("Останні події", icon = Icons.Rounded.History)
        events.forEach { event ->
            val at = Instant.ofEpochMilli(event.at)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot((if (event.on) Tone.ON else Tone.OFF).colors().accent)
                Spacer(Modifier.width(10.dp))
                Text(
                    buildString {
                        append(if (event.on) "З'явилось" else if (event.assumed) "Зникло (ймовірно)" else "Зникло")
                        event.volts?.takeIf { event.on }?.let { append(" · ${Texts.volts(it)}") }
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Texts.past(at, now),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
