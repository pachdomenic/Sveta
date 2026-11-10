package com.tuki.sveta.ui.predict

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.store.History
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.data.store.WatchRule
import com.tuki.sveta.data.telegram.ChannelStatus
import com.tuki.sveta.data.telegram.TelegramParser
import com.tuki.sveta.domain.GridState
import com.tuki.sveta.domain.LagWindow
import com.tuki.sveta.domain.SwitchKind
import com.tuki.sveta.domain.Texts
import com.tuki.sveta.domain.lagKey
import com.tuki.sveta.service.Tone
import com.tuki.sveta.ui.components.Dot
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.components.SectionCard
import com.tuki.sveta.ui.components.SectionTitle
import com.tuki.sveta.ui.theme.colors
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun PredictScreen(app: AppContainer, contentPadding: PaddingValues) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val history by app.history.history.collectAsStateWithLifecycle()
    val channels by app.telegram.channels.collectAsStateWithLifecycle()
    val prediction by app.telegram.prediction.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WatchRule?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { app.settings.update(transform) }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item {
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer) {
                SectionTitle("Як це працює", icon = Icons.Rounded.Insights)
                Text(
                    "Лінії однієї черги вимикають не одночасно, а одну за одною. Додай сусідні лінії — кожну зі своїм " +
                        "Telegram-каналом, де пишуть про її відключення. Щойно там напишуть, що сусідів вимкнули, — отримаєш попередження. " +
                        "Після кількох збігів застосунок сам вивчить, через скільки хвилин черга доходить до тебе.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        prediction?.let { p ->
            item {
                val tone = Tone.WARNING.colors()
                SectionCard(container = tone.container, content = tone.onContainer) {
                    SectionTitle(if (p.kind == SwitchKind.OFF) "Активний прогноз: вимкнуть" else "Активний прогноз: увімкнуть", icon = Icons.Rounded.Timer)
                    Text("«${p.trigger.watchName}» · ${Texts.range(p.earliest, p.latest)}", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(p.trigger.text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            SectionCard {
                SectionTitle("Сусідні лінії", icon = Icons.Rounded.Tune)
                if (settings.rules.isEmpty()) {
                    Hint(
                        "У кожної лінії свій канал. Якщо канал лише про неї — більше нічого не треба. Якщо в каналі пишуть про кілька " +
                            "ліній, додай ключові слова, наприклад «лісов, лесн»: вони шукаються як частина слова, тож відмінки не заважають."
                    )
                }
                settings.rules.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        status = channels[rule.channel],
                        history = history,
                        settings = settings,
                        onToggle = { on -> update { s -> s.copy(rules = s.rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) } },
                        onEdit = { editing = rule },
                    )
                }
                FilledTonalButton(onClick = { editing = WatchRule(id = "", name = "", keywords = emptyList(), channel = "") }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Додати лінію")
                }
            }
        }

        item {
            SectionCard {
                SectionTitle("Типова затримка", icon = Icons.Rounded.Timer)
                var range by remember(settings.predictMinMinutes, settings.predictMaxMinutes) {
                    mutableStateOf(settings.predictMinMinutes.toFloat()..settings.predictMaxMinutes.toFloat())
                }
                Text(
                    "${range.start.roundToInt()}–${range.endInclusive.roundToInt()} хв після сусідів",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 1f..60f,
                    onValueChangeFinished = {
                        val min = range.start.roundToInt()
                        val max = maxOf(range.endInclusive.roundToInt(), min + 1)
                        update { it.copy(predictMinMinutes = min, predictMaxMinutes = max) }
                    },
                )
                Hint("Використовується, доки для лінії не набереться ${LagWindow.MIN_SAMPLES} реальних збігів.")
            }
        }

        item {
            SectionCard {
                SectionTitle("Слова-маркери")
                WordsField("Вимкнули", settings.offWords) { words -> update { it.copy(offWords = words) } }
                WordsField("Увімкнули", settings.onWords) { words -> update { it.copy(onWords = words) } }
                TextButton(onClick = { update { it.copy(offWords = Settings.DEFAULT_OFF_WORDS, onWords = Settings.DEFAULT_ON_WORDS) } }) {
                    Text("Повернути типові")
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Останні збіги", Modifier.weight(1f))
                    if (checking) {
                        LoadingIndicator(Modifier.size(40.dp))
                    } else {
                        TextButton(
                            enabled = settings.rules.any { it.enabled && it.channel.isNotBlank() },
                            onClick = {
                                scope.launch {
                                    checking = true
                                    val grid = app.ecoflow.state.value.grid.state
                                    app.telegram.poll(if (grid == GridState.UNKNOWN) null else grid == GridState.ON)
                                    checking = false
                                }
                            },
                        ) { Text("Перевірити зараз") }
                    }
                }
                if (history.matches.isEmpty()) Hint("Поки що жоден допис не збігся з твоїми лініями.")
                history.matches.take(8).forEach { match ->
                    Row {
                        Dot((if (match.kind == SwitchKind.OFF) Tone.OFF else Tone.ON).colors().accent, Modifier.padding(top = 6.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "${match.ruleName} · ${if (match.kind == SwitchKind.OFF) "вимкнули" else "увімкнули"} · ${Texts.past(Instant.ofEpochMilli(match.at), Instant.now())}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("@${match.channel}: ${match.text}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        RuleDialog(
            rule = rule,
            probe = { app.telegram.probe(it) },
            onDismiss = { editing = null },
            onDelete = {
                app.telegram.forgetRule(rule.id)
                update { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id }) }
                editing = null
            },
            onSave = { saved ->
                update { s ->
                    if (saved.id.isEmpty()) s.copy(rules = s.rules + saved.copy(id = UUID.randomUUID().toString()))
                    else s.copy(rules = s.rules.map { if (it.id == saved.id) saved else it })
                }
                // Lags learned for the old keywords or the old channel say nothing about the new ones.
                if (saved.id.isNotEmpty() && (saved.keywords != rule.keywords || saved.channel != rule.channel)) app.telegram.forgetRule(saved.id)
                editing = null
            },
        )
    }
}

@Composable
private fun RuleRow(rule: WatchRule, status: ChannelStatus?, history: History, settings: Settings, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val key = lagKey(rule.id, SwitchKind.OFF)
    val lags = history.lags[key].orEmpty()
    val misses = history.misses[key] ?: 0
    val window = LagWindow.of(lags, Duration.ofMinutes(settings.predictMinMinutes.toLong()), Duration.ofMinutes(settings.predictMaxMinutes.toLong()))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.titleMedium)
            if (rule.channel.isBlank()) {
                Text("Канал не вказано — лінія не відстежується", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Hint("@${rule.channel} · " + if (rule.keywords.isEmpty()) "будь-який допис про світло" else rule.keywords.joinToString(", "))
                when {
                    !rule.enabled -> Unit
                    status?.error != null -> Text(status.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    status?.checkedAt != null -> Text(
                        "Перевірено ${Texts.past(status.checkedAt, Instant.now())}" +
                            status.lastPostAt?.let { " · останній допис ${Texts.past(it, Instant.now())}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                when {
                    window.learned -> "Вивчено: ${Texts.duration(window.min)}–${Texts.duration(window.max)} · збігів ${lags.size}, промахів $misses"
                    lags.isNotEmpty() || misses > 0 -> "Збігів ${lags.size} з ${LagWindow.MIN_SAMPLES} потрібних, промахів $misses"
                    else -> "Ще не спрацьовувало"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Редагувати ${rule.name}") }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun RuleDialog(
    rule: WatchRule,
    probe: suspend (String) -> Result<Int>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (WatchRule) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(rule.name) }
    var channel by rememberSaveable { mutableStateOf(if (rule.channel.isBlank()) "" else "@${rule.channel}") }
    var keywords by rememberSaveable { mutableStateOf(rule.keywords.joinToString(", ")) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val parsed = parseWords(keywords)

    fun submit() {
        val username = TelegramParser.normalizeChannel(channel)
        if (username == null) {
            channelError = "Потрібна назва на кшталт @kanal або посилання t.me/kanal"
            return
        }
        val saved = rule.copy(name = name.trim().ifEmpty { parsed.firstOrNull() ?: "@$username" }, keywords = parsed, channel = username)
        // A channel that is already being read needs no second opinion.
        if (username.equals(rule.channel, ignoreCase = true)) {
            onSave(saved.copy(channel = rule.channel))
            return
        }
        scope.launch {
            busy = true
            channelError = null
            probe(username)
                .onSuccess { onSave(saved) }
                .onFailure { channelError = it.message ?: "Не вдалося відкрити канал" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id.isEmpty()) "Нова лінія" else "Лінія") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Назва") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it; channelError = null },
                    label = { Text("Канал цієї лінії") },
                    placeholder = { Text("@канал або посилання") },
                    singleLine = true,
                    isError = channelError != null,
                    supportingText = { Text(channelError ?: "Лише публічний: t.me/назва має відкриватись без вступу") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    keywords, { keywords = it },
                    label = { Text("Ключові слова (необов'язково)") },
                    supportingText = {
                        Text("Порожньо — спрацює будь-який допис каналу про вимкнення чи ввімкнення. Якщо канал пише про кілька ліній, вкажи частини слів через кому: «лісов» знайде і «Лісова», і «на Лісовій»")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (busy) {
                LoadingIndicator(Modifier.size(40.dp))
            } else {
                Button(enabled = channel.isNotBlank(), onClick = ::submit) { Text("Зберегти") }
            }
        },
        dismissButton = {
            Row {
                if (rule.id.isNotEmpty()) TextButton(onClick = onDelete) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Скасувати") }
            }
        },
    )
}

@Composable
private fun WordsField(label: String, words: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(words.joinToString(", ")) }
    // Re-sync only when the list changed from outside (reset to defaults), never while the user is typing.
    LaunchedEffect(words) {
        if (parseWords(text) != words) text = words.joinToString(", ")
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parseWords(it).takeIf { parsed -> parsed.isNotEmpty() && parsed != words }?.let(onChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parseWords(text: String): List<String> = text.split(',', ';', '\n').map { it.trim() }.filter { it.length >= 2 }.distinct()
