package com.tuki.sveta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tuki.sveta.domain.SlotState
import com.tuki.sveta.service.Tone

/** Semantic colours for the power state; fixed rather than dynamic so green/red always mean the same. */
data class ToneColors(val container: Color, val onContainer: Color, val accent: Color)

@Composable
fun Tone.colors(dark: Boolean = isSystemInDarkTheme()): ToneColors = when (this) {
    Tone.ON -> if (dark) ToneColors(Color(0xFF1F4D30), Color(0xFFCDEFD6), Color(0xFF6FD694))
    else ToneColors(Color(0xFFCDEFD6), Color(0xFF06210F), Color(0xFF2E9E5B))

    Tone.OFF -> if (dark) ToneColors(Color(0xFF5C1C16), Color(0xFFFFDAD5), Color(0xFFFF8A7E))
    else ToneColors(Color(0xFFFFDAD5), Color(0xFF410002), Color(0xFFC6453B))

    Tone.WARNING -> if (dark) ToneColors(Color(0xFF5A4300), Color(0xFFFFE3A6), Color(0xFFF2C14E))
    else ToneColors(Color(0xFFFFE3A6), Color(0xFF261A00), Color(0xFFB07A00))

    Tone.UNKNOWN -> ToneColors(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.outline,
    )
}

@Composable
fun SlotState.color(): Color = when (this) {
    SlotState.ON -> Tone.ON.colors().accent
    SlotState.OFF -> Tone.OFF.colors().accent
    SlotState.MAYBE -> Tone.WARNING.colors().accent
}
