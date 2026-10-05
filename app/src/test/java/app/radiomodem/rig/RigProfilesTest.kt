package app.radiomodem.rig

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Records what is written and answers each query from a fixed reply. */
private class Recorder(var reply: String = "") : SerialLink {
    val sent = mutableListOf<String>()
    override fun write(text: String) { sent += text }
    override fun readUntil(terminator: Char, timeoutMs: Int) = reply
    override fun purge() {}
    override fun close() {}
}

class RigProfilesTest {
    private fun load(id: String) = RigProfile.parse(File("src/main/assets/rigs/$id.json").readText())

    @Test fun everyBundledProfileIsConsistent() {
        File("src/main/assets/rigs").listFiles { f -> f.name.endsWith(".json") }!!.forEach { f ->
            val p = RigProfile.parse(f.readText())
            assertTrue("${p.name} has modes", p.modes.isNotEmpty())
            if (p.audioOnly) return@forEach  // no CAT tables to check
            p.modes.forEach { m ->
                val t = p.bandwidths[m.bwTable]
                assertNotNull("${p.name} ${m.name} table ${m.bwTable}", t)
                assertEquals("${p.name} ${m.bwTable} labels/values", t!!.labels.size, t.values.size)
                assertTrue("${p.name} ${m.name} default width in range", m.defaultBw in 0 until maxOf(1, t.labels.size) || m.defaultBw == 0)
            }
            assertTrue("${p.name} has USB", p.modes.any { it.name == "USB" })
        }
    }

    @Test fun ft991aSetsNarrowFilterThenWidth() {
        val p = load("FT-991A"); val link = Recorder(); val rig = CatRig(p, link)
        val usb = p.modes.indexOfFirst { it.name == "USB" }
        rig.setBandwidth(usb, 5)                         // 1350 Hz: below index 8, so the narrow filter is selected
        assertEquals(listOf("NA01;", "SH006;"), link.sent)
        link.sent.clear(); rig.setBandwidth(usb, 12)     // 2300 Hz: wide
        assertEquals(listOf("NA00;", "SH013;"), link.sent)
        link.reply = "SH006;"
        assertEquals(5, rig.getBandwidth(usb))
    }

    @Test fun ftdx10HasNoNarrowSwitch() {
        val p = load("FTdx10"); val link = Recorder(); val rig = CatRig(p, link)
        val dataU = p.modes.indexOfFirst { it.name == "DATA-U" }
        rig.setBandwidth(dataU, 17)
        assertEquals(listOf("SH0018;"), link.sent)
        link.reply = "SH0018;"
        assertEquals(17, rig.getBandwidth(dataU))
        link.sent.clear(); rig.setBandwidth(p.modes.indexOfFirst { it.name == "FM" }, 0)
        assertTrue("FM has a single fixed width", link.sent.isEmpty())
    }

    @Test fun rigConfigRoundTrips() {
        val c = RigConfig(name = "Shack", profileId = "FT-991A", usbVendor = 1, usbProduct = 2, usbPort = 1, baud = 19200, dataBits = 7,
            stopBits = 2, parity = 2, flow = "rtscts", dtr = "low", rts = "high", defaultMode = "DATA-U", defaultBw = "3000")
        assertEquals(c, RigConfig.fromJson(JSONObject(c.toJson().toString())))
    }
}
