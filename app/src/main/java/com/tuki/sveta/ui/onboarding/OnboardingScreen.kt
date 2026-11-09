package com.tuki.sveta.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.ui.components.Hint
import com.tuki.sveta.ui.settings.DefaultGroups
import com.tuki.sveta.ui.settings.EcoflowForm
import com.tuki.sveta.ui.settings.GroupPicker
import kotlinx.coroutines.launch

private const val STEPS = 3

/** First-run flow: queue, station account, background monitoring. Every step after the first is optional. */
@Composable
fun OnboardingScreen(app: AppContainer, onMonitoring: (Boolean) -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val schedule by app.schedule.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }

    fun finish(monitoring: Boolean) {
        scope.launch { app.settings.update { it.copy(onboarded = true) } }
        if (monitoring) onMonitoring(true)
    }

    BackHandler(enabled = step > 0) { step-- }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            LinearWavyProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 3 * direction } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 * direction } + fadeOut())
                },
                label = "onboardingStep",
            ) { current ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (current) {
                        0 -> {
                            StepHeader(Icons.Rounded.Numbers, "Яка в тебе черга?", "Свєта показує графік ДТЕК Київські регіональні електромережі для твоєї черги.")
                            GroupPicker(
                                groups = schedule.payload?.let(DtekParser::groups)?.takeIf { it.isNotEmpty() } ?: DefaultGroups,
                                selected = settings.group,
                                onSelect = { id -> scope.launch { app.settings.update { it.copy(group = id) } } },
                            )
                            Hint("Не знаєш чергу? Подивись на dtek-krem.com.ua → «Графіки відключень», ввівши адресу. Змінити можна будь-коли в налаштуваннях.")
                        }

                        1 -> {
                            StepHeader(Icons.Rounded.Bolt, "Підключи EcoFlow", "Станція бачить напругу в розетці, тож Свєта знатиме точну мить, коли світло зникло чи з'явилось, і поточний вольтаж.")
                            EcoflowForm(app, settings, showHost = false, onSaved = { step = 2 })
                        }

                        else -> {
                            StepHeader(Icons.Rounded.NotificationsActive, "Жива картка і сповіщення", "Свєта працюватиме у фоні: постійна картка з відліком до наступного перемикання й сповіщення, щойно щось зміниться.")
                            Hint("На Android 16 картка закріплюється як Live Update, а в рядку стану видно вольтаж або таймер.")
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (step) {
                    0 -> {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { step = 1 }) { Text("Далі") }
                    }

                    1 -> {
                        TextButton(onClick = { step = 2 }) { Text(if (settings.ecoflowConfigured) "Далі" else "Пропустити") }
                    }

                    else -> {
                        TextButton(onClick = { finish(monitoring = false) }) { Text("Не зараз") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { finish(monitoring = true) }) { Text("Увімкнути моніторинг") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    Box(
        Modifier.size(88.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Text(title, style = MaterialTheme.typography.displaySmallEmphasized)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
