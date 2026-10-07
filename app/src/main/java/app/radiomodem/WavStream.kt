package app.radiomodem

import java.io.InputStream

/**
 * Streams a WAV file as mono floats at [targetRate]: only a few thousand samples are in memory at a time, so a
 * recording of any length plays. Reads 8/16/24/32-bit PCM and 32-bit float (first channel) and resamples with
 * linear interpolation. Nothing is read until the header has checked out; [open] returns null for anything else.
 */
class WavStream private constructor(
    private val input: InputStream, val rate: Int, private val channels: Int, private val bits: Int, private val isFloat: Boolean,
    private var remaining: Long, private val targetRate: Int,
) {
    private val frameBytes = channels * bits / 8
    private val ratio = rate.toDouble() / targetRate   // input samples per output sample
    private var win = FloatArray(16384)
    private var winCount = 0
    private var pos = 0.0
    private var eof = false

    /** Up to [want] output samples; fewer (possibly none) only at the end of the file. */
    fun read(want: Int): FloatArray {
        val out = FloatArray(want)
        var n = 0
        while (n < want) {
            val i0 = pos.toInt()
            if (i0 + 1 >= winCount) {
                if (eof) break
                if (i0 > 0) { System.arraycopy(win, i0, win, 0, winCount - i0); winCount -= i0; pos -= i0 }
                if (!fill()) eof = true
                continue
            }
            val f = (pos - i0).toFloat()
            out[n++] = win[i0] * (1 - f) + win[i0 + 1] * f
            pos += ratio
        }
        return if (n == want) out else out.copyOf(n)
    }

    fun close() = runCatching { input.close() }

    private val raw = ByteArray(8192 * 4 * 8)

    /** Appends the next block of input frames to the window. False when the data is exhausted. */
    private fun fill(): Boolean {
        if (remaining <= 0) return false
        val frames = minOf(8192L, remaining / frameBytes).toInt()
        if (frames <= 0) return false
        val want = frames * frameBytes
        var got = 0
        while (got < want) { val r = input.read(raw, got, want - got); if (r < 0) break; got += r }
        val have = got / frameBytes
        if (have == 0) return false
        remaining -= got
        if (winCount + have > win.size) win = win.copyOf(maxOf(win.size * 2, winCount + have))
        for (k in 0 until have) win[winCount + k] = sample(k * frameBytes)
        winCount += have
        return have == frames   // a short read means the file ended early
    }

    private fun sample(o: Int): Float = when {
        isFloat -> java.lang.Float.intBitsToFloat(le(o, 4))
        bits == 8 -> ((raw[o].toInt() and 0xFF) - 128) / 128f
        bits == 16 -> ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)) / 32768f
        bits == 24 -> ((raw[o].toInt() and 0xFF shl 8) or (raw[o + 1].toInt() and 0xFF shl 16) or (raw[o + 2].toInt() shl 24)) / 2147483648f
        else -> le(o, 4) / 2147483648f
    }

    private fun le(o: Int, n: Int): Int { var v = 0; for (i in 0 until n) v = v or ((raw[o + i].toInt() and 0xFF) shl (8 * i)); return v }

    companion object {
        /** Parses the header; null if this is not a WAV file this reader can play. Closes [input] in that case. */
        fun open(input: InputStream, targetRate: Int = 48000): WavStream? {
            try {
                val head = readN(input, 12) ?: return fail(input)
                val tag = String(head, 0, 4)
                if ((tag != "RIFF" && tag != "RF64") || String(head, 8, 4) != "WAVE") return fail(input)
                var channels = 0; var rate = 0; var bits = 0; var format = 0
                while (true) {
                    val h = readN(input, 8) ?: return fail(input)
                    val id = String(h, 0, 4)
                    val len = (u32(h, 4))
                    if (id == "fmt ") {
                        val f = readN(input, len.toInt().coerceIn(16, 4096)) ?: return fail(input)
                        format = u16(f, 0); channels = u16(f, 2); rate = u32(f, 4).toInt(); bits = u16(f, 14)
                        if (format == 0xFFFE && f.size >= 26) format = u16(f, 24)  // WAVE_FORMAT_EXTENSIBLE: the real format follows
                        if (len > f.size) skip(input, len - f.size + (len and 1))
                    } else if (id == "data") {
                        val isFloat = format == 3
                        val ok = rate in 1000..400_000 && channels in 1..16 && (format == 1 && bits in intArrayOf(8, 16, 24, 32) || isFloat && bits == 32)
                        if (!ok) return fail(input)
                        // A streamed file may claim 0 or 0xFFFFFFFF bytes: then it runs to the end of the file.
                        val size = if (len == 0L || len == 0xFFFFFFFFL) Long.MAX_VALUE else len
                        return WavStream(input, rate, channels, bits, isFloat, size, targetRate)
                    } else skip(input, len + (len and 1))
                }
            } catch (e: java.io.IOException) { return fail(input) }
        }

        private fun fail(i: InputStream): WavStream? { runCatching { i.close() }; return null }
        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

        private fun readN(i: InputStream, n: Int): ByteArray? {
            val b = ByteArray(n); var got = 0
            while (got < n) { val r = i.read(b, got, n - got); if (r < 0) return null; got += r }
            return b
        }

        private fun skip(i: InputStream, n: Long) {
            var left = n
            while (left > 0) { val s = i.skip(left); if (s <= 0) { if (i.read() < 0) throw java.io.IOException("end of file"); left-- } else left -= s }
        }
    }
}
