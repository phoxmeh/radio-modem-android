package app.radiomodem.schedule

/** A span of the UTC day, in minutes since midnight. May wrap midnight (start > end). */
data class HourWindow(val startMin: Int, val endMin: Int) {
    operator fun contains(minute: Int) = if (startMin <= endMin) minute in startMin..endMin else minute >= startMin || minute <= endMin

    companion object {
        /** From HHMM numbers as printed in schedules, e.g. of(230, 1039) for 0230-1039. */
        fun of(startHhmm: Int, endHhmm: Int) = HourWindow(toMin(startHhmm), toMin(endHhmm))
        private fun toMin(hhmm: Int) = (hhmm / 100) * 60 + hhmm % 100
    }
}

/**
 * One published frequency. [window] is when it is on air (null = whenever the station transmits); [hours] is the
 * human wording shown in notes and details, e.g. "0230-1039 UTC (night)".
 */
data class FaxFrequency(val kHz: Double, val window: HourWindow? = null, val hours: String = "") {
    fun isOn(minute: Int) = window?.contains(minute) ?: true
}

/**
 * A transmitting site. Frequencies and their hours come from NOAA's worldwide schedule (March 2025). The
 * published values are assigned (centre) frequencies unless [listedIsCarrier], and the USB dial is 1.9 kHz lower so
 * the fax subcarrier lands at 1900 Hz.
 */
data class FaxStation(
    val id: String, val place: String, val country: String, val region: String, val call: String, val operator: String,
    val dated: String, val freqs: List<FaxFrequency>, val note: String = "",
    val listedIsCarrier: Boolean = false, val keyPrefix: String = call,
) {
    val title get() = "$place, $country"

    fun dialKHz(f: FaxFrequency) = if (listedIsCarrier) f.kHz else f.kHz - 1.9
    fun activeAt(minute: Int) = freqs.filter { it.isOn(minute) }
}

object FaxStations {
    private fun w(a: Int, b: Int, tag: String = "") =
        HourWindow.of(a, b) to "%04d-%04d UTC".format(a, b) + if (tag.isNotEmpty()) " ($tag)" else ""

    private fun f(kHz: Double, win: Pair<HourWindow, String>? = null, hours: String = "") =
        FaxFrequency(kHz, win?.first, win?.second ?: hours)

    const val NOAA_NOTE = "NOAA proposed in 2026 to end its HF radiofax broadcasts toward the end of 2026, so these may stop."

    val all: List<FaxStation> = listOf(
        // ---- North America ----
        FaxStation("boston", "Boston", "USA", "North America", "NMF", "US Coast Guard for NOAA", "Feb 2020",
            listOf(f(4235.0, w(230, 1039, "night")), f(6340.5), f(9110.0), f(12750.0, w(1400, 2239, "day"))), NOAA_NOTE),
        FaxStation("new_orleans", "New Orleans", "USA", "North America", "NMG", "US Coast Guard for NOAA", "Feb 2020",
            listOf(f(4317.9), f(8503.9), f(12789.9), f(17146.4, w(1200, 2045, "day"))), NOAA_NOTE),
        FaxStation("kodiak", "Kodiak, Alaska", "USA", "North America", "NOJ", "US Coast Guard for NOAA", "Feb 2020",
            listOf(f(2054.0), f(4298.0), f(8459.0), f(12412.5)), NOAA_NOTE),
        FaxStation("pt_reyes", "Point Reyes, California", "USA", "North America", "NMC", "US Coast Guard for NOAA", "Feb 2020",
            listOf(f(4346.0, w(140, 1608, "night")), f(8682.0), f(12786.0), f(17151.2), f(22527.0, w(1840, 2356, "day"))), NOAA_NOTE),
        FaxStation("sydney_ns", "Sydney, Nova Scotia", "Canada", "North America", "VCO", "Canadian Coast Guard (ice charts)", "2014",
            listOf(f(4416.0, w(2200, 2331)), f(6915.1, w(1121, 1741)))),
        // ---- Europe ----
        FaxStation("hamburg", "Hamburg", "Germany", "Europe", "DDH3/DDK3/DDK6", "Deutscher Wetterdienst (German weather service)", "24 July 2024",
            listOf(f(3855.0), f(7880.0), f(13882.5)), "Uses a wider +/-425 Hz shift, so blacks and whites clip slightly.", keyPrefix = "DWD"),
        FaxStation("northwood", "Northwood", "United Kingdom", "Europe", "GYA", "Royal Navy / Met Office (JOMOC North Atlantic)", "5 Jan 2022",
            listOf(f(2618.5, w(2200, 500, "night")), f(4610.0, hours = "24 hours"), f(8040.0, hours = "alternative 24-hour frequency"),
                f(11086.5, w(600, 2000, "day")))),
        FaxStation("athens", "Athens", "Greece", "Europe", "SVJ4", "Hellenic National Meteorological Service", "Jan 2019",
            listOf(f(4481.0), f(8105.0)), listedIsCarrier = true),
        FaxStation("murmansk", "Murmansk", "Russia", "Europe", "RBW41/RBW48", "Russian weather service", "date not given",
            listOf(f(5336.0), f(6445.5), f(7908.8, w(1900, 600)), f(10130.0, w(600, 1900))), keyPrefix = "RBW"),
        // ---- Asia ----
        FaxStation("tokyo", "Tokyo", "Japan", "Asia", "JMH", "Japan Meteorological Agency", "Jan 2014",
            listOf(f(3622.5), f(7795.0), f(13988.5))),
        FaxStation("kagoshima", "Kagoshima", "Japan", "Asia", "JFX", "Japanese fisheries and marine-weather broadcast", "29 Jan 2025",
            listOf(f(4274.0), f(8658.0), f(13074.0), f(16907.5), f(22559.6)), "Carries fish-market information and marine weather."),
        FaxStation("kyodo", "Kyodo News", "Japan", "Asia", "JJC", "Kyodo News (news pages and navigational warnings)", "30 Jan 2025",
            listOf(f(16971.0))),
        FaxStation("seoul", "Seoul", "South Korea", "Asia", "HLL2", "Korea Meteorological Administration", "Jan 2009",
            listOf(f(3585.0, w(1200, 0)), f(5857.5), f(7433.5), f(9165.0), f(13570.0, w(0, 1200))), "Many of the reports are in Korean."),
        FaxStation("bangkok", "Bangkok", "Thailand", "Asia", "HSW64", "Thai Meteorological Department", "Jan 2009",
            listOf(f(7395.0)), "Old information: it may no longer be on air.", listedIsCarrier = true),
        FaxStation("guangzhou", "Guangzhou", "China", "Asia", "XSQ", "Guangzhou Coast Radio Station", "Aug 2022",
            listOf(f(4199.75), f(8412.5), f(12629.25), f(16826.25)), "Sends direct FM (F3C) rather than single sideband."),
        // ---- Pacific and Australia ----
        FaxStation("honolulu", "Honolulu, Hawaii", "USA", "Pacific and Australia", "KVM70", "US Coast Guard for NOAA", "Feb 2020",
            listOf(f(9982.5, w(519, 1556)), f(11090.0), f(16135.0, w(1719, 356))), NOAA_NOTE),
        FaxStation("charleville", "Charleville", "Australia", "Pacific and Australia", "VMC", "Bureau of Meteorology", "Apr 2022",
            listOf(f(2628.0, w(900, 1900)), f(5100.0), f(11030.0), f(13920.0), f(20469.0, w(1900, 900)))),
        FaxStation("wiluna", "Wiluna", "Australia", "Pacific and Australia", "VMW", "Bureau of Meteorology", "Apr 2022",
            listOf(f(5755.0, w(1100, 2100)), f(7535.0), f(10555.0), f(15615.0), f(18060.0, w(2100, 1100)))),
        // ---- South America ----
        FaxStation("valparaiso", "Valparaíso", "Chile", "South America", "CBV", "Chilean Navy (SHOA)", "Sep 2010",
            listOf(f(4228.0), f(8677.0), f(17146.4))),
        FaxStation("punta_arenas", "Punta Arenas", "Chile", "South America", "CBM", "Chilean Navy (SHOA)", "Sep 2010",
            listOf(f(4322.0), f(8696.0))),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
    val regions = all.map { it.region }.distinct()
}
