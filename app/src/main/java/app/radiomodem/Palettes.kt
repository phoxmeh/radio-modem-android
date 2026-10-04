package app.radiomodem

/** Waterfall colour maps. `t` is 0 (floor) .. 1 (loudest). */
object Palettes {
    // classic: near-black -> deep blue -> cyan -> yellow -> orange -> white, so weak signals stay visible against dark noise.
    private val classic = arrayOf(
        floatArrayOf(0.00f, 0.00f, 0.00f, 0.06f), floatArrayOf(0.25f, 0.00f, 0.10f, 0.55f), floatArrayOf(0.50f, 0.00f, 0.75f, 0.85f),
        floatArrayOf(0.72f, 0.95f, 0.95f, 0.15f), floatArrayOf(0.88f, 1.00f, 0.45f, 0.05f), floatArrayOf(1.00f, 1.00f, 1.00f, 1.00f),
    )
    private fun stops(t: Float): Triple<Float, Float, Float> {
        for (i in 1 until classic.size) if (t <= classic[i][0]) {
            val a = classic[i - 1]; val b = classic[i]
            val u = (t - a[0]) / (b[0] - a[0])
            return Triple(a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, a[3] + (b[3] - a[3]) * u)
        }
        return Triple(1f, 1f, 1f)
    }

    val names = listOf("classic" to "Classic", "heat" to "Heat", "grey" to "Greyscale", "ice" to "Ice")

    fun color(palette: String, t0: Float): Int {
        val t = t0.coerceIn(0f, 1f)
        val (r, g, b) = when (palette) {
            "heat" -> Triple(minOf(1f, t * 2.2f), maxOf(0f, minOf(1f, (t - 0.35f) * 2.2f)), maxOf(0f, minOf(1f, (t - 0.75f) * 4f)))
            "grey" -> Triple(t, t, t)
            "ice" -> Triple(maxOf(0f, (t - 0.6f) * 2.5f), 0.15f + t * 0.8f, 0.3f + t * 0.7f)
            else -> stops(t)
        }
        return (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
    }
}
