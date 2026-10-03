package app.radiomodem

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader: 16-bit PCM (first channel), resampled to [targetRate] with linear interpolation. */
object WavLoader {
    fun load(bytes: ByteArray, targetRate: Int = 48000): FloatArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 1; var rate = 0; var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16 || rate <= 0 || channels < 1) return null
                val n = minOf(len, bytes.size - body) / (2 * channels)
                val src = FloatArray(n) { b.getShort(body + it * 2 * channels) / 32768f }
                return if (rate == targetRate) src else resample(src, rate, targetRate)
            }
            pos = body + len + (len and 1)
        }
        return null
    }

    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        val n = (src.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = (x - i0).toFloat()
            src[i0] * (1 - f) + src[i1] * f
        }
    }
}
