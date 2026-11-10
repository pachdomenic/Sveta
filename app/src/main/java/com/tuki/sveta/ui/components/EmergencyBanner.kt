package com.tuki.sveta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.tuki.sveta.domain.EmergencyStatus
import com.tuki.sveta.domain.Texts
import com.tuki.sveta.service.Tone
import com.tuki.sveta.ui.theme.colors
import java.time.Instant

/** Shown wherever the schedule is, so nobody plans around hours that are not being followed. */
@Composable
fun EmergencyBanner(status: EmergencyStatus, now: Instant, modifier: Modifier = Modifier) {
    val tone = Tone.WARNING.colors()
    SectionCard(modifier, container = tone.container, content = tone.onContainer) {
        SectionTitle("Екстрені відключення", icon = Icons.Rounded.WarningAmber)
        Text("Графік зараз не діє: світло можуть вимкнути й увімкнути будь-коли.", style = MaterialTheme.typography.bodyLarge)
        Text(
            buildString {
                if (status.channel.isNotBlank()) append("@${status.channel}, ")
                append(Texts.past(Instant.ofEpochMilli(status.since), now))
                append(": ")
                append(status.summary)
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
