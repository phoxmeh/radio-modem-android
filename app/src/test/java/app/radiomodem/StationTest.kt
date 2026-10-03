package app.radiomodem

import app.radiomodem.settings.LineStamper
import app.radiomodem.settings.Station
import org.junit.Assert.*
import org.junit.Test

class StationTest {
    @Test fun locatorValidation() {
        assertTrue(Station.isValidLocator(""))
        assertTrue(Station.isValidLocator("FN31"))
        assertTrue(Station.isValidLocator("fn31pr"))
        assertTrue(Station.isValidLocator("FN31pr52"))
        assertFalse(Station.isValidLocator("FN3"))
        assertFalse(Station.isValidLocator("ZZ99"))      // field letters only go to R
        assertFalse(Station.isValidLocator("FN31pr5"))
        assertFalse(Station.isValidLocator("FN31YY"))    // subsquare letters only go to X
    }

    @Test fun callsignNormalisation() {
        assertEquals("W1AW", Station.normalizeCallsign(" w1aw "))
        assertEquals("DL/W1AW", Station.normalizeCallsign("dl/w1aw"))
        assertEquals("K1ABC", Station.normalizeCallsign("k1-abc!"))
    }

    @Test fun lineStamperStampsEachLineAcrossChunks() {
        val st = LineStamper { "12:00:00" }
        assertEquals("[12:00:00] CQ CQ", st.stamp("CQ CQ"))
        assertEquals(" de W1AW\n", st.stamp(" de W1AW\n"))               // continues the same line, no second stamp
        assertEquals("[12:00:00] next\n[12:00:00] line", st.stamp("next\nline"))
        assertEquals("\n\n", st.stamp("\n\n"))                          // blank lines are not stamped
        st.reset()
        assertEquals("[12:00:00] x", st.stamp("x"))
    }
}
