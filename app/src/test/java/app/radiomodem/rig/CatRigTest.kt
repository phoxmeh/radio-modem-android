package app.radiomodem.rig

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Simulated FT-891: answers the ASCII CAT subset the app uses, so protocol logic is testable without a radio. */
class FakeFt891 : SerialLink {
    var freq = 14070000L
    var mode = '2'
    var filter = 17
    var narrow = 0
    var power = 50
    val sent = mutableListOf<String>()
    private var reply = ""

    override fun write(text: String) {
        for (c in text.split(';').filter { it.isNotEmpty() }) {
            sent += "$c;"
            when {
                c == "ID" -> reply += "ID0650;"
                c == "FA" -> reply += "FA%09d;".format(freq)
                c.startsWith("FA") -> freq = c.substring(2).toLong()
                c == "MD0" -> reply += "MD0$mode;"
                c.startsWith("MD0") -> mode = c[3]
                c == "SH0" -> reply += "SH0%d%02d;".format(narrow, filter)
                c.startsWith("SH01") -> filter = c.substring(4).toInt()
                c.startsWith("NA0") -> narrow = c[3] - '0'
                c == "PC" -> reply += "PC%03d;".format(power)
                c.startsWith("PC") -> power = c.substring(2).toInt()
                c == "SM0" -> reply += "SM0128;"
            }
        }
    }
    override fun readUntil(terminator: Char, timeoutMs: Int) = reply.also { reply = "" }
    override fun purge() { reply = "" }
    override fun close() {}
}

class CatRigTest {
    private val profile = RigProfile.parse(File("src/main/assets/rigs/FT-891.json").readText())
    private val radio = FakeFt891()
    private val rig = CatRig(profile, radio)

    @Test fun profileLoadedFromFlrigTables() {
        assertEquals(12, profile.modes.size)
        assertEquals(listOf("LSB", "USB", "CW-U", "FM", "AM", "RTTY-L", "CW-L", "DATA-L", "RTTY-U", "FM-N", "DATA-U", "AM-N"),
            profile.modes.map { it.name })
        assertEquals(17, profile.bandwidths["SSBD"]!!.labels.size)
        assertEquals(38400, profile.serial.baud)
    }

    @Test fun identify() = assertTrue(rig.identify())

    @Test fun frequencyRoundTrip() {
        assertEquals(14070000L, rig.getFrequency())
        rig.setFrequency(7074000)
        assertEquals("FA007074000;", radio.sent.last())
        assertEquals(7074000L, rig.getFrequency())
    }

    @Test fun modeMapsCodesToIndexes() {
        val usb = profile.modes.indexOfFirst { it.name == "USB" }
        assertEquals(usb, rig.getMode())
        val dataU = profile.modes.indexOfFirst { it.name == "DATA-U" }
        rig.setMode(dataU)
        assertEquals("MD0C;", radio.sent.last())
        assertEquals(dataU, rig.getMode())
    }

    @Test fun bandwidthUsesModeTableAndNarrowRule() {
        val dataU = profile.modes.indexOfFirst { it.name == "DATA-U" }
        rig.setBandwidth(dataU, 3)  // "200" in the DATA table -> narrow filter on
        assertEquals(listOf("NA01;", "SH0104;"), radio.sent.takeLast(2))
        assertEquals(3, rig.getBandwidth(dataU))
        rig.setBandwidth(dataU, 12)
        assertEquals("NA00;", radio.sent.takeLast(2).first())
        assertEquals(12, rig.getBandwidth(dataU))
    }

    @Test fun fmHasNoBandwidthCommands() {
        val fm = profile.modes.indexOfFirst { it.name == "FM" }
        val before = radio.sent.size
        rig.setBandwidth(fm, 0)
        assertNull(rig.getBandwidth(fm))
        assertEquals(before, radio.sent.size)
    }

    @Test fun powerClampedAndFormatted() {
        rig.setPower(2)
        assertEquals("PC005;", radio.sent.last())
        rig.setPower(75)
        assertEquals(75, rig.getPower())
    }

    @Test fun smeterScaled() = assertEquals(50, rig.getSmeter())  // 128 / 2.56
}
