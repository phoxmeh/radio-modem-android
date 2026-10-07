package app.radiomodem

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavStreamTest {
    private fun wav(rate: Int, channels: Int, bits: Int, format: Int, samples: Int, dataLen: Long? = null, value: (Int) -> Double): ByteArray {
        val bytesPer = bits / 8
        val data = ByteBuffer.allocate(samples * channels * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) for (c in 0 until channels) {
            val v = value(i) * if (c == 0) 1.0 else 0.25
            when {
                format == 3 -> data.putFloat(v.toFloat())
                bits == 8 -> data.put((v * 127 + 128).toInt().toByte())
                bits == 16 -> data.putShort((v * 32767).toInt().toShort())
                bits == 24 -> { val x = (v * 8388607).toInt(); data.put(x.toByte()); data.put((x shr 8).toByte()); data.put((x shr 16).toByte()) }
                else -> data.putInt((v * 2147483647.0).toInt())
            }
        }
        val out = ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        out.write("RIFF".toByteArray()); le(36L + data.capacity(), 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(format.toLong(), 2); le(channels.toLong(), 2); le(rate.toLong(), 4)
        le(rate.toLong() * channels * bytesPer, 4); le((channels * bytesPer).toLong(), 2); le(bits.toLong(), 2)
        out.write("LIST".toByteArray()); le(4, 4); out.write("abcd".toByteArray())   // a chunk to skip
        out.write("data".toByteArray()); le(dataLen ?: data.capacity().toLong(), 4); out.write(data.array())
        return out.toByteArray()
    }

    private fun readAll(w: WavStream): FloatArray {
        val all = ArrayList<Float>()
        while (true) { val b = w.read(2400); if (b.isEmpty()) break; b.forEach { all.add(it) } }
        return all.toFloatArray()
    }

    private val tone = { i: Int -> 0.5 * Math.sin(2 * Math.PI * 1000 * i / 48000.0) }

    @Test fun sixteenBitAt48kIsUnchanged() {
        val out = readAll(WavStream.open(ByteArrayInputStream(wav(48000, 1, 16, 1, 10000, value = tone)))!!)
        assertTrue(out.size in 9998..10000)
        assertEquals(tone(123).toFloat(), out[123], 1e-3f)
    }

    @Test fun resamplesToTheTargetRate() {
        val out = readAll(WavStream.open(ByteArrayInputStream(wav(8000, 1, 16, 1, 8000, value = { 0.5 * Math.sin(2 * Math.PI * 500 * it / 8000.0) })))!!)
        assertEquals(48000.0, out.size.toDouble(), 10.0)                  // one second either way
        assertEquals((0.5 * Math.sin(2 * Math.PI * 500 * 6000 / 48000.0)).toFloat(), out[6000], 0.02f)
    }

    @Test fun readsOtherSampleFormatsAndTakesTheFirstChannel() {
        for ((bits, fmt) in listOf(8 to 1, 24 to 1, 32 to 1, 32 to 3)) {
            val out = readAll(WavStream.open(ByteArrayInputStream(wav(48000, 2, bits, fmt, 4000, value = tone)))!!)
            assertEquals("$bits-bit format $fmt length", 4000.0, out.size.toDouble(), 3.0)
            assertEquals("$bits-bit format $fmt value", tone(300).toFloat(), out[300], 0.02f)
        }
    }

    @Test fun aStreamedFileThatClaimsNoLengthRunsToTheEnd() {
        val out = readAll(WavStream.open(ByteArrayInputStream(wav(48000, 1, 16, 1, 5000, dataLen = 0xFFFFFFFFL, value = tone)))!!)
        assertEquals(5000.0, out.size.toDouble(), 3.0)
    }

    @Test fun aLongFileIsNeverHeldInMemory() {
        // 5 minutes at 192 kHz is 1.7 GB of 16-bit samples: read in blocks from a generated stream.
        val frames = 192_000L * 300
        val header = wav(192000, 1, 16, 1, 0, dataLen = frames * 2) { 0.0 }
        val body = object : java.io.InputStream() {
            var left = frames * 2
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (left <= 0) return -1; val n = minOf(len.toLong(), left).toInt(); java.util.Arrays.fill(b, off, off + n, 0); left -= n; return n }
        }
        val w = WavStream.open(java.io.SequenceInputStream(ByteArrayInputStream(header), body))!!
        var total = 0L
        repeat(1000) { total += w.read(2400).size }   // 50 s of audio, constant memory
        assertEquals(2_400_000L, total)
    }

    @Test fun rejectsFilesItCannotPlay() {
        assertNull(WavStream.open(ByteArrayInputStream("not a wav file at all".toByteArray())))
        assertNull(WavStream.open(ByteArrayInputStream(ByteArray(0))))
        assertNull(WavStream.open(ByteArrayInputStream(wav(48000, 1, 16, 1, 100, value = tone).copyOf(30))))   // truncated header
    }
}
