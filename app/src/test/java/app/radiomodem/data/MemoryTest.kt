package app.radiomodem.data

import org.junit.Assert.*
import org.junit.Test

class MemoryTest {
    private val sample = listOf(
        Memory(name = "20m, \"PSK\"", group = "Digital", freqHz = 14070150, mode = "DATA-U", bandwidth = "500",
            modem = "BPSK31", carrierHz = 1000, notes = "line one\nline two, with comma"),
        Memory(name = "plain", freqHz = 7074000),
    )

    @Test fun jsonRoundTrip() {
        val back = Memory.listFromJson(Memory.listToJson(sample))
        assertEquals(sample, back)
    }

    @Test fun csvRoundTripKeepsQuotesCommasNewlines() {
        val back = Memory.listFromCsv(Memory.listToCsv(sample))
        assertEquals(sample.map { it.copy(id = "") }, back.map { it.copy(id = "") })
    }

    @Test fun csvImportToleratesColumnOrderAndBadRows() {
        val csv = "freq_hz,name,mode\n14074000,FT8 20m,USB\nnotanumber,bad,USB\n,,\n"
        val items = Memory.listFromCsv(csv)
        assertEquals(1, items.size)
        assertEquals(14074000L, items[0].freqHz)
        assertEquals("FT8 20m", items[0].name)
    }

    private fun fax() = SeedMemories.all.filter { it.modem == "WEFAX" }

    @Test fun faxDialIsAssignedMinus1900() {
        val m = SeedMemories.all.first { it.seedKey == "fax-NMF-4235.0" }
        assertEquals(4233100L, m.freqHz)
        assertEquals("WEFAX", m.modem)
        assertEquals(9980600L, SeedMemories.all.first { it.seedKey == "fax-KVM70-9982.5" }.freqHz)
        // Hamburg (DWD) published 3855 kHz
        assertEquals(3853100L, SeedMemories.all.first { it.seedKey == "fax-DWD-3855.0" }.freqHz)
    }

    @Test fun faxStationsListedAsCarrierUseTheListedValue() {
        // Athens and Bangkok are published as carrier frequencies, so no 1.9 kHz is subtracted.
        assertEquals(4481000L, SeedMemories.all.first { it.seedKey == "fax-SVJ4-4481.0" }.freqHz)
        assertEquals(7395000L, SeedMemories.all.first { it.seedKey == "fax-HSW64-7395.0" }.freqHz)
    }

    @Test fun faxEntriesAreNamedByPlaceNotCallSign() {
        val callSignLike = Regex("^[A-Z0-9/]{3,6}\\b")  // e.g. "NMF 4235.0"
        for (m in fax()) {
            assertFalse("name should start with a place: ${m.name}", callSignLike.containsMatchIn(m.name))
            assertTrue("name has the frequency: ${m.name}", m.name.contains("kHz"))
            assertTrue("call sign kept in the notes: ${m.name}", m.notes.contains("call sign"))
            assertEquals(1900, m.carrierHz)
        }
        assertEquals("Boston, USA · 4235 kHz", SeedMemories.all.first { it.seedKey == "fax-NMF-4235.0" }.name)
        assertEquals("Hamburg, Germany · 13882.5 kHz", SeedMemories.all.first { it.seedKey == "fax-DWD-13882.5" }.name)
    }

    @Test fun seedKeysUniqueAndCounts() {
        val keys = SeedMemories.all.map { it.seedKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(9, SeedMemories.all.count { it.group == "PSK31 calling" })
        assertEquals(14070150L, SeedMemories.all.first { it.seedKey == "psk31-20m" }.freqHz)
        assertTrue("expected the full worldwide list", fax().size >= 60)
    }

    @Test fun discontinuedStationsAreNotListed() {
        val names = fax().joinToString { it.name }
        for (gone in listOf("Wellington", "Iqaluit", "Resolute", "Inuvik", "Pevek")) assertFalse(gone, names.contains(gone))
    }

    @Test fun faxFrequenciesAreSaneHf() {
        for (m in fax()) assertTrue(m.name, m.freqHz in 2_000_000L..25_000_000L)
    }

    @Test fun refreshSeedsRenamesBuiltInsKeepsUserEntries() {
        val oldNmf = Memory(id = "keep-this-id", name = "NMF 4235.0", group = "NOAA radiofax", freqHz = 4233100, modem = "WEFAX",
            seedKey = "fax-NMF-4235.0")
        val obsolete = Memory(name = "gone", freqHz = 1, seedKey = "fax-OLD-1.0")
        val mine = Memory(name = "My own", freqHz = 7074000)
        val out = MemoryStore.refreshSeeds(listOf(oldNmf, obsolete, mine))
        assertTrue(out.any { it.name == "My own" })
        assertFalse(out.any { it.seedKey == "fax-OLD-1.0" })
        val nmf = out.first { it.seedKey == "fax-NMF-4235.0" }
        assertEquals("keep-this-id", nmf.id)
        assertEquals("Boston, USA · 4235 kHz", nmf.name)
        assertEquals("Weather fax: North America", nmf.group)
    }
}
