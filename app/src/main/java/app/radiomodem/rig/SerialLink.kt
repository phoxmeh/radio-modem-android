package app.radiomodem.rig

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable

/** Byte transport to the rig. Implemented over USB serial; tests use a simulator. */
interface SerialLink : Closeable {
    fun write(text: String)
    /** Reads until [terminator] is seen or [timeoutMs] elapses; returns whatever arrived. */
    fun readUntil(terminator: Char, timeoutMs: Int): String
    /** Discards any unread input (stale or unsolicited replies). */
    fun purge()
}

class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    private val buf = ByteArray(256)

    override fun write(text: String) = port.write(text.toByteArray(Charsets.ISO_8859_1), 500)

    override fun readUntil(terminator: Char, timeoutMs: Int): String {
        val sb = StringBuilder()
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            val n = port.read(buf, 20)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (sb.indexOf(terminator.toString()) >= 0) break
            }
        }
        return sb.toString()
    }

    override fun purge() {
        while (port.read(buf, 5) > 0) { /* drop */ }
    }

    override fun close() = port.close()
}
