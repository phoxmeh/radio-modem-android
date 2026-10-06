package app.radiomodem.radiogram

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Turns the text OCR reads from the weekly schedule picture (and the blog post around it) into a calendar.
 * The picture is a four-column table: UTC Day, UTC Time, Frequency kHz, Transmitter. OCR makes small mistakes
 * (a letter for a digit, a stray grid line), so every field is checked against what it can be, and rows that do not
 * check out are dropped rather than guessed.
 */
object RadiogramParser {
    private val dayNames = DayOfWeek.values().associateBy { it.name.lowercase() }

    /** Transmitters seen so far; a close OCR reading is snapped to the real name. */
    private val knownTransmitters = listOf("WINB Pennsylvania", "WRMI Florida", "WWCR Tennessee", "WBCQ Maine", "WTWW Tennessee", "Channel 292 Germany")

    // ---- the table ----------------------------------------------------------------------------

    fun parseRows(ocr: String): List<RadiogramSlot> =
        ocr.lines().mapNotNull(::parseRow).distinct()

    internal fun parseRow(raw: String): RadiogramSlot? {
        val all = raw.replace('|', ' ').replace('—', '-').replace('–', '-').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (all.size < 4) return null
        // OCR sometimes splits a long word ("Satur day").
        var skip = 1
        val day = matchDay(all[0]) ?: matchDay(all[0] + all[1])?.also { skip = 2 } ?: return null
        val tokens = listOf(all[0]) + all.drop(skip)
        if (tokens.size < 4) return null
        // The time range may be split by spaces around the dash ("2330 - 2400").
        var i = 1
        var timeText = tokens[i]
        if (!timeText.contains('-') && i + 1 < tokens.size && tokens[i + 1].startsWith("-")) { timeText += tokens[i + 1]; i++ }
        if (timeText.endsWith("-") && i + 1 < tokens.size) { timeText += tokens[i + 1]; i++ }
        val tm = Regex("^(\\d{4})-(\\d{4})$").matchEntire(digitsOnly(timeText)) ?: return null
        val start = minutes(tm.groupValues[1]) ?: return null
        val end = minutes(tm.groupValues[2]) ?: return null
        if (end <= start) return null
        i++
        if (i >= tokens.size) return null
        val freq = digitsOnly(tokens[i]).toIntOrNull()?.takeIf { it in 2300..30000 } ?: return null
        val name = tokens.drop(i + 1).joinToString(" ").replace(Regex("[^A-Za-z0-9 ./&-]"), "").trim()
        if (name.length < 3) return null
        return RadiogramSlot(day, start, end, freq, snapTransmitter(name))
    }

    private fun minutes(hhmm: String): Int? {
        val h = hhmm.substring(0, 2).toInt(); val m = hhmm.substring(2).toInt()
        return if (m < 60 && (h < 24 || (h == 24 && m == 0))) h * 60 + m else null
    }

    /** OCR confuses O/0, l/I/1, S/5, B/8 inside numbers. */
    private fun digitsOnly(s: String) = s.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'l', 'I', 'i', '|', 'L' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
    }.joinToString("").replace(Regex("[^0-9-]"), "")

    private fun matchDay(word: String): DayOfWeek? {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.length < 5) return null
        return dayNames.entries.map { it.value to distance(w, it.key) }.minByOrNull { it.second }?.takeIf { it.second <= 2 }?.first
    }

    private fun snapTransmitter(name: String): String {
        val best = knownTransmitters.map { it to distance(name.lowercase(), it.lowercase()) }.minByOrNull { it.second }!!
        return if (best.second <= maxOf(2, best.first.length / 5)) best.first else name
    }

    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    // ---- calendar -----------------------------------------------------------------------------

    /**
     * Places the rows on the calendar. The table is in time order and starts on the week's first day; a row whose
     * weekday comes round again (the second Thursday) is the following week.
     */
    fun place(slots: List<RadiogramSlot>, start: LocalDate): List<RadiogramBroadcast> {
        var date: LocalDate? = null
        var prevStart = -1
        return slots.map { s ->
            val d = date
            val next = when {
                d == null -> start.with(TemporalAdjusters.nextOrSame(s.day))
                s.day == d.dayOfWeek && s.startMin >= prevStart -> d  // later the same day
                else -> d.with(TemporalAdjusters.next(s.day))
            }
            date = next; prevStart = s.startMin
            val base = next.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            RadiogramBroadcast(base + s.startMin * 60_000L, base + s.endMin * 60_000L, s.kHz, s.transmitter)
        }
    }

    // ---- the post -----------------------------------------------------------------------------

    class PostTitle(val program: Int?, val start: LocalDate, val end: LocalDate)

    /** "Shortwave Radiogram, 1-8 October 2026 (program 469): …", also "27 August-3 September 2026". */
    fun parseTitle(title: String): PostTitle? {
        val m = Regex("(\\d{1,2})(?:\\s+([A-Za-z]+))?(?:\\s+(\\d{4}))?\\s*[-–]\\s*(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})").find(title) ?: return null
        val endMonth = month(m.groupValues[5]) ?: return null
        val startMonth = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { month(it) ?: return null } ?: endMonth
        val endYear = m.groupValues[6].toInt()
        val startYear = m.groupValues[3].toIntOrNull() ?: if (startMonth > endMonth) endYear - 1 else endYear
        return runCatching {
            PostTitle(
                Regex("program\\s+(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toInt(),
                LocalDate.of(startYear, startMonth, m.groupValues[1].toInt()), LocalDate.of(endYear, endMonth, m.groupValues[4].toInt()),
            )
        }.getOrNull()
    }

    private fun month(name: String): Month? =
        Month.values().firstOrNull { name.length >= 3 && it.name.startsWith(name.uppercase().take(if (name.length >= 4) name.length else 3)) }

    /** The programme lines ("11:04  MFSK64: This week's images") from the post text. */
    fun parseLineup(text: String): List<LineupItem> {
        val i = text.indexOf("lineup", ignoreCase = true)
        if (i < 0) return emptyList()
        val out = mutableListOf<LineupItem>()
        for (line in text.substring(i).lines().drop(1)) {
            val m = Regex("^\\s*(\\d{1,2}:\\d{2})\\s+([A-Za-z0-9 /-]{3,20}?):\\s*(.+?)\\s*$").find(line)
            if (m != null) out += LineupItem(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            else if (out.isNotEmpty() && line.isBlank()) break
        }
        return out
    }
}
