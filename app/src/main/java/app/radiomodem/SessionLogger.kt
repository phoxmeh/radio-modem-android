package app.radiomodem

import android.content.Context
import app.radiomodem.settings.AppSettings
import app.radiomodem.settings.LineStamper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One timestamped text file per app session in filesDir/logs. The file is created when the first text arrives,
 * so a session in which nothing was decoded leaves no file behind.
 */
class SessionLogger(private val ctx: Context, private val settings: AppSettings, private val context: () -> String) {
    private val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
    private val stamper = LineStamper { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) }
    private var file: File? = null
    private val pending = StringBuilder()  // notes recorded before the file exists

    @Synchronized fun text(raw: String) {
        if (!settings.logEnabled || raw.isEmpty()) return
        val f = file ?: open() ?: return
        f.appendText(pending.toString() + stamper.stamp(raw))
        pending.clear()
    }

    /** A marker line such as a mode change or a saved picture. */
    @Synchronized fun note(what: String) {
        if (!settings.logEnabled) return
        val line = "\n--- ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $what ---\n"
        val f = file
        if (f != null) f.appendText(line) else { pending.setLength(0); pending.append(line) }  // keep only the latest
        stamper.reset()
    }

    private fun open(): File? = try {
        val now = Date()
        val f = File(dir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".log")
        val station = listOf(settings.callsign, settings.operatorName, settings.locator).filter { it.isNotBlank() }.joinToString(" · ")
        f.writeText(
            "Radio Modem session log\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}\n" +
                (if (station.isNotEmpty()) "Station: $station\n" else "") + "Mode: ${context()}\n\n"
        )
        file = f
        prune()
        f
    } catch (e: Exception) { null }

    private fun prune() {
        val keep = settings.logKeep.toInt().coerceAtLeast(1)
        files().drop(keep).forEach { it.delete() }
    }

    /** Newest first. */
    fun files(): List<File> = dir.listFiles { f -> f.extension == "log" }?.sortedByDescending { it.name } ?: emptyList()

    fun delete(f: File) { if (f == file) file = null; f.delete() }
}
