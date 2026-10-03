package app.radiomodem

import org.junit.Assert.*
import org.junit.Test

class ModemsTest {
    // The list order is the id contract with jni_bridge.cpp (gModemId): 0-5 PSK, 6-12 MFSK.
    @Test fun idsMatchNativeLayout() {
        assertEquals(40, Modems.all.size)
        assertEquals(0, Modems.idOf("BPSK31")); assertEquals(3, Modems.idOf("QPSK31"))
        assertEquals(6, Modems.idOf("MFSK4")); assertEquals(12, Modems.idOf("MFSK128"))
        assertEquals(38, Modems.idOf("WEFAX 576")); assertEquals(39, Modems.idOf("WEFAX 288"))
        assertEquals(38, Modems.idOf("WEFAX"))  // bare name used by the starter lists
        assertEquals(-1, Modems.idOf("nonsense"))
        // Olivia ids: 13 + toneIdx * 5 + bwIdx
        assertEquals("Olivia 4/125", Modems.all[Modems.oliviaId(0, 0)].name)
        assertEquals("Olivia 16/500", Modems.all[Modems.oliviaId(2, 2)].name)
        assertEquals("Olivia 64/2000", Modems.all[Modems.oliviaId(4, 4)].name)
        assertEquals(37, Modems.oliviaId(4, 4))
    }

    @Test fun oliviaBandwidth() {
        assertEquals(468.75f, Modems.all[Modems.oliviaId(2, 2)].bandwidthHz, 0.01f)  // 500 - 500/16
    }

    @Test fun pictureModesAreMfsk16_32_64_128() {
        assertEquals(setOf("MFSK16", "MFSK32", "MFSK64", "MFSK128"), Modems.PICTURE_MODES.map { Modems.all[it].name }.toSet())
    }

    @Test fun mfskBandwidths() {
        assertEquals(234f, Modems.all[Modems.idOf("MFSK16")].bandwidthHz, 0.5f)
        assertEquals(1875f, Modems.all[Modems.idOf("MFSK128")].bandwidthHz, 0.5f)
    }
}
