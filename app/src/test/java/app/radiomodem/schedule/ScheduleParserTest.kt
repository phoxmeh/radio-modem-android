package app.radiomodem.schedule

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScheduleParserTest {
    private val set by lazy { ScheduleParser.parse(File("src/main/assets/schedules/worldwide.txt").readText()) }
    private fun sched(id: String) = set.schedules[id] ?: fail("no schedule for $id").let { error("unreachable") }
    private fun at(id: String, hhmm: Int) = sched(id).broadcasts.filter { it.minute == (hhmm / 100) * 60 + hhmm % 100 }

    @Test fun publishedDateComesFromTheSnapshotHeader() = assertEquals("March 7, 2025", set.published)

    @Test fun everyListedStationHasABroadcastList() {
        val expected = listOf("tokyo", "kagoshima", "seoul", "bangkok", "kyodo", "valparaiso", "punta_arenas", "sydney_ns", "kodiak",
            "pt_reyes", "new_orleans", "boston", "charleville", "wiluna", "honolulu", "athens", "murmansk", "hamburg", "northwood")
        for (id in expected) assertTrue("$id has broadcasts", (set.schedules[id]?.broadcasts?.size ?: 0) >= 3)
        // Station ids must exist in the catalog so the UI can show them.
        for (id in set.schedules.keys) assertNotNull("catalog entry for $id", FaxStations.byId(id))
    }

    @Test fun bostonTwoColumnTimesBecomeTwoCycles() {
        // "0243/1405  BROADCAST SCHEDULE (PART 1)" is on air at both 02:43 and 14:05
        assertEquals("BROADCAST SCHEDULE (PART 1)", at("boston", 243).single().title)
        assertEquals("BROADCAST SCHEDULE (PART 1)", at("boston", 1405).single().title)
        // "0233/----  PRELIMINARY SURFACE ANALYSIS" only at 02:33, "----/1453" only at 14:53
        assertEquals(1, at("boston", 233).size)
        assertTrue(at("boston", 1453).single().title.contains("PRELIMINARY SURFACE ANALYSIS"))
        assertTrue(sched("boston").broadcasts.size > 60)
        assertEquals(120, at("boston", 243).single().lpm); assertEquals(576, at("boston", 243).single().ioc)
    }

    @Test fun otherNoaaTableStations() {
        assertTrue(at("seoul", 33).single().title.startsWith("SEA-SHORE WEATHER OBSERVATION"))
        assertTrue(at("seoul", 1233).isNotEmpty())
        assertTrue(at("tokyo", 20).single().title.contains("96HR SURFACE PRESSURE"))
        assertEquals("SATELLITE IMAGE", at("valparaiso", 1130).single().title)
        assertTrue("CBM schedule kept separate", at("punta_arenas", 1605).single().title.contains("12HR SURFACE FORECAST"))
        assertTrue(at("valparaiso", 1605).isEmpty())
        assertEquals(90, at("murmansk", 1850).single().lpm)
        assertTrue(at("athens", 845).single().title.startsWith("SURFACE ANALYSIS"))
        assertTrue(at("sydney_ns", 1121).single().title.startsWith("ICE ANALYSIS GULF OF ST. LAWRENCE"))
    }

    @Test fun charlevilleAndWilunaShareOneSchedule() {
        assertEquals(sched("charleville").broadcasts, sched("wiluna").broadcasts)
        assertTrue(sched("charleville").broadcasts.any { it.title.startsWith("Australian MSLP Prog") })
    }

    @Test fun hamburgDwdDotTimes() {
        val h = sched("hamburg")
        assertEquals("Surface Analysis North Atlantic, Europe", h.broadcasts.first { it.minute == 4 * 60 + 30 }.title)
        assertTrue(h.broadcasts.any { it.minute == 5 * 60 + 12 && it.title.startsWith("36HR-forecast surface pressure") })
        assertTrue(h.broadcasts.size >= 20)
    }

    @Test fun northwoodProductTimePairs() {
        val n = sched("northwood")
        assertEquals("18Z SFC ANALYSIS", n.broadcasts.first { it.minute == 0 }.title)
        assertEquals("06Z SFC ANALYSIS", n.broadcasts.first { it.minute == 12 * 60 }.title)
        assertTrue(n.broadcasts.size >= 60)
    }

    @Test fun perFrequencyStationsKeepTheirFrequency() {
        val k = sched("kagoshima")
        val a = k.broadcasts.first { it.onlyKHz == 8658.0 && it.minute == 20 }
        assertTrue(a.title.contains("SURFACE PRESSURE FORECAST"))
        assertTrue(sched("kyodo").broadcasts.any { it.onlyKHz == 16971.0 && it.minute == 20 * 60 + 30 })
    }

    @Test fun dayRestrictionsFromFootnotes() {
        // Kagoshima "0110 BROADCAST SCHEDULE (1)" with "(1) MONDAY ONLY"
        assertEquals(setOf(1), sched("kagoshima").broadcasts.first { it.minute == 70 && it.onlyKHz == 8658.0 }.days)
    }

    @Test fun parseDays() {
        assertEquals(setOf(1), ScheduleParser.parseDays("MONDAY ONLY"))
        assertEquals(setOf(2, 3, 4, 5, 6, 7), ScheduleParser.parseDays("TUESDAY – SUNDAY"))
        assertEquals(setOf(7, 1), ScheduleParser.parseDays("SUNDAY, MONDAY"))
        assertEquals(setOf(2, 5), ScheduleParser.parseDays("TUESDAY AND FRIDAY ONLY"))
        assertEquals(setOf(2, 3, 4, 5, 6), ScheduleParser.parseDays("Tue Wed Thu Fri? Sat"))
        assertEquals(setOf(1, 2, 3, 4, 5, 6), ScheduleParser.parseDays("Monday – Saturday"))
        assertNull(ScheduleParser.parseDays("AUGUST TO DECEMBER"))
    }

    @Test fun chartClassification() {
        assertEquals(ChartType.Satellite, ChartType.of("METEOROLOGICAL SATELLITE PICTURE (MSAT)"))
        assertEquals(ChartType.Waves, ChartType.of("24HR WIND/WAVE FORECAST"))   // waves beat forecast
        assertEquals(ChartType.Ice, ChartType.of("SEA ICE ANALYSIS"))
        assertEquals(ChartType.Forecast, ChartType.of("36HR SURFACE PRESSURE PROG"))
        assertEquals(ChartType.Analysis, ChartType.of("SURFACE ANALYSIS"))
        assertEquals(ChartType.Ocean, ChartType.of("SEA SURFACE TEMPERATURE CHART"))
        assertEquals(ChartType.Other, ChartType.of("TEST PATTERN"))
    }

    @Test fun frequencyWindowsWrapMidnight() {
        val night = HourWindow.of(2200, 500)
        assertTrue(23 * 60 in night); assertTrue(60 in night); assertFalse(12 * 60 in night)
        val boston = FaxStations.byId("boston")!!
        assertEquals(listOf(4235.0, 6340.5, 9110.0), boston.activeAt(3 * 60).map { it.kHz })   // 03:00 UTC
        assertEquals(listOf(6340.5, 9110.0, 12750.0), boston.activeAt(15 * 60).map { it.kHz }) // 15:00 UTC
    }
}

/** Text as PDFBox extracts it on the phone: collapsed spacing, and rows whose time and title land on separate lines. */
class PdfBoxLayoutTest {
    private val set = ScheduleParser.parse(javaClass.getResource("/pdfbox_excerpt.txt")!!.readText())
    private fun at(id: String, hhmm: Int) = set.schedules[id]!!.broadcasts.filter { it.minute == (hhmm / 100) * 60 + hhmm % 100 }

    @Test fun tableRowsWithSingleSpaces() {
        assertEquals("PRELIMINARY SURFACE ANALYSIS", at("boston", 233).single().title)
        assertEquals(120, at("boston", 233).single().lpm)
    }

    @Test fun timeAndTitleOnSeparateLines() {
        assertEquals("RETRANSMISSION OF 0210", at("tokyo", 1420).single().title)   // "-------/1420" then the title
        assertEquals("MANAM", at("seoul", 100).single().title)                     // "0100/-------" then "MANAM 120/576"
    }

    @Test fun northwoodTokensAndFooterStop() {
        val n = set.schedules["northwood"]!!.broadcasts
        assertEquals("18Z SFC ANALYSIS", n.first { it.minute == 0 }.title)
        assertEquals("06Z SFC ANALYSIS", n.first { it.minute == 12 * 60 }.title)
        assertTrue(n.none { it.title.contains("MAPS") })
    }

    @Test fun hamburgDotTimes() {
        assertEquals("Surface Analysis North Atlantic, Europe", set.schedules["hamburg"]!!.broadcasts.first { it.minute == 4 * 60 + 30 }.title)
    }
}
