package app.radiomodem.radiogram

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class RadiogramParserTest {
    private val clean = """
        Shortwave Radiogram Transmission Schedule
        UTC Day UTC Time Frequency kHz Transmitter
        Thursday 2330-2400 9265 WINB Pennsylvania
        Saturday 0230-0300 9265 WINB Pennsylvania
        Saturday 2300-2330 9455 WRMI Florida
        Sunday 1430-1500 9955 WRMI Florida
        Monday 0030-0100 5050 WRMI Florida
        Monday 0800-0830 5850 WRMI Florida
        Wednesday 1330-1400 15770 WRMI Florida
        Thursday 0930-1000 7780 WRMI Florida
    """.trimIndent()

    @Test fun readsAllRowsOfTheTable() {
        val rows = RadiogramParser.parseRows(clean)
        assertEquals(8, rows.size)
        assertEquals(RadiogramSlot(DayOfWeek.THURSDAY, 23 * 60 + 30, 24 * 60, 9265, "WINB Pennsylvania"), rows[0])
        assertEquals(15770, rows[6].kHz)
    }

    @Test fun toleratesTypicalOcrMistakes() {
        val messy = """
            | UTC Day | UTC Time | Frequency kHz | Transmitter |
            | Thursday | 2330-24O0 | 9265 | WINB Pennsy1vania |
            Satur day 0230 - 0300 9265 WINB Pennsylvania
            | Saturday | 2300-2330 | 945S | WRMI Fiorida |
            Sunday 1430-1500 9955 WRM1 Florida
            Mondav 0030-0100 5O50 WRMI Florida
            Monday 0800-0830 5850 WRMI Florida |
            Wednesday I330-1400 15770 WRMI Florida
            Thursday 0930-1000 7780 WRMI Florida
        """.trimIndent()
        val rows = RadiogramParser.parseRows(messy)
        assertEquals(8, rows.size)
        assertEquals(24 * 60, rows[0].endMin)
        assertEquals("WINB Pennsylvania", rows[0].transmitter)
        assertEquals(9455, rows[2].kHz)
        assertEquals("WRMI Florida", rows[3].transmitter)
        assertEquals(5050, rows[4].kHz)
        assertEquals(13 * 60 + 30, rows[6].startMin)
    }

    @Test fun dropsRowsThatDoNotCheckOut() {
        assertNull(RadiogramParser.parseRow("Friday 2500-2600 9265 WINB Pennsylvania"))       // impossible time
        assertNull(RadiogramParser.parseRow("Friday 2330-2300 9265 WINB Pennsylvania"))       // ends before it starts
        assertNull(RadiogramParser.parseRow("Friday 2330-2400 926 WINB Pennsylvania"))        // not a shortwave frequency
        assertNull(RadiogramParser.parseRow("Shortwave Radiogram Transmission Schedule"))
        assertNull(RadiogramParser.parseRow("UTC Day UTC Time Frequency kHz Transmitter"))
    }

    @Test fun placesRowsOnTheCalendarAcrossTheWeek() {
        val week = RadiogramParser.place(RadiogramParser.parseRows(clean), LocalDate.of(2026, 10, 1))
        fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
        assertEquals("2026-10-01T23:30:00Z", iso(week[0].startMs))
        assertEquals("2026-10-02T00:00:00Z", iso(week[0].endMs))     // 2400 is midnight
        assertEquals("2026-10-03T02:30:00Z", iso(week[1].startMs))
        assertEquals("2026-10-03T23:00:00Z", iso(week[2].startMs))   // second Saturday row is the same day
        assertEquals("2026-10-04T14:30:00Z", iso(week[3].startMs))
        assertEquals("2026-10-05T00:30:00Z", iso(week[4].startMs))
        assertEquals("2026-10-05T08:00:00Z", iso(week[5].startMs))   // second Monday row, later the same day
        assertEquals("2026-10-07T13:30:00Z", iso(week[6].startMs))
        assertEquals("2026-10-08T09:30:00Z", iso(week[7].startMs))   // the second Thursday is the following week
    }

    @Test fun readsTheDatesInPostTitles() {
        val a = RadiogramParser.parseTitle("Shortwave Radiogram, 1-8 October 2026 (program 469): Off to a rocky start")!!
        assertEquals(LocalDate.of(2026, 10, 1), a.start); assertEquals(LocalDate.of(2026, 10, 8), a.end); assertEquals(469, a.program)
        val b = RadiogramParser.parseTitle("Shortwave Radiogram, 27 August-3 September 2026 (program 464): Official digital modes")!!
        assertEquals(LocalDate.of(2026, 8, 27), b.start); assertEquals(LocalDate.of(2026, 9, 3), b.end)
        val c = RadiogramParser.parseTitle("Shortwave Radiogram, 26 December 2026-2 January 2027 (program 480)")!!
        assertEquals(LocalDate.of(2026, 12, 26), c.start); assertEquals(LocalDate.of(2027, 1, 2), c.end)
        val d = RadiogramParser.parseTitle("Shortwave Radiogram. 9-16 July 2026 (program 457): Digital m")!!
        assertEquals(LocalDate.of(2026, 7, 9), d.start)
        assertNull(RadiogramParser.parseTitle("Experiments with MFSK Wide modes"))
    }

    @Test fun readsTheLineup() {
        val text = """
            Here is the lineup for Shortwave Radiogram, program 469, 1-8 October 2026, in MFSK modes as noted:

             1:48  MFSK32: Program preview
             2:58  MFSK32: Finally, a true blue rose exists
             8:02  MFSK64: Digital HF radio to remotest locations
            11:04  MFSK64: This week’s images
            28:11  MFSK32: Closing announcements

            Please send reception reports to radiogram@verizon.net
        """.trimIndent()
        val l = RadiogramParser.parseLineup(text)
        assertEquals(5, l.size)
        assertEquals(LineupItem("1:48", "MFSK32", "Program preview"), l[0])
        assertEquals("MFSK64", l[3].mode)
        assertTrue(RadiogramParser.parseLineup("no lineup here").isEmpty())
    }
}

class RadiogramFeedTest {
    private val posts = RadiogramFeed.parse(javaClass.getResource("/radiogram_feed.xml")!!.readText())

    @Test fun readsPostsFromTheRealFeed() {
        assertEquals(2, posts.size)
        val p = posts[0]
        assertTrue(p.title.startsWith("Shortwave Radiogram, 1-8 October 2026 (program 469)"))
        assertTrue(p.link.startsWith("https://swradiogram.net/post/"))
        assertTrue(p.publishedMs > 0)
    }

    @Test fun findsTheSchedulePictureNotTheListenerImages() {
        val url = posts[0].scheduleImageUrl!!
        assertTrue(url.startsWith("https://64.media.tumblr.com/"))
        assertTrue("first picture after the schedule line", posts[0].html.indexOf(url) > posts[0].html.indexOf("SCHEDULE IS IN THE IMAGE"))
    }

    @Test fun readsDatesLineupAndNotesFromAPost() {
        val p = posts[0]
        val t = RadiogramParser.parseTitle(p.title)!!
        assertEquals(LocalDate.of(2026, 10, 1), t.start)
        val lineup = RadiogramParser.parseLineup(p.text)
        assertEquals(5, lineup.size)
        assertEquals("Program preview", lineup[0].title)
        assertTrue("intro carries the special notes", p.intro.contains("rocky") || p.intro.contains("WINB"))
        assertFalse(p.intro.contains("<"))
    }
}
