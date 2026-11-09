package com.tuki.sveta.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PowerMonitorTest {

    private val t0: Instant = Instant.parse("2026-11-09T10:00:00Z")
    private fun t(seconds: Long): Instant = t0.plusSeconds(seconds)

    @Test
    fun `first sample is taken at face value`() {
        val m = PowerMonitor()
        assertTrue(m.onVoltage(231.0, t(0)))
        assertEquals(GridState.ON, m.state)
        assertEquals(231.0, m.volts!!, 0.0)
    }

    @Test
    fun `outage is reported only after the debounce window`() {
        val m = PowerMonitor()
        m.onVoltage(231.0, t(0))
        assertFalse(m.onVoltage(0.0, t(10)))
        assertEquals(GridState.ON, m.state)
        assertFalse(m.onTick(t(13), connected = true))
        assertTrue(m.onTick(t(15), connected = true))
        assertEquals(GridState.OFF, m.state)
    }

    @Test
    fun `second sample after the window also settles the flip`() {
        val m = PowerMonitor()
        m.onVoltage(0.0, t(0))
        m.onVoltage(228.0, t(10))
        assertTrue(m.onVoltage(229.0, t(16)))
        assertEquals(GridState.ON, m.state)
    }

    @Test
    fun `short blip is ignored`() {
        val m = PowerMonitor()
        m.onVoltage(231.0, t(0))
        m.onVoltage(40.0, t(10))
        m.onVoltage(229.0, t(12))
        assertFalse(m.onTick(t(30), connected = true))
        assertEquals(GridState.ON, m.state)
    }

    @Test
    fun `sagging but usable voltage still counts as on`() {
        val m = PowerMonitor()
        assertTrue(m.onVoltage(176.0, t(0)))
        assertEquals(GridState.ON, m.state)
    }

    @Test
    fun `silence degrades to unknown by default`() {
        val m = PowerMonitor()
        m.onVoltage(231.0, t(0))
        assertFalse(m.onTick(t(100), connected = true))
        assertTrue(m.onTick(t(150), connected = true))
        assertEquals(GridState.UNKNOWN, m.state)
        assertNull(m.volts)
        // and does not fire again every tick
        assertFalse(m.onTick(t(400), connected = true))
    }

    @Test
    fun `silence is an assumed outage when the station shares the grid uplink`() {
        val m = PowerMonitor(PowerMonitor.Config(offlineMeansOutage = true))
        m.onVoltage(231.0, t(0))
        assertTrue(m.onTick(t(150), connected = true))
        assertEquals(GridState.OFF, m.state)
        assertTrue(m.assumed)
    }

    @Test
    fun `our own connection dropping never fakes an outage`() {
        val m = PowerMonitor(PowerMonitor.Config(offlineMeansOutage = true))
        m.onVoltage(231.0, t(0))
        assertTrue(m.onTick(t(150), connected = false))
        assertEquals(GridState.UNKNOWN, m.state)
    }

    @Test
    fun `measured outage survives the station going quiet`() {
        val m = PowerMonitor()
        m.onVoltage(0.0, t(0))
        assertFalse(m.onTick(t(500), connected = true))
        assertEquals(GridState.OFF, m.state)
    }

    @Test
    fun `assumed outage confirmed by a real sample is not reported twice`() {
        val m = PowerMonitor(PowerMonitor.Config(offlineMeansOutage = true))
        m.onVoltage(231.0, t(0))
        m.onOffline(connected = true)
        assertTrue(m.assumed)
        assertFalse(m.onVoltage(0.0, t(300)))
        assertFalse(m.assumed)
        assertEquals(GridState.OFF, m.state)
    }

    @Test
    fun `assumed outage ends immediately on a live sample`() {
        val m = PowerMonitor(PowerMonitor.Config(offlineMeansOutage = true))
        m.onVoltage(231.0, t(0))
        m.onOffline(connected = true)
        assertTrue(m.onVoltage(230.0, t(300)))
        assertEquals(GridState.ON, m.state)
    }

    @Test
    fun `keepalive messages postpone the silence timeout`() {
        val m = PowerMonitor()
        m.onVoltage(231.0, t(0))
        m.onAlive(t(100))
        assertFalse(m.onTick(t(200), connected = true))
        assertEquals(GridState.ON, m.state)
    }
}
