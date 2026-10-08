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

/** Where the schedule stands: this week's post is current, its week has finished and the next post is not out, or nothing has been read yet. */
enum class WeekState { Active, WaitingForNextPost, NoPost }

/** The posted week placed on the calendar. Nothing is carried forward: until a new post arrives the old dates stay. */
class WeekView(val week: RadiogramWeek, val broadcasts: List<RadiogramBroadcast>, val state: WeekState)

class RefreshResult(val message: String, val newPost: Boolean)

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

    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        val cur = load()
        // For a while after a new post, some of the site's servers still return the old feed while others return the new
        // one, and a kept-alive connection sticks to one of them. So: fresh connections, both addresses, a few tries,
        // stopping as soon as a newer post than the one held is seen, and trusting whichever feed has the newest post.
        var best: List<RadiogramPost>? = null
        attempts@ for (attempt in 1..4) {
            for (url in FEEDS) {
                val posts = runCatching { RadiogramFeed.parse(String(get(url, 3_000_000), Charsets.UTF_8)) }
                    .onFailure { Log.w("Radiogram", "feed $url failed", it) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                Log.d("Radiogram", "try $attempt $url: ${posts.size} posts, newest \"${posts.first().title.take(60)}\"")
                if ((RadiogramFeed.newestStart(posts)?.toEpochDay() ?: Long.MIN_VALUE) > (best?.let { RadiogramFeed.newestStart(it)?.toEpochDay() } ?: Long.MIN_VALUE)) best = posts
                val s = RadiogramFeed.newestStart(best ?: posts)
                if (cur.postUrl.isNotEmpty() && s != null && s.isAfter(cur.start)) break@attempts
            }
            if (attempt < 4) kotlinx.coroutines.delay(800)
        }
        val feed = best ?: return@withContext RefreshResult("Could not reach swradiogram.net.", false)
        val post = feed.firstOrNull { RadiogramParser.parseTitle(it.title) != null }
            ?: return@withContext RefreshResult("No schedule post found in the feed.", false)
        val dates = RadiogramParser.parseTitle(post.title)!!
        meta.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
        // Only a post for a later week than the one held counts as new; the old week's dates stay until then.
        if (cur.postUrl.isNotEmpty() && (cur.postUrl == post.link || !dates.start.isAfter(cur.start)) && (cur.rowsFrom == "image" || cur.postUrl == post.link))
            return@withContext RefreshResult("No new post yet. The latest is program ${cur.program ?: "?"}, ${shortRange(cur.start, cur.end)}.", false)

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
        meta.edit().putLong("newPostAt", System.currentTimeMillis()).apply()
        RefreshResult(
            "New post: program ${dates.program ?: "?"}, ${shortRange(dates.start, dates.end)}: " +
                if (from == "image") "table read from the post's picture." else "using the last known table. $note",
            true,
        )
    }

    private fun shortRange(a: LocalDate, b: LocalDate): String {
        fun d(x: LocalDate) = "${x.dayOfMonth} ${x.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
        return "${d(a)}–${d(b)}"
    }

    private val meta = ctx.getSharedPreferences("radiogram_meta", Context.MODE_PRIVATE)
    /** When the feed was last checked successfully (even if nothing new was there). */
    fun lastCheckedMs(): Long = meta.getLong("checkedAt", 0)

    private fun get(url: String, limit: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "RadioModem/0.1 (Android; schedule reader)")
        // The site serves a copy that can lag a new post by a long time unless the request asks for exactly this type.
        c.setRequestProperty("Accept", "application/rss+xml")
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("Connection", "close")   // a new connection each time, so a lagging server is not reused
        try {
            Log.d("Radiogram", "GET $url -> ${c.responseCode}, date=${c.getHeaderField("Date")} age=${c.getHeaderField("Age")} cache=${c.getHeaderField("Cache-Control")} via=${c.getHeaderField("Via")} server=${c.getHeaderField("Server")}")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { i -> val buf = ByteArray(16384); while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) error("too large") } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    companion object {
        private val FEEDS = listOf("https://swradiogram.tumblr.com/rss", "https://swradiogram.net/rss")

        /** The posted week on the calendar, and whether it is still current. */
        fun view(week: RadiogramWeek, nowMs: Long): WeekView {
            val b = RadiogramParser.place(week.slots, week.start)
            val state = when {
                week.postUrl.isEmpty() -> WeekState.NoPost
                b.isNotEmpty() && nowMs > b.last().endMs -> WeekState.WaitingForNextPost
                else -> WeekState.Active
            }
            return WeekView(week, b, state)
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
