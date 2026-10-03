package app.radiomodem

/** Decoder catalogue; the list index is the id used by the native engine. */
data class ModemInfo(val name: String, val family: String, val bandwidthHz: Float)

object Modems {
    private fun mfsk(name: String, symlen: Int, tones: Int) =
        ModemInfo(name, "MFSK", (tones - 1) * 8000f / symlen)

    val oliviaTones = listOf(4, 8, 16, 32, 64)
    val oliviaBandwidths = listOf(125, 250, 500, 1000, 2000)
    const val OLIVIA_BASE = 13

    val all = listOf(
        ModemInfo("BPSK31", "BPSK", 31.25f), ModemInfo("BPSK63", "BPSK", 62.5f), ModemInfo("BPSK125", "BPSK", 125f),
        ModemInfo("QPSK31", "QPSK", 31.25f), ModemInfo("QPSK63", "QPSK", 62.5f), ModemInfo("QPSK125", "QPSK", 125f),
        mfsk("MFSK4", 2048, 32), mfsk("MFSK8", 1024, 32), mfsk("MFSK16", 512, 16), mfsk("MFSK31", 256, 8),
        mfsk("MFSK32", 256, 16), mfsk("MFSK64", 128, 16), mfsk("MFSK128", 64, 16),
    ) + olivia() + listOf(
        ModemInfo("WEFAX 576", "WEFAX", 800f), ModemInfo("WEFAX 288", "WEFAX", 800f),
    )

    // Ordered to match native id = 13 + toneIdx * 5 + bwIdx.
    private fun olivia() = oliviaTones.flatMap { t ->
        oliviaBandwidths.map { bw -> ModemInfo("Olivia $t/$bw", "Olivia", bw - bw.toFloat() / t) }
    }
    fun oliviaId(toneIdx: Int, bwIdx: Int) = OLIVIA_BASE + toneIdx * 5 + bwIdx
    fun supportsReverse(family: String) = family == "QPSK" || family == "MFSK" || family == "Olivia"
    fun supportsAfc(family: String) = family != "Olivia"

    /** MFSK16, MFSK32, MFSK64, MFSK128: the modes fldigi gives a picture mode. */
    val PICTURE_MODES = setOf(8, 10, 11, 12)

    /** Decoder families in the order shown in the modem picker. */
    val families = listOf("BPSK", "QPSK", "MFSK", "Olivia", "WEFAX")

    /** The variant selected when you first switch to a family. */
    fun familyDefault(family: String) = when (family) {
        "BPSK" -> idOf("BPSK31"); "QPSK" -> idOf("QPSK31"); "MFSK" -> idOf("MFSK16")
        "Olivia" -> oliviaId(2, 2); else -> WEFAX_576
    }

    const val WEFAX_576 = 38
    const val WEFAX_288 = 39
    /** Bare "WEFAX" (used by the starter lists) means IOC 576, the usual HF broadcast format. */
    fun idOf(name: String?) = all.indexOfFirst { it.name == (if (name == "WEFAX") "WEFAX 576" else name) }
}
