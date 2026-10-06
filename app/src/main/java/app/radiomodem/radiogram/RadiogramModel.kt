package app.radiomodem.radiogram

import java.time.DayOfWeek
import java.time.LocalDate

/** One row of the weekly table: a weekday (UTC), a time range, the frequency and the transmitter. */
data class RadiogramSlot(val day: DayOfWeek, val startMin: Int, val endMin: Int, val kHz: Int, val transmitter: String) {
    /** "2330-2400" as written in the schedule. */
    val timeRange get() = "%02d%02d-%02d%02d".format(startMin / 60, startMin % 60, endMin / 60, endMin % 60)
}

/** A slot placed on the calendar. Times are epoch milliseconds (UTC). */
data class RadiogramBroadcast(val startMs: Long, val endMs: Long, val kHz: Int, val transmitter: String) {
    val key get() = "$startMs|$kHz"
}

/** One line of a programme's lineup: minutes:seconds into the transmission, the MFSK mode, and the item. */
data class LineupItem(val offset: String, val mode: String, val title: String)

/**
 * The schedule for one programme week, as published in a post: its dates, the table rows, and where the rows came from.
 * [rowsFrom] is "image" (read from this post's picture), "previous" (the picture could not be read, so the last known
 * table is reused), or "bundled".
 */
data class RadiogramWeek(
    val title: String,
    val program: Int?,
    val start: LocalDate,
    val end: LocalDate,
    val slots: List<RadiogramSlot>,
    val rowsFrom: String,
    val postUrl: String = "",
    val intro: String = "",
    val lineup: List<LineupItem> = emptyList(),
    val fetchedAtMs: Long = 0,
)
