package app.radiomodem.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleEngineTest {
    private fun utc(s: String) = Instant.parse(s).toEpochMilli()
    private val boston = FaxStations.byId("boston")!!
    private fun sched(vararg b: Broadcast) = mapOf("boston" to StationSchedule("boston", b.toList()))

    @Test fun isoDayOfWeek() {
        assertEquals(6, ScheduleEngine.isoDay(utc("2026-10-03T00:00:00Z")))   // Saturday
        assertEquals(1, ScheduleEngine.isoDay(utc("2026-10-05T00:00:00Z")))   // Monday
        assertEquals(4, ScheduleEngine.isoDay(0))                              // 1 Jan 1970, Thursday
    }

    @Test fun dailyBroadcastsRepeatEachDayAcrossMidnight() {
        val s = sched(Broadcast(23 * 60 + 50, "LATE"), Broadcast(10, "EARLY"))
        val list = ScheduleEngine.instances(listOf(boston), s, utc("2026-10-03T23:00:00Z"), utc("2026-10-04T01:00:00Z"))
        assertEquals(listOf("LATE", "EARLY"), list.map { it.title })
        assertEquals(utc("2026-10-03T23:50:00Z"), list[0].startMs)
        assertEquals(utc("2026-10-04T00:10:00Z"), list[1].startMs)
    }

    @Test fun broadcastStillOnAirAtTheStartOfTheWindowIsIncluded() {
        val s = sched(Broadcast(60, "ONE"))
        val list = ScheduleEngine.instances(listOf(boston), s, utc("2026-10-03T01:05:00Z"), utc("2026-10-03T03:00:00Z"))
        assertEquals(1, list.size)
        assertTrue(list[0].isOn(utc("2026-10-03T01:05:00Z")))
    }

    @Test fun dayRestrictionIsHonoured() {
        val mondayOnly = sched(Broadcast(120, "MON", days = setOf(1)))
        val sat = ScheduleEngine.instances(listOf(boston), mondayOnly, utc("2026-10-03T00:00:00Z"), utc("2026-10-04T00:00:00Z"))
        val mon = ScheduleEngine.instances(listOf(boston), mondayOnly, utc("2026-10-05T00:00:00Z"), utc("2026-10-06T00:00:00Z"))
        assertTrue(sat.isEmpty()); assertEquals(1, mon.size)
    }

    @Test fun durationRunsToTheNextBroadcastWithinLimits() {
        val a = Broadcast(0, "A"); val b = Broadcast(5, "B"); val c = Broadcast(100, "C")
        val d = ScheduleEngine.durations(listOf(a, b, c))
        assertEquals(5, d[a]); assertEquals(14, d[b]); assertEquals(14, d[c])   // long gaps are capped
        assertEquals(3, ScheduleEngine.durations(listOf(Broadcast(0, "X"), Broadcast(1, "Y")))[Broadcast(0, "X")])  // floor of 3 minutes
    }

    @Test fun simultaneousBroadcastsGetSeparateLanes() {
        val s = sched(Broadcast(0, "A"), Broadcast(0, "B"), Broadcast(30, "C"))
        val list = ScheduleEngine.instances(listOf(boston), s, utc("2026-10-03T00:00:00Z"), utc("2026-10-03T01:00:00Z"))
        assertEquals(setOf(0, 1), list.filter { it.title != "C" }.map { it.lane }.toSet())
        assertEquals(0, list.first { it.title == "C" }.lane)
    }

    @Test fun nextUpIsOnAirOrNextToStart() {
        val s = sched(Broadcast(60, "ONE"), Broadcast(120, "TWO"))
        val l = ScheduleEngine.instances(listOf(boston), s, utc("2026-10-03T00:00:00Z"), utc("2026-10-04T00:00:00Z"))
        assertEquals("ONE", ScheduleEngine.nextUp(l, utc("2026-10-03T00:30:00Z"))!!.title)
        assertEquals("ONE", ScheduleEngine.nextUp(l, utc("2026-10-03T01:05:00Z"))!!.title)   // still on air
        assertEquals("TWO", ScheduleEngine.nextUp(l, utc("2026-10-03T01:30:00Z"))!!.title)
    }

    @Test fun frequenciesFollowHoursOfOperationOrTheBroadcastsOwnFrequency() {
        val s = sched(Broadcast(3 * 60, "NIGHT"), Broadcast(15 * 60, "DAY"))
        val l = ScheduleEngine.instances(listOf(boston), s, utc("2026-10-03T00:00:00Z"), utc("2026-10-04T00:00:00Z"))
        assertEquals(listOf(4235.0, 6340.5, 9110.0), ScheduleEngine.frequenciesFor(l.first { it.title == "NIGHT" }).map { it.kHz })
        assertEquals(listOf(6340.5, 9110.0, 12750.0), ScheduleEngine.frequenciesFor(l.first { it.title == "DAY" }).map { it.kHz })
        val kag = FaxStations.byId("kagoshima")!!
        val ks = mapOf("kagoshima" to StationSchedule("kagoshima", listOf(Broadcast(20, "FC", onlyKHz = 8658.0))))
        val ki = ScheduleEngine.instances(listOf(kag), ks, utc("2026-10-03T00:00:00Z"), utc("2026-10-04T00:00:00Z")).first()
        assertEquals(listOf(8658.0), ScheduleEngine.frequenciesFor(ki).map { it.kHz })
    }
}
