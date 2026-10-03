package app.radiomodem.data

/**
 * Starter lists, bumped via [VERSION] so installed copies are refreshed by [MemoryStore].
 *
 *  - PSK31 USB dial frequencies: RSGB "PSK31 – work the world with low power" (rsgb.org/main/?p=4930).
 *  - HF weather fax: NOAA/NWS "Worldwide Marine Radiofacsimile Broadcast Schedules", 7 March 2025
 *    (weather.gov/media/marine/rfax.pdf). Frequencies there are assigned (centre) frequencies unless marked, and
 *    NOAA's rule is "for carrier frequency subtract 1.9 kHz", so the USB dial is assigned - 1.9 kHz and the fax
 *    subcarrier lands at 1900 Hz. Athens and Bangkok are marked as carrier frequencies, so their dial is the
 *    listed value. Stations NOAA reports as discontinued (Wellington NZ 2023; Iqaluit, Resolute and Inuvik 2019;
 *    Pevek, a 1997 LF entry) are left out. The NWS has also proposed ending its own five US broadcasts toward
 *    the end of 2026 (PNS 26-48).
 */
object SeedMemories {
    const val VERSION = 3

    private fun psk(band: String, dialKhz: Double, note: String = "") = Memory(
        name = "$band PSK31", group = "PSK31 calling", freqHz = Math.round(dialKhz * 1000), mode = "DATA-U",
        modem = "BPSK31", carrierHz = 1000, notes = note, seedKey = "psk31-$band",
    )

    /** One transmitting site. [freqs] are the published kHz values with an optional "hours of operation" note. */
    private class Site(
        val place: String, val country: String, val region: String, val call: String, val operator: String,
        val dated: String, val freqs: List<Pair<Double, String>>, val note: String = "",
        /** Published frequencies are already carrier frequencies (dial = listed) instead of assigned (dial = listed - 1.9). */
        val listedIsCarrier: Boolean = false, val keyPrefix: String = call,
    )

    private fun kHz(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().trimEnd('0')

    private fun entries(s: Site): List<Memory> = s.freqs.map { (f, hours) ->
        val dial = if (s.listedIsCarrier) f else f - 1.9
        Memory(
            name = "${s.place}, ${s.country} · ${kHz(f)} kHz",
            group = "Weather fax: ${s.region}",
            freqHz = Math.round(dial * 1000), mode = "USB", modem = "WEFAX", carrierHz = 1900,
            // Most useful facts first: the list shows only the first two lines of the notes.
            notes = buildString {
                append("${s.operator} (call sign ${s.call}). ")
                if (hours.isNotEmpty()) append("Hours: $hours. ")
                if (s.note.isNotEmpty()) append(s.note + " ")
                append("Schedule info dated ${s.dated}. ")
                append(if (s.listedIsCarrier) "Published as a carrier frequency, so the dial is the listed value."
                       else "Dial is 1.9 kHz below the published frequency (USB, fax subcarrier at 1900 Hz).")
            },
            seedKey = "fax-${s.keyPrefix}-$f",
        )
    }

    private const val ALL = ""
    private const val NOAA_NOTE = "NOAA proposed in 2026 to end its HF radiofax broadcasts toward the end of 2026, so these may stop."

    private val sites = listOf(
        // ---- North America ----
        Site("Boston", "USA", "North America", "NMF", "US Coast Guard for NOAA", "Feb 2020",
            listOf(4235.0 to "0230-1039 UTC (night)", 6340.5 to ALL, 9110.0 to ALL, 12750.0 to "1400-2239 UTC (day)"), NOAA_NOTE),
        Site("New Orleans", "USA", "North America", "NMG", "US Coast Guard for NOAA", "Feb 2020",
            listOf(4317.9 to ALL, 8503.9 to ALL, 12789.9 to ALL, 17146.4 to "1200-2045 UTC (day)"), NOAA_NOTE),
        Site("Kodiak, Alaska", "USA", "North America", "NOJ", "US Coast Guard for NOAA", "Feb 2020",
            listOf(2054.0 to ALL, 4298.0 to ALL, 8459.0 to ALL, 12412.5 to ALL), NOAA_NOTE),
        Site("Point Reyes, California", "USA", "North America", "NMC", "US Coast Guard for NOAA", "Feb 2020",
            listOf(4346.0 to "0140-1608 UTC (night)", 8682.0 to ALL, 12786.0 to ALL, 17151.2 to ALL, 22527.0 to "1840-2356 UTC (day)"), NOAA_NOTE),
        Site("Sydney, Nova Scotia", "Canada", "North America", "VCO", "Canadian Coast Guard (ice charts)", "2014",
            listOf(4416.0 to "2200-2331 UTC", 6915.1 to "1121-1741 UTC")),
        // ---- Europe ----
        Site("Hamburg", "Germany", "Europe", "DDH3/DDK3/DDK6", "Deutscher Wetterdienst (German weather service)", "24 July 2024",
            listOf(3855.0 to ALL, 7880.0 to ALL, 13882.5 to ALL), "Uses a wider +/-425 Hz shift, so blacks and whites clip slightly.", keyPrefix = "DWD"),
        Site("Northwood", "United Kingdom", "Europe", "GYA", "Royal Navy / Met Office (JOMOC North Atlantic)", "5 Jan 2022",
            listOf(2618.5 to "2200-0500 UTC (night)", 4610.0 to "24 hours", 8040.0 to "alternative 24-hour frequency", 11086.5 to "0600-2000 UTC (day)")),
        Site("Athens", "Greece", "Europe", "SVJ4", "Hellenic National Meteorological Service", "Jan 2019",
            listOf(4481.0 to ALL, 8105.0 to ALL), listedIsCarrier = true),
        Site("Murmansk", "Russia", "Europe", "RBW41/RBW48", "Russian weather service", "date not given",
            listOf(5336.0 to ALL, 6445.5 to ALL, 7908.8 to "1900-0600 UTC", 10130.0 to "0600-1900 UTC"), keyPrefix = "RBW"),
        // ---- Asia ----
        Site("Tokyo", "Japan", "Asia", "JMH", "Japan Meteorological Agency", "Jan 2014",
            listOf(3622.5 to ALL, 7795.0 to ALL, 13988.5 to ALL)),
        Site("Kagoshima", "Japan", "Asia", "JFX", "Japanese fisheries and marine-weather broadcast", "29 Jan 2025",
            listOf(4274.0 to ALL, 8658.0 to ALL, 13074.0 to ALL, 16907.5 to ALL, 22559.6 to ALL),
            "Carries fish-market information and marine weather."),
        Site("Kyodo News", "Japan", "Asia", "JJC", "Kyodo News (news pages and navigational warnings)", "30 Jan 2025",
            listOf(16971.0 to ALL)),
        Site("Seoul", "South Korea", "Asia", "HLL2", "Korea Meteorological Administration", "Jan 2009",
            listOf(3585.0 to "1200-0000 UTC", 5857.5 to ALL, 7433.5 to ALL, 9165.0 to ALL, 13570.0 to "0000-1200 UTC"),
            "Many of the reports are in Korean."),
        Site("Bangkok", "Thailand", "Asia", "HSW64", "Thai Meteorological Department", "Jan 2009",
            listOf(7395.0 to ALL), "Old information: it may no longer be on air.", listedIsCarrier = true),
        Site("Guangzhou", "China", "Asia", "XSQ", "Guangzhou Coast Radio Station", "Aug 2022",
            listOf(4199.75 to ALL, 8412.5 to ALL, 12629.25 to ALL, 16826.25 to ALL), "Sends direct FM (F3C) rather than single sideband."),
        // ---- Pacific and Australia ----
        Site("Honolulu, Hawaii", "USA", "Pacific and Australia", "KVM70", "US Coast Guard for NOAA", "Feb 2020",
            listOf(9982.5 to "0519-1556 UTC", 11090.0 to ALL, 16135.0 to "1719-0356 UTC"), NOAA_NOTE),
        Site("Charleville", "Australia", "Pacific and Australia", "VMC", "Bureau of Meteorology", "Apr 2022",
            listOf(2628.0 to "0900-1900 UTC", 5100.0 to ALL, 11030.0 to ALL, 13920.0 to ALL, 20469.0 to "1900-0900 UTC")),
        Site("Wiluna", "Australia", "Pacific and Australia", "VMW", "Bureau of Meteorology", "Apr 2022",
            listOf(5755.0 to "1100-2100 UTC", 7535.0 to ALL, 10555.0 to ALL, 15615.0 to ALL, 18060.0 to "2100-1100 UTC")),
        // ---- South America ----
        Site("Valparaíso", "Chile", "South America", "CBV", "Chilean Navy (SHOA)", "Sep 2010",
            listOf(4228.0 to ALL, 8677.0 to ALL, 17146.4 to ALL)),
        Site("Punta Arenas", "Chile", "South America", "CBM", "Chilean Navy (SHOA)", "Sep 2010",
            listOf(4322.0 to ALL, 8696.0 to ALL)),
    )

    val all: List<Memory> = listOf(
        psk("160m", 1838.150), psk("80m", 3580.150),
        psk("40m", 7070.150, "RSGB lists 7040 kHz and up; 7070 is common in the Americas."),
        psk("30m", 10142.150), psk("20m", 14070.150), psk("17m", 18100.150),
        psk("15m", 21080.150), psk("12m", 24920.150), psk("10m", 28120.150),
    ) + sites.flatMap { entries(it) }
}
