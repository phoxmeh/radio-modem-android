package app.radiomodem.rig

/** HF/6m amateur bands with a default digital-modes frequency (PSK31 calling area, USB dial). */
data class Band(val name: String, val lowHz: Long, val highHz: Long, val defaultHz: Long) {
    operator fun contains(hz: Long) = hz in lowHz..highHz
}

object Bands {
    val all = listOf(
        Band("160m", 1_800_000, 2_000_000, 1_838_150),
        Band("80m", 3_500_000, 4_000_000, 3_580_150),
        Band("40m", 7_000_000, 7_300_000, 7_070_150),
        Band("30m", 10_100_000, 10_150_000, 10_142_150),
        Band("20m", 14_000_000, 14_350_000, 14_070_150),
        Band("17m", 18_068_000, 18_168_000, 18_100_150),
        Band("15m", 21_000_000, 21_450_000, 21_080_150),
        Band("12m", 24_890_000, 24_990_000, 24_920_150),
        Band("10m", 28_000_000, 29_700_000, 28_120_150),
        Band("6m", 50_000_000, 54_000_000, 50_290_150),
    )
    fun of(hz: Long) = all.firstOrNull { hz in it }
}
