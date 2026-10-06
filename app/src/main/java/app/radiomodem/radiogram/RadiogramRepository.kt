package app.radiomodem.radiogram

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate

/** The week as it should be shown now: placed on the calendar, and whether it was carried forward from an older post. */
class WeekView(val week: RadiogramWeek, val broadcasts: List<RadiogramBroadcast>, val projected: Boolean, val start: LocalDate)

/**
 * The weekly schedule. A bundled copy of the table is always available; refreshing reads the latest post from the
 * blog's RSS feed, takes its dates from the title, and reads the table from the post's picture with OCR. If the
 * picture cannot be read, the last known table is kept and only the dates move on.
 */
class RadiogramRepository(private val ctx: Context) {
    private val file = File(ctx.filesDir, "radiogram.json")

    fun load(): RadiogramWeek = runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() ?: bundled()

    private fun bundled(): RadiogramWeek {
        val slots = RadiogramEntries.bundledSlots
        val start = LocalDate.of(2026, 10, 1)
        return RadiogramWeek("Bundled schedule", null, start, start.plusDays(7), slots, "bundled")
    }

    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        val feed = FEEDS.firstNotNullOfOrNull { runCatching { RadiogramFeed.parse(String(get(it, 3_000_000), Charsets.UTF_8)) }.getOrNull()?.takeIf { p -> p.isNotEmpty() } }
            ?: return@withContext "Could not reach swradiogram.net."
        val post = feed.firstOrNull { RadiogramParser.parseTitle(it.title) != null }
            ?: return@withContext "No schedule post found in the feed."
        val dates = RadiogramParser.parseTitle(post.title)!!
        val cur = load()
        if (cur.postUrl == post.link && cur.rowsFrom == "image") return@withContext "Already current: program ${dates.program ?: "?"}."

        var slots = cur.slots; var from = "previous"; var note = ""
        val url = post.scheduleImageUrl
        if (url == null) note = "The post has no schedule picture."
        else try {
            val text = RadiogramOcr(ctx).read(get(url, 4_000_000))
            Log.d("Radiogram", "OCR text:\n$text")
            val rows = RadiogramParser.parseRows(text)
            if (rows.size >= 6) { slots = rows; from = "image" } else note = "The schedule picture could not be read (${rows.size} rows recognised)."
        } catch (e: Exception) { note = "The schedule picture could not be read (${e.message ?: e.javaClass.simpleName})." }

        val week = RadiogramWeek(post.title, dates.program, dates.start, dates.end, slots, from, post.link, post.intro,
            RadiogramParser.parseLineup(post.text), System.currentTimeMillis())
        file.writeText(toJson(week).toString())
        buildString {
            append("Program ${dates.program ?: "?"}, ${dates.start.dayOfMonth} ${dates.start.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}–${dates.end.dayOfMonth} ${dates.end.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}: ")
            append(if (from == "image") "table read from the post's picture." else "using the last known table. $note")
        }
    }

    private fun get(url: String, limit: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "RadioModem/0.1 (Android; schedule reader)")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { i -> val buf = ByteArray(16384); while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) error("too large") } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    companion object {
        private val FEEDS = listOf("https://swradiogram.net/rss", "https://swradiogram.tumblr.com/rss")

        /**
         * The week to show at [nowMs]: the posted week, or, once it is over and the next post is not out yet, the same
         * table carried forward by whole weeks.
         */
        fun view(week: RadiogramWeek, nowMs: Long): WeekView {
            var start = week.start
            var b = RadiogramParser.place(week.slots, start)
            var projected = false
            var guard = 0
            while (b.isNotEmpty() && nowMs > b.last().endMs && guard++ < 520) { start = start.plusWeeks(1); b = RadiogramParser.place(week.slots, start); projected = true }
            return WeekView(week, b, projected, start)
        }

        fun toJson(w: RadiogramWeek) = JSONObject().apply {
            put("title", w.title); w.program?.let { put("program", it) }; put("start", w.start.toString()); put("end", w.end.toString())
            put("rowsFrom", w.rowsFrom); put("postUrl", w.postUrl); put("intro", w.intro); put("fetchedAt", w.fetchedAtMs)
            put("slots", JSONArray(w.slots.map { JSONObject().put("day", it.day.name).put("s", it.startMin).put("e", it.endMin).put("k", it.kHz).put("t", it.transmitter) }))
            put("lineup", JSONArray(w.lineup.map { JSONObject().put("o", it.offset).put("m", it.mode).put("t", it.title) }))
        }

        fun fromJson(o: JSONObject): RadiogramWeek {
            val slots = o.getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { s -> RadiogramSlot(DayOfWeek.valueOf(s.getString("day")), s.getInt("s"), s.getInt("e"), s.getInt("k"), s.getString("t")) } } }
            val lineup = o.optJSONArray("lineup")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { l -> LineupItem(l.getString("o"), l.getString("m"), l.getString("t")) } } } ?: emptyList()
            return RadiogramWeek(o.getString("title"), if (o.has("program")) o.getInt("program") else null, LocalDate.parse(o.getString("start")), LocalDate.parse(o.getString("end")),
                slots, o.getString("rowsFrom"), o.optString("postUrl"), o.optString("intro"), lineup, o.optLong("fetchedAt"))
        }
    }
}
