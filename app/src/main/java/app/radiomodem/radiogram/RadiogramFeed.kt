package app.radiomodem.radiogram

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A post from the blog's RSS feed (Tumblr). */
class RadiogramPost(val title: String, val link: String, val publishedMs: Long, val html: String) {
    /** The weekly schedule is the first picture after the "schedule is in the image below" line. */
    val scheduleImageUrl: String? get() {
        val i = html.indexOf("SCHEDULE IS IN THE IMAGE", ignoreCase = true)
        return Regex("<img[^>]+src=\"([^\"]+)\"").find(html, maxOf(i, 0))?.groupValues?.get(1)
    }

    val text: String get() = RadiogramFeed.htmlToText(html)

    /** The opening of the post, before the lineup: where corrections and special notes appear. */
    val intro: String get() {
        val t = text
        val end = listOf("Here is the lineup", "A video of last week").map { t.indexOf(it) }.filter { it > 0 }.minOrNull() ?: t.length
        return t.substring(0, end).trim().take(900)
    }
}

object RadiogramFeed {
    fun parse(xml: String): List<RadiogramPost> =
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            fun tag(n: String) = Regex("<$n>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape)
            val title = tag("title") ?: return@mapNotNull null
            RadiogramPost(title.trim(), tag("link").orEmpty().trim(), pubDate(tag("pubDate")), tag("description").orEmpty())
        }.toList()

    private fun pubDate(s: String?): Long = runCatching { ZonedDateTime.parse(s!!.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** XML text and HTML text share the entities that matter here. */
    fun unescape(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                "ndash" -> "–"; "mdash" -> "—"; "rsquo" -> "’"; "lsquo" -> "‘"; "ldquo" -> "“"; "rdquo" -> "”"; "hellip" -> "…"
                else -> m.value
            }
        }
    }

    fun htmlToText(html: String): String = unescape(
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</(p|div|li|h\\d)>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), ""),
    ).replace('\u00a0', ' ').lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
}
