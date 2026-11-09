package com.tuki.sveta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tuki.sveta.domain.DaySchedule
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.domain.Texts
import com.tuki.sveta.ui.theme.color

/**
 * A day as a 48-segment bar. Segments of the same state are drawn as one rounded run; possible outages
 * are paler than definite ones. [nowFraction] (0..1 through the day) draws the current-time marker.
 */
@Composable
fun DayStrip(day: DaySchedule, modifier: Modifier = Modifier, nowFraction: Float? = null) {
    val on = SlotState.ON.color()
    val off = SlotState.OFF.color()
    val maybe = SlotState.MAYBE.color()
    val marker = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface

    Column(modifier.semantics { contentDescription = "Графік на добу: ${Texts.outages(day)}" }) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val gap = 2.dp.toPx()
            val slot = size.width / DaySchedule.SLOTS_PER_DAY
            val barTop = 6.dp.toPx()
            val barHeight = size.height - barTop * 2
            val radius = CornerRadius(6.dp.toPx())

            var start = 0
            while (start < day.slots.size) {
                val state = day.slots[start]
                var end = start
                while (end + 1 < day.slots.size && day.slots[end + 1] == state) end++
                val color = when (state) {
                    SlotState.ON -> on.copy(alpha = 0.35f)
                    SlotState.OFF -> off
                    SlotState.MAYBE -> maybe.copy(alpha = 0.55f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(start * slot + gap / 2, barTop),
                    size = Size((end - start + 1) * slot - gap, barHeight),
                    cornerRadius = radius,
                )
                start = end + 1
            }

            if (nowFraction != null) {
                val x = (nowFraction.coerceIn(0f, 1f) * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
                // Halo first so the marker stays visible over any segment colour.
                drawRoundRect(surface, Offset(x - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
                drawRoundRect(marker, Offset(x - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height), CornerRadius(1.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (hour in listOf("00", "06", "12", "18", "24")) {
                Text(hour, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
