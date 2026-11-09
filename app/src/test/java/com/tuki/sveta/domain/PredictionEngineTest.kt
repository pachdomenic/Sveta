package com.tuki.sveta.domain

import com.tuki.sveta.domain.PredictionEngine.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PredictionEngineTest {

    private val t0: Instant = Instant.parse("2026-11-09T10:00:00Z")
    private fun min(m: Long): Instant = t0.plus(Duration.ofMinutes(m))

    private val lags = HashMap<String, List<Int>>()
    private val engine = PredictionEngine { lags[it].orEmpty() }

    private fun trigger(id: String, kind: SwitchKind = SwitchKind.OFF, at: Instant = t0) =
        Trigger(id, "Line $id", kind, "chan", "text", at)

    @Test
    fun `default window is used until lags are learned`() {
        val effects = engine.onTrigger(trigger("a"), gridOn = true, now = min(1))
        val p = (effects.single() as Effect.Announce).prediction
        assertEquals(min(5), p.earliest)
        assertEquals(min(20), p.latest)
        assertEquals(min(40), p.expiresAt)
        assertFalse(p.window.learned)
        assertEquals(p, engine.active)
    }

    @Test
    fun `learned lags replace the default window`() {
        lags[lagKey("a", SwitchKind.OFF)] = listOf(420, 600, 780, 540)
        val p = (engine.onTrigger(trigger("a"), true, min(1)).single() as Effect.Announce).prediction
        assertTrue(p.window.learned)
        assertEquals(t0.plusSeconds(420), p.earliest)
        assertEquals(t0.plusSeconds(780), p.latest)
        assertEquals(Duration.ofSeconds(600), p.window.median)
    }

    @Test
    fun `tight clusters keep a usable window`() {
        val w = LagWindow.of(listOf(300, 301, 302), Duration.ofMinutes(5), Duration.ofMinutes(20))
        assertEquals(Duration.ofSeconds(300), w.min)
        assertEquals(Duration.ofSeconds(420), w.max)
    }

    @Test
    fun `outage post is irrelevant when we are already dark`() {
        assertTrue(engine.onTrigger(trigger("a"), gridOn = false, now = min(1)).isEmpty())
        assertNull(engine.active)
    }

    @Test
    fun `restore post only matters during an outage`() {
        assertTrue(engine.onTrigger(trigger("a", SwitchKind.ON), gridOn = true, now = min(1)).isEmpty())
        assertEquals(1, engine.onTrigger(trigger("a", SwitchKind.ON), gridOn = false, now = min(1)).size)
    }

    @Test
    fun `unknown grid state still warns about outages`() {
        assertEquals(1, engine.onTrigger(trigger("a"), gridOn = null, now = min(1)).size)
    }

    @Test
    fun `repeated posts for the same line do not re-announce`() {
        engine.onTrigger(trigger("a"), true, min(1))
        assertTrue(engine.onTrigger(trigger("a", at = min(2)), true, min(2)).isEmpty())
    }

    @Test
    fun `stale posts are dropped`() {
        assertTrue(engine.onTrigger(trigger("a"), true, now = min(45)).isEmpty())
        assertNull(engine.active)
    }

    @Test
    fun `outage following the trigger is a hit with the measured lag`() {
        engine.onTrigger(trigger("a"), true, min(1))
        val effects = engine.onGrid(on = false, at = min(12))
        assertEquals(listOf(Effect.Hit(lagKey("a", SwitchKind.OFF), 720)), effects)
        assertNull(engine.active)
    }

    @Test
    fun `every pending line learns from the same outage`() {
        engine.onTrigger(trigger("a"), true, min(1))
        engine.onTrigger(trigger("b", at = min(4)), true, min(4))
        val hits = engine.onGrid(on = false, at = min(10)).filterIsInstance<Effect.Hit>()
        assertEquals(mapOf(lagKey("a", SwitchKind.OFF) to 600, lagKey("b", SwitchKind.OFF) to 360), hits.associate { it.key to it.lagSeconds })
    }

    @Test
    fun `grid flipping the other way teaches nothing`() {
        engine.onTrigger(trigger("a"), true, min(1))
        assertTrue(engine.onGrid(on = true, at = min(3)).isEmpty())
        assertNull(engine.active)
    }

    @Test
    fun `expiry is a miss`() {
        engine.onTrigger(trigger("a"), true, min(1))
        assertTrue(engine.onTick(min(39)).isEmpty())
        assertEquals(listOf(Effect.Miss(lagKey("a", SwitchKind.OFF))), engine.onTick(min(40)))
        assertNull(engine.active)
    }

    @Test
    fun `the soonest prediction is the active one`() {
        lags[lagKey("b", SwitchKind.OFF)] = listOf(60, 90, 120)
        engine.onTrigger(trigger("a"), true, min(1))
        val second = engine.onTrigger(trigger("b", at = min(1)), true, min(1))
        assertEquals(1, second.size)
        assertEquals("b", engine.active?.trigger?.watchId)
        assertNotNull(engine.active)
    }
}
