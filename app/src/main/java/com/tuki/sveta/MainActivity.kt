package com.tuki.sveta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.tuki.sveta.service.MonitorService
import com.tuki.sveta.service.ScheduleRefreshWorker
import com.tuki.sveta.ui.SvetaRoot
import com.tuki.sveta.ui.theme.SvetaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.notifications.ensureChannels()
        ScheduleRefreshWorker.schedule(this)
        setContent {
            SvetaTheme {
                SvetaRoot(container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = container
        // The visible UI keeps its own hold on the station link, so data is live even with monitoring off.
        app.ecoflow.acquire()
        lifecycleScope.launch { app.schedule.refresh(app.settings.settings.value.group) }
        // The system may have killed the service; an open activity is always allowed to bring it back.
        if (app.settings.settings.value.monitoring && !MonitorService.isRunning.value) {
            MonitorService.startIfAllowed(this)
        }
    }

    override fun onStop() {
        container.ecoflow.release()
        super.onStop()
    }
}
