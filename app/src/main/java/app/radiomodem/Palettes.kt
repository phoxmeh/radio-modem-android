package app.radiomodem

/** Waterfall colour maps. `t` is 0 (floor) .. 1 (loudest). */
object Palettes {
    val names = listOf("classic" to "Classic", "heat" to "Heat", "grey" to "Greyscale", "ice" to "Ice")

    fun color(palette: String, t0: Float): Int {
        val t = t0.coerceIn(0f, 1f)
        val (r, g, b) = when (palette) {
            "heat" -> Triple(minOf(1f, t * 2.2f), maxOf(0f, minOf(1f, (t - 0.35f) * 2.2f)), maxOf(0f, minOf(1f, (t - 0.75f) * 4f)))
            "grey" -> Triple(t, t, t)
            "ice" -> Triple(maxOf(0f, (t - 0.6f) * 2.5f), 0.15f + t * 0.8f, 0.3f + t * 0.7f)
            else -> when {  // classic: dark blue -> cyan -> yellow -> red
                t < 0.33f -> Triple(0f, t / 0.33f * 0.6f, 0.2f + t / 0.33f * 0.8f)
                t < 0.66f -> { val u = (t - 0.33f) / 0.33f; Triple(u, 0.6f + 0.4f * u, 1f - u) }
                else -> { val u = (t - 0.66f) / 0.34f; Triple(1f, 1f - u, 0f) }
            }
        }
        return (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
    }
}
