package app.radiomodem.schedule

/**
 * Parses NOAA's marine radiofax schedule text (the worldwide PDF as text, or one of its per-station text files)
 * into broadcasts. Stations publish in four layouts:
 *  1. NOAA tables "HHMM/HHMM  TITLE  120/576" (most stations; two columns = two daily cycles),
 *  2. DWD Hamburg "HH.MM  120 / 576  nn  00.00  Title",
 *  3. Northwood, product and time pairs in two columns,
 *  4. Kagoshima and Kyodo, a list per "Frequency - N kHz".
 * Guangzhou's bilingual table is not parsed yet.
 */
object ScheduleParser {
    private class Heading(val key: String, pattern: String) { val regex = Regex(pattern) }

    // In document order. Entries with no station (discontinued, or not parsed) still end the previous section.
    private val headings = listOf(
        Heading("tokyo", "^TOKYO, JAPAN\\s*$"),
        Heading("kagoshima", "^JFX Kagoshima, Japan\\s*$"),
        Heading("pevek", "^PEVEK, CHUKOTKA PENINSULA\\s*$"),
        Heading("seoul", "^SEOUL, REPUBLIC OF KOREA\\s*$"),
        Heading("bangkok", "^BANGKOK, THAILAND\\s*$"),
        Heading("kyodo", "^JJC Kyodo News Japan/Singapore\\s*$"),
        Heading("guangzhou", "^GUANGZHOU COAST RADIO STATION, CHINA\\s*$"),
        Heading("chile", "^VALPARAISO PLAYA ANCHA, CHILE \\(CBV\\)\\s*$"),
        Heading("iqaluit", "^IQALUIT, CANADA\\s*$"),
        Heading("resolute", "^RESOLUTE, CANADA\\s*$"),
        Heading("inuvik", "^INUVIK, CANADA\\s*$"),
        Heading("sydney", "^SYDNEY - NOVA SCOTIA, CANADA\\s*$"),
        Heading("kodiak", "^KODIAK, ALASKA, U\\.S\\.A\\.?\\s*$"),
        Heading("reyes", "^PT\\. REYES, CALIFORNIA, U\\.S\\.A\\.?\\s*$"),
        Heading("gulf", "^NEW ORLEANS, LOUISIANA, U\\.S\\.A\\.?\\s*$"),
        Heading("marsh", "^BOSTON, MASSACHUSETTS, U\\.S\\.A\\.?\\s*$"),
        Heading("charleville", "^CHARLEVILLE, AUSTRALIA\\s*$"),
        Heading("wellington", "^WELLINGTON, NEW ZEALAND\\s*$"),
        Heading("honolulu", "^HONOLULU, HAWAII, U\\.S\\.A\\.?\\s*$"),
        Heading("athens", "^ATHENS, GREECE\\s*$"),
        Heading("murmansk", "^MURMANSK, RUSSIA\\s*$"),
        Heading("hamburg", "^HAMBURG/PINNEBERG, GERMANY\\s*$"),
        Heading("northwood", "^NORTHWOOD, UNITED KINGDOM\\s*$"),
    )

    /** Parses every recognised station section found in [text]. */
    fun parse(text: String): ScheduleSet {
        val published = Regex("^#snapshot .*published (.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?: Regex("(?:INFORMATION DATED|Schedule effective|updated)[^\\n]*", RegexOption.IGNORE_CASE).find(text)?.value ?: ""
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var currentKey = ""
        for (line in text.lines()) {
            val h = headings.firstOrNull { it.regex.containsMatchIn(line.trim()) }
            if (h != null) {
                if (h.key != currentKey) {  // page headers repeat the station name; keep the same section
                    currentKey = h.key
                    current = sections.getOrPut(h.key) { mutableListOf() }
                }
                continue
            }
            current?.add(line)
        }
        val out = linkedMapOf<String, StationSchedule>()
        for ((key, lines) in sections) when (key) {
            "tokyo" -> out["tokyo"] = tables("tokyo", lines)
            "seoul" -> out["seoul"] = tables("seoul", lines)
            "bangkok" -> out["bangkok"] = tables("bangkok", lines)
            "kodiak" -> out["kodiak"] = tables("kodiak", lines)
            "reyes" -> out["pt_reyes"] = tables("pt_reyes", lines)
            "gulf" -> out["new_orleans"] = tables("new_orleans", lines)
            "marsh" -> out["boston"] = tables("boston", lines)
            "honolulu" -> out["honolulu"] = tables("honolulu", lines)
            "athens" -> out["athens"] = tables("athens", lines)
            "murmansk" -> out["murmansk"] = tables("murmansk", lines)
            "sydney" -> out["sydney_ns"] = tables("sydney_ns", lines)
            "charleville" -> {
                val s = tables("charleville", lines)
                out["charleville"] = s
                out["wiluna"] = s.copy(stationId = "wiluna", note = "Shared schedule for VMC Charleville and VMW Wiluna.")
            }
            "chile" -> chile(lines).forEach { out[it.stationId] = it }
            "hamburg" -> out["hamburg"] = dwd("hamburg", lines)
            "northwood" -> out["northwood"] = northwood("northwood", lines)
            "kagoshima" -> out["kagoshima"] = perFrequency("kagoshima", lines)
            "kyodo" -> out["kyodo"] = perFrequency("kyodo", lines)
        }
        return ScheduleSet(published, out)
    }

    // ---- layout 1: NOAA tables -------------------------------------------------------------------------------------

    private val tableHeader = Regex("CONTENTS OF TRANSMISSION")
    private val tableEnd = Regex("^\\s*(NOTES?\\b|MAP AREA|\\(INFORMATION|\\(SCHEDULE|Update\\b|\\* May refer)", RegexOption.IGNORE_CASE)
    private val timeLine = Regex("^\\s*(\\d{4}|-{2,})\\s*(?:/\\s*)?(\\d{4}|-{2,})?\\s+(\\S.*?)(?:\\s+(\\d{2,3})\\s*/\\s*(\\d{3})\\b.*)?$")

    private val onlyTimes = Regex("^\\s*(\\d{4}|-{2,})\\s*(?:/\\s*)?(\\d{4}|-{2,})?\\s*$")
    private val iocTail = Regex("^(.*?)(?:\\s+(\\d{2,3})\\s*/\\s*(\\d{3})\\b.*)?$")

    private fun tables(id: String, lines: List<String>, only: (String) -> Boolean = { true }): StationSchedule {
        val notes = footnotes(lines)
        val out = mutableListOf<Broadcast>()
        var inTable = false
        var pending: Pair<String, String>? = null   // times seen on a line of their own (PDFBox sometimes splits a row)

        fun add(t1: String, t2: String, rawTitle: String, rpm: String, ioc: String) {
            val (title, days) = cleanTitle(rawTitle, notes)
            if (title.isEmpty()) return
            for (t in listOf(t1, t2)) {
                val minute = minuteOf(t) ?: continue
                out += Broadcast(minute, title, rpm.toIntOrNull() ?: 120, ioc.toIntOrNull() ?: 576, days)
            }
        }

        for (line in lines) {
            if (tableHeader.containsMatchIn(line)) { inTable = only(line); pending = null; continue }
            if (tableEnd.containsMatchIn(line)) { inTable = false; pending = null; continue }
            if (!inTable) continue
            val m = timeLine.matchEntire(line)
            if (m != null) {
                pending = null
                val (t1, t2, rawTitle, rpm, ioc) = m.destructured
                add(t1, t2, rawTitle, rpm, ioc)
                continue
            }
            onlyTimes.matchEntire(line)?.let { pending = it.groupValues[1] to it.groupValues[2]; return@let }?.let { continue }
            val p = pending
            if (p != null && line.isNotBlank()) {
                val (title, rpm, ioc) = iocTail.matchEntire(line.trim())!!.destructured
                add(p.first, p.second, title, rpm, ioc)
                pending = null
            }
        }
        return StationSchedule(id, finish(out))
    }

    /** Valparaiso (CBV) and Punta Arenas (CBM) share a section with two tables. */
    private fun chile(lines: List<String>): List<StationSchedule> = listOf(
        tables("valparaiso", lines) { !it.contains("(CBM)") },
        tables("punta_arenas", lines) { it.contains("(CBM)") },
    )

    // ---- layout 2: DWD ---------------------------------------------------------------------------------------------

    private val dwdLine = Regex("^\\s*(\\d{2})\\.(\\d{2})\\s+(\\d{2,3})\\s*/\\s*(\\d{3})\\s*(?:\\d+\\s+)?(?:\\d{2}\\.\\d{2}\\s*)?(.*)$")

    private fun dwd(id: String, lines: List<String>): StationSchedule {
        val out = mutableListOf<Broadcast>()
        for (line in lines) {
            val m = dwdLine.matchEntire(line) ?: continue
            val (h, mi, rpm, ioc, rest) = m.destructured
            val minute = h.toInt() * 60 + mi.toInt()
            if (h.toInt() > 23 || mi.toInt() > 59) continue
            // Some rows carry their title on a neighbouring line in the PDF layout, so fall back to a generic label.
            out += Broadcast(minute, rest.trim().ifEmpty { "Chart (see DWD schedule)" }, rpm.toInt(), ioc.toInt())
        }
        return StationSchedule(id, finish(out), "Schedule from the Deutscher Wetterdienst; some chart titles are abbreviated.")
    }

    // ---- layout 3: Northwood ---------------------------------------------------------------------------------------

    /**
     * Rows read "PRODUCT time PRODUCT time" or just "time time" for charts that continue the previous product. Works on
     * tokens rather than column positions so it survives both pdftotext's layout and PDFBox's collapsed spacing.
     */
    private fun northwood(id: String, lines: List<String>): StationSchedule {
        val out = mutableListOf<Broadcast>()
        var inTable = false
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("Product") && t.contains("Broadcast")) { inTable = true; continue }
            if (!inTable) continue
            if (t.startsWith("All MAPS") || t.startsWith("Abbreviations")) break  // footer after the table
            val title = mutableListOf<String>()
            for (tok in t.split(Regex("\\s+"))) {
                val minute = if (tok.length == 4 && tok.all { it.isDigit() }) minuteOf(tok) else null
                if (minute != null) { out += Broadcast(minute, title.joinToString(" ").ifEmpty { "Chart" }); title.clear() }
                else title += tok
            }
        }
        return StationSchedule(id, finish(out), "Royal Navy / Met Office North Atlantic schedule. Blank product names are charts continuing the previous product.")
    }

    // ---- layout 4: one list per frequency --------------------------------------------------------------------------

    private val freqLine = Regex("Frequency\\s*-\\s*([\\d.]+)\\s*kHz")
    private val simpleTime = Regex("^\\s*(\\d{4})\\s+(\\S.*?)\\s*$")

    private fun perFrequency(id: String, lines: List<String>): StationSchedule {
        val notes = footnotes(lines)
        val out = mutableListOf<Broadcast>()
        var khz: Double? = null
        for (line in lines) {
            freqLine.find(line)?.let { khz = it.groupValues[1].toDoubleOrNull(); continue }
            if (khz == null) continue
            val m = simpleTime.matchEntire(line) ?: continue
            val minute = minuteOf(m.groupValues[1]) ?: continue
            val (title, days) = cleanTitle(m.groupValues[2], notes)
            out += Broadcast(minute, title, days = days, onlyKHz = khz)
        }
        return StationSchedule(id, finish(out), "Each frequency has its own list of transmissions.")
    }

    // ---- shared helpers --------------------------------------------------------------------------------------------

    private fun minuteOf(t: String): Int? {
        if (t.length != 4 || !t.all { it.isDigit() }) return null
        val h = t.substring(0, 2).toInt(); val m = t.substring(2).toInt()
        return if (h < 24 && m < 60) h * 60 + m else null
    }

    private fun finish(list: List<Broadcast>) =
        list.distinctBy { Triple(it.minute, it.title, it.onlyKHz) }.sortedWith(compareBy({ it.minute }, { it.title }))

    private val footnoteDef = Regex("^\\s*\\((\\d)\\)\\s+(\\S.*)$")
    private val footnoteRef = Regex("\\s*\\((\\d)\\)\\s*$")

    /** Footnote number -> days it restricts the broadcast to, when the note is a day list ("MONDAY ONLY"). */
    private fun footnotes(lines: List<String>): Map<Int, Set<Int>> {
        val out = mutableMapOf<Int, Set<Int>>()
        for (line in lines) {
            val m = footnoteDef.matchEntire(line) ?: continue
            parseDays(m.groupValues[2])?.let { out[m.groupValues[1].toInt()] = it }
        }
        return out
    }

    private fun cleanTitle(raw: String, notes: Map<Int, Set<Int>>): Pair<String, Set<Int>?> {
        var title = raw.trim().replace(Regex("\\s+"), " ")
        var days: Set<Int>? = null
        while (true) {
            val m = footnoteRef.find(title) ?: break
            days = (days ?: emptySet()) + (notes[m.groupValues[1].toInt()] ?: emptySet())
            title = title.removeRange(m.range).trim()
        }
        return title.trimEnd('*', ' ') to days?.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads a day restriction such as "MONDAY ONLY", "TUESDAY – SUNDAY", "SUNDAY, MONDAY" or "Tue Wed Thu Fri? Sat".
     * Returns ISO day numbers (1 = Monday), or null when the text names no days.
     */
    fun parseDays(text: String): Set<Int>? {
        val upper = text.uppercase()
        val names = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        val tokens = Regex("\\b(MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*").findAll(upper).map { names.indexOf(it.groupValues[1]) + 1 }.toList()
        if (tokens.isEmpty()) return null
        val range = tokens.size == 2 && Regex("[A-Z]\\s*(?:–|—|-|\\bTO\\b)\\s*[A-Z]").containsMatchIn(upper)
        if (!range) return tokens.toSet()
        val (a, b) = tokens
        return if (a <= b) (a..b).toSet() else ((a..7) + (1..b)).toSet()
    }
}
