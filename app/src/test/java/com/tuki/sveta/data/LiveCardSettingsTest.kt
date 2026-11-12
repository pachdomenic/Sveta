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
    fun `hiding until the next event has no deadline and ends with the event`() {
        val waiting = Settings(liveHideMinutes = Settings.HIDE_UNTIL_EVENT).liveCardHidden(now)
        assertEquals(Settings.UNTIL_EVENT, waiting.liveHiddenUntil)
        assertFalse(waiting.liveCardVisible(now + 1000 * 24 * 60 * minute))

        val revealed = waiting.liveCardRevealedByEvent()
        assertTrue(revealed.liveCardVisible(now))
        // The choice of "until an event" for the next press stays.
        assertEquals(Settings.HIDE_UNTIL_EVENT, revealed.liveHideMinutes)
    }

    @Test
    fun `an event does not cut short hiding for a set time or bring back a switched off card`() {
        val timed = Settings(liveHideMinutes = 180).liveCardHidden(now)
        assertEquals(timed, timed.liveCardRevealedByEvent())
        val off = Settings(liveCard = false)
        assertEquals(off, off.liveCardRevealedByEvent())
    }

    @Test
    fun `showing undoes either kind of hiding`() {
        assertTrue(Settings(liveHideMinutes = Settings.HIDE_UNTIL_EVENT).liveCardHidden(now).liveCardShown().liveCardVisible(now))
        assertTrue(Settings().liveCardHidden(now).liveCardShown().liveCardVisible(now))
        assertTrue(Settings(liveCard = false).liveCardShown().liveCardVisible(now))
        assertTrue(Settings(liveCard = false, liveHiddenUntil = now + minute).liveCardShown().liveCardVisible(now))
    }
}
