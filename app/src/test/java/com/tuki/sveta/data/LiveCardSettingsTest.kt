package com.tuki.sveta.data

import com.tuki.sveta.data.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCardSettingsTest {

    private val now = 1_794_240_000_000L
    private val minute = 60_000L

    @Test
    fun `the button puts the card away for the chosen time only`() {
        val hidden = Settings(liveHideMinutes = 30).liveCardHidden(now)
        assertFalse(hidden.liveCardVisible(now))
        assertFalse(hidden.liveCardVisible(now + 29 * minute))
        assertTrue(hidden.liveCardVisible(now + 30 * minute))
        // The switch itself was not touched.
        assertTrue(hidden.liveCard)
    }

    @Test
    fun `the default is an hour`() {
        assertEquals(now + 60 * minute, Settings().liveCardHidden(now).liveHiddenUntil)
        assertTrue(Settings.LIVE_HIDE_OPTIONS.contains(Settings().liveHideMinutes))
    }

    @Test
    fun `the switch hides for good`() {
        val off = Settings(liveCard = false)
        assertFalse(off.liveCardVisible(now))
        assertFalse(off.liveCardVisible(now + 100 * 24 * 60 * minute))
    }

    @Test
    fun `showing undoes either kind of hiding`() {
        assertTrue(Settings().liveCardHidden(now).liveCardShown().liveCardVisible(now))
        assertTrue(Settings(liveCard = false).liveCardShown().liveCardVisible(now))
        assertTrue(Settings(liveCard = false, liveHiddenUntil = now + minute).liveCardShown().liveCardVisible(now))
    }
}
