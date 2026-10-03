package app.radiomodem.settings

/** Small pure helpers for station info and timestamped text, kept free of Android types so they unit-test. */
object Station {
    private val locatorRe = Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2}([0-9]{2})?)?$")

    /** Maidenhead locator with 4, 6 or 8 characters (e.g. FN31, FN31pr). Empty is allowed (not set). */
    fun isValidLocator(s: String) = s.isEmpty() || locatorRe.matches(s)

    /** Upper-case callsign limited to letters, digits and '/'. */
    fun normalizeCallsign(s: String) = s.uppercase().filter { it.isLetterOrDigit() && it.code < 128 || it == '/' }.take(12)
}

/** Inserts "[HH:MM:SS] " at the start of every line of a text stream that arrives in arbitrary pieces. */
class LineStamper(private val clock: () -> String) {
    private var atLineStart = true

    fun stamp(chunk: String): String {
        val sb = StringBuilder()
        for (ch in chunk) {
            if (atLineStart && ch != '\n' && ch != '\r') { sb.append('[').append(clock()).append("] "); atLineStart = false }
            sb.append(ch)
            if (ch == '\n' || ch == '\r') atLineStart = true
        }
        return sb.toString()
    }

    /** Forces the next character to start a new stamped line (e.g. after a picture or mode change). */
    fun reset() { atLineStart = true }
}
