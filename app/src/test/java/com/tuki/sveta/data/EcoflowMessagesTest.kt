package com.tuki.sveta.data

import com.tuki.sveta.data.ecoflow.EcoflowMessages
import com.tuki.sveta.data.ecoflow.StationSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EcoflowMessagesTest {

    private val now: Instant = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun `topics follow the app scheme`() {
        assertEquals("/app/device/property/DAEB123", EcoflowMessages.dataTopic("DAEB123"))
        assertEquals("/app/42/DAEB123/thing/property/get", EcoflowMessages.getTopic("42", "DAEB123"))
        assertEquals("/app/42/DAEB123/thing/property/get_reply", EcoflowMessages.getReplyTopic("42", "DAEB123"))
    }

    @Test
    fun `quota request has the expected envelope`() {
        val obj = Json.parseToJsonElement(EcoflowMessages.latestQuotasRequest()).jsonObject
        assertEquals("latestQuotas", obj.getValue("operateType").jsonPrimitive.content)
        assertEquals("1.1", obj.getValue("version").jsonPrimitive.content)
        assertEquals("0", obj.getValue("moduleType").jsonPrimitive.content)
        assertTrue(obj.getValue("id").jsonPrimitive.content.startsWith("9999"))
        assertTrue(obj.getValue("params").jsonObject.isEmpty())
    }

    @Test
    fun `property push is folded into the snapshot`() {
        val payload = """{"id":1,"version":"1.0","moduleType":0,"timestamp":1,
            "params":{"inv.acInVol":231456,"inv.inputWatts":812,"inv.outputWatts":140,
            "pd.wattsInSum":812,"pd.wattsOutSum":150,"bmsMaster.soc":74,
            "ems.chgRemainTime":93,"ems.dsgRemainTime":5999}}""".toByteArray()
        val params = checkNotNull(EcoflowMessages.parseProperty(payload))
        val s = EcoflowMessages.merge(StationSnapshot(), params, now)
        assertEquals(231.456, s.acInVolts!!, 1e-9)
        assertEquals(812, s.acInWatts)
        assertEquals(140, s.acOutWatts)
        assertEquals(150, s.totalOutWatts)
        assertEquals(74, s.batteryPercent)
        assertEquals(93, s.chargeRemainMinutes)
        assertEquals(0, s.dischargeRemainMinutes)
        assertEquals(now, s.updatedAt)
    }

    @Test
    fun `partial push keeps earlier values`() {
        val first = EcoflowMessages.merge(
            StationSnapshot(),
            EcoflowMessages.parseProperty("""{"params":{"inv.acInVol":229000,"bmsMaster.soc":50}}""".toByteArray())!!,
            now,
        )
        val second = EcoflowMessages.merge(
            first,
            EcoflowMessages.parseProperty("""{"params":{"inv.inputWatts":300}}""".toByteArray())!!,
            now.plusSeconds(1),
        )
        assertEquals(229.0, second.acInVolts!!, 1e-9)
        assertEquals(50, second.batteryPercent)
        assertEquals(300, second.acInWatts)
    }

    @Test
    fun `with an extra battery the combined charge is not overwritten by the main pack`() {
        fun push(prev: StationSnapshot, json: String) =
            EcoflowMessages.merge(prev, EcoflowMessages.parseProperty(json.toByteArray())!!, now)

        // Before anything better arrives, the main pack is all we know.
        var s = push(StationSnapshot(), """{"params":{"bmsMaster.soc":90}}""")
        assertEquals(90, s.batteryPercent)
        s = push(s, """{"params":{"ems.lcdShowSoc":63}}""")
        assertEquals(63, s.batteryPercent)
        // Main pack and pd pushes keep coming, but the station's own display value wins.
        s = push(s, """{"params":{"bmsMaster.soc":91,"bmsSlave1.soc":35}}""")
        assertEquals(63, s.batteryPercent)
        s = push(s, """{"params":{"pd.soc":62}}""")
        assertEquals(63, s.batteryPercent)
        s = push(s, """{"params":{"ems.lcdShowSoc":64}}""")
        assertEquals(64, s.batteryPercent)
    }

    @Test
    fun `zero volts means the grid is gone`() {
        val s = EcoflowMessages.merge(
            StationSnapshot(acInVolts = 230.0),
            EcoflowMessages.parseProperty("""{"params":{"inv.acInVol":0}}""".toByteArray())!!,
            now,
        )
        assertEquals(0.0, s.acInVolts!!, 0.0)
    }

    @Test
    fun `junk payloads are ignored`() {
        assertNull(EcoflowMessages.parseProperty(byteArrayOf(0x0a, 0x12, 0x7f)))
        assertNull(EcoflowMessages.parseProperty("""{"params":{}}""".toByteArray()))
        assertNull(EcoflowMessages.parseProperty("[]".toByteArray()))
    }

    @Test
    fun `latest quotas reply carries online flag and quota map`() {
        val reply = EcoflowMessages.parseGetReply(
            """{"operateType":"latestQuotas","data":{"online":1,"quotaMap":{"inv.acInVol":228100,"pd.soc":61}}}""".toByteArray()
        )!!
        assertEquals(true, reply.online)
        val s = EcoflowMessages.merge(StationSnapshot(), reply.quotas, now)
        assertEquals(228.1, s.acInVolts!!, 1e-9)
        assertEquals(61, s.batteryPercent)
    }

    @Test
    fun `offline reply has no quotas`() {
        val reply = EcoflowMessages.parseGetReply("""{"operateType":"latestQuotas","data":{"online":0}}""".toByteArray())!!
        assertEquals(false, reply.online)
        assertTrue(reply.quotas.isEmpty())
    }

    @Test
    fun `other replies are not quota replies`() {
        assertNull(EcoflowMessages.parseGetReply("""{"operateType":"TCP","data":{}}""".toByteArray()))
    }
}
