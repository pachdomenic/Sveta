package com.tuki.sveta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.dtek.DtekParser
import com.tuki.sveta.service.MonitorService
import com.tuki.sveta.ui.home.HomeScreen
import com.tuki.sveta.ui.onboarding.OnboardingScreen
import com.tuki.sveta.ui.predict.PredictScreen
import com.tuki.sveta.ui.schedule.ScheduleScreen
import com.tuki.sveta.ui.settings.SettingsScreen
import com.tuki.sveta.ui.settings.rememberMonitoringToggle

enum class Tab(val title: String, val label: String, val icon: ImageVector) {
    Home("Свєта", "Зараз", Icons.Rounded.Lightbulb),
    Schedule("Графік", "Графік", Icons.Rounded.CalendarMonth),
    Predict("Прогноз", "Прогноз", Icons.Rounded.Insights),
    Settings("Налаштування", "Опції", Icons.Rounded.Settings),
}

@Composable
fun SvetaRoot(app: AppContainer) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val onMonitoring = rememberMonitoringToggle(app)
    if (settings.onboarded) MainScreen(app, onMonitoring) else OnboardingScreen(app, onMonitoring)
}

@Composable
private fun MainScreen(app: AppContainer, onMonitoring: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val schedule by app.schedule.state.collectAsStateWithLifecycle()
    val serviceRunning by MonitorService.isRunning.collectAsStateWithLifecycle()
    val monitoringActive = settings.monitoring && serviceRunning

    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    // safeDrawing counts the keyboard too, and the keyboard is already handled by imePadding() below;
    // leaving it in here would lift everything by its height a second time.
    val insets = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val keyboardOpen = WindowInsets.isImeVisible
    val direction = LocalLayoutDirection.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = insets.calculateStartPadding(direction), end = insets.calculateEndPadding(direction))
                .imePadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(start = 24.dp, end = 16.dp, top = insets.calculateTopPadding() + 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(tab.title, Modifier.weight(1f), label = "title") { title ->
                        Text(title, style = MaterialTheme.typography.headlineLargeEmphasized)
                    }
                    if (tab != Tab.Settings) {
                        AssistChip(onClick = { tab = Tab.Settings }, label = { Text(DtekParser.groupTitle(schedule.payloadFor(settings.dtekRegion), settings.group)) })
                    }
                }

                // Room for the floating toolbar so the last card can scroll clear of it. While typing the
                // toolbar is hidden and every line above the keyboard is precious.
                val contentPadding = PaddingValues(top = 4.dp, bottom = if (keyboardOpen) 16.dp else insets.calculateBottomPadding() + 104.dp)
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut() },
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.Home -> HomeScreen(
                            app = app,
                            contentPadding = contentPadding,
                            monitoringActive = monitoringActive,
                            onEnableMonitoring = { onMonitoring(true) },
                            onOpenSettings = { tab = Tab.Settings },
                            onOpenSchedule = { tab = Tab.Schedule },
                        )
                        Tab.Schedule -> ScheduleScreen(app, contentPadding)
                        Tab.Predict -> PredictScreen(app, contentPadding)
                        Tab.Settings -> SettingsScreen(app, contentPadding, monitoringActive, onMonitoring)
                    }
                }
            }

            // Above the keyboard it would sit right on top of the field being typed in.
            AnimatedVisibility(
                visible = !keyboardOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier.padding(bottom = insets.calculateBottomPadding() + 16.dp),
                ) {
                    Tab.entries.forEach { item ->
                        val selected = item == tab
                        ToggleButton(
                            checked = selected,
                            onCheckedChange = { tab = item },
                            modifier = Modifier.padding(horizontal = 2.dp).semantics { role = Role.Tab },
                        ) {
                            Icon(item.icon, contentDescription = if (selected) null else item.title, modifier = Modifier.size(22.dp))
                            AnimatedVisibility(selected) {
                                Row {
                                    Spacer(Modifier.width(8.dp))
                                    Text(item.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
