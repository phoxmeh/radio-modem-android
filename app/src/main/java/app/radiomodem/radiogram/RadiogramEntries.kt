package app.radiomodem.radiogram

import app.radiomodem.data.Memory
import java.time.format.TextStyle
import java.util.Locale

/** Saved-frequency entries for the Shortwave Radiogram transmitters, built from the weekly table. */
object RadiogramEntries {
    /** The table as published since April 2026; used until a post has been read, and for the starter entries. */
    const val BUNDLED_TABLE = """
        Thursday 2330-2400 9265 WINB Pennsylvania
        Saturday 0230-0300 9265 WINB Pennsylvania
        Saturday 2300-2330 9455 WRMI Florida
        Sunday 1430-1500 9955 WRMI Florida
        Monday 0030-0100 5050 WRMI Florida
        Monday 0800-0830 5850 WRMI Florida
        Wednesday 1330-1400 15770 WRMI Florida
        Thursday 0930-1000 7780 WRMI Florida
    """

    val bundledSlots: List<RadiogramSlot> by lazy { RadiogramParser.parseRows(BUNDLED_TABLE) }

    const val GROUP = "Shortwave Radiogram"

    /** One entry per frequency and transmitter. The dial is the listed (carrier) frequency: the broadcast is AM, decoded at 1500 Hz audio. */
    fun memories(slots: List<RadiogramSlot>, seeds: Boolean = false): List<Memory> =
        slots.groupBy { it.kHz to it.transmitter }.map { (key, rows) ->
            val (kHz, tx) = key
            Memory(
                name = "Shortwave Radiogram · $tx · $kHz kHz", group = GROUP, freqHz = kHz * 1000L, modem = "MFSK32", carrierHz = 1500,
                notes = "Weekly: " + rows.joinToString(", ") { "${it.day.getDisplayName(TextStyle.SHORT, Locale.US)} ${it.timeRange} UTC" } +
                    ". MFSK32 text and MFSK64 pictures; USB or AM with the waterfall at 1500 Hz. Current times are in Shortwave Radiogram in the menu.",
                seedKey = if (seeds) "swr-$kHz-${tx.lowercase().replace(' ', '_')}" else null,
            )
        }
}
