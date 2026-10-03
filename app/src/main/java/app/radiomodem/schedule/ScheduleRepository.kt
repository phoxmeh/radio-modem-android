package app.radiomodem.schedule

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Schedules come from three layers, newest on top: the snapshot bundled in the app (NOAA's worldwide PDF as text),
 * the worldwide PDF downloaded since, and NOAA's per-station text files for the five US stations. Nothing downloaded
 * replaces data unless it parses to a plausible schedule.
 */
class ScheduleRepository(private val ctx: Context) {
    class Report(val updated: List<String>, val unchanged: List<String>, val failed: List<String>) {
        fun summary() = buildString {
            if (updated.isNotEmpty()) append("Updated: ${updated.joinToString()}. ")
            if (unchanged.isNotEmpty()) append("Already current: ${unchanged.joinToString()}. ")
            if (failed.isNotEmpty()) append("Could not update: ${failed.joinToString()}.")
        }.trim().ifEmpty { "Nothing to update." }
    }

    private val dir = File(ctx.filesDir, "schedules").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")

    private class Source(val key: String, val label: String, val url: String, val expect: String?)

    private val sources = listOf(
        Source("worldwide", "worldwide schedules (NOAA PDF)", "https://www.weather.gov/media/marine/rfax.pdf", null),
        Source("hfmarsh", "Boston", "https://www.weather.gov/media/marine/hfmarsh.txt", "boston"),
        Source("hfgulf", "New Orleans", "https://www.weather.gov/media/marine/hfgulf.txt", "new_orleans"),
        Source("hfak", "Kodiak", "https://www.weather.gov/media/marine/hfak.txt", "kodiak"),
        Source("hfreyes", "Point Reyes", "https://www.weather.gov/media/marine/hfreyes.txt", "pt_reyes"),
        Source("hfhi", "Honolulu", "https://www.weather.gov/media/marine/hfhi.txt", "honolulu"),
    )

    /** Bundled snapshot with any downloaded updates layered on top (older downloads first). */
    fun load(): ScheduleSet {
        var set = ScheduleParser.parse(ctx.assets.open("schedules/worldwide.txt").bufferedReader().use { it.readText() })
        val meta = readMeta()
        val cached = sources.mapNotNull { s -> File(dir, "${s.key}.txt").takeIf { it.exists() }?.let { s to it } }
            .sortedBy { (s, _) -> meta.optJSONObject(s.key)?.optLong("fetchedAt", 0) ?: 0 }
        for ((_, f) in cached) runCatching { set += ScheduleParser.parse(f.readText()) }
        return set
    }

    /** "Bundled snapshot published March 7, 2025", or the same for the downloaded PDF. */
    fun describe(): String {
        val meta = readMeta()
        val pdf = meta.optJSONObject("worldwide")?.optString("published").orEmpty()
        val bundled = ScheduleParser.parse(ctx.assets.open("schedules/worldwide.txt").bufferedReader().use { it.readText() }).published
        return "Worldwide schedules: NOAA publication of ${pdf.ifEmpty { bundled }}" + if (pdf.isEmpty()) " (bundled)" else " (downloaded)"
    }

    suspend fun refresh(): Report = withContext(Dispatchers.IO) {
        val meta = readMeta()
        val updated = mutableListOf<String>(); val unchanged = mutableListOf<String>(); val failed = mutableListOf<String>()
        for (s in sources) {
            val entry = meta.optJSONObject(s.key) ?: JSONObject().also { meta.put(s.key, it) }
            try {
                val r = download(s.url, entry.optString("etag").ifEmpty { null }, entry.optString("lastModified").ifEmpty { null })
                entry.put("checkedAt", System.currentTimeMillis())
                if (r == null) { unchanged += s.label; continue }
                val text = if (s.key == "worldwide") stamp(pdfText(r.body)) else String(r.body, Charsets.UTF_8)
                val parsed = ScheduleParser.parse(text)
                val plausible = if (s.expect != null) (parsed.schedules[s.expect]?.broadcasts?.size ?: 0) >= 10 else parsed.schedules.size >= 12
                if (!plausible) { failed += "${s.label} (unrecognised format)"; continue }
                File(dir, "${s.key}.txt").writeText(text)
                entry.put("etag", r.etag ?: "").put("lastModified", r.lastModified ?: "")
                    .put("fetchedAt", System.currentTimeMillis()).put("published", parsed.published)
                updated += s.label
            } catch (e: Exception) {
                failed += "${s.label} (${e.message ?: e.javaClass.simpleName})"
            }
        }
        metaFile.writeText(meta.toString())
        Report(updated, unchanged, failed)
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    private class Download(val body: ByteArray, val etag: String?, val lastModified: String?)

    /** null = not modified since the validators we sent. */
    private fun download(url: String, etag: String?, lastModified: String?): Download? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 45_000
            c.setRequestProperty("User-Agent", "RadioModem-Android (schedule viewer)")
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
            return when (c.responseCode) {
                304 -> null
                200 -> Download(c.inputStream.use { it.readBytes() }, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
                else -> error("HTTP ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }

    private fun pdfText(pdf: ByteArray): String {
        PDFBoxResourceLoader.init(ctx)
        val tmp = File(ctx.cacheDir, "rfax.pdf").apply { writeBytes(pdf) }
        try {
            return PDDocument.load(tmp).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) }
        } finally { tmp.delete() }
    }

    /** Adds the header line that [ScheduleParser] reads the publication date from. */
    private fun stamp(text: String): String {
        val date = Regex("[A-Z][a-z]+ \\d{1,2}, \\d{4}").find(text.take(3000))?.value ?: "unknown date"
        return "#snapshot NOAA worldwide marine radiofax schedules, published $date\n$text"
    }

    private fun readMeta() = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())
}
