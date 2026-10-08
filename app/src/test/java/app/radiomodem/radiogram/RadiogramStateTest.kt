package app.radiomodem.radiogram

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RadiogramStateTest {
    private fun week(post: String = "https://swradiogram.net/post/1") =
        RadiogramWeek("Shortwave Radiogram, 1-8 October 2026 (program 469)", 469, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8),
            RadiogramEntries.bundledSlots, "image", post)

    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun theWeekIsActiveUntilItsLastTransmissionEnds() {
        assertEquals(WeekState.Active, RadiogramRepository.view(week(), at("2026-10-05T12:00:00Z")).state)
        assertEquals(WeekState.Active, RadiogramRepository.view(week(), at("2026-10-08T09:45:00Z")).state)   // last one on air
    }

    @Test fun afterTheWeekTheOldDatesStayAndNothingIsCarriedForward() {
        val v = RadiogramRepository.view(week(), at("2026-10-08T15:00:00Z"))
        assertEquals(WeekState.WaitingForNextPost, v.state)
        assertEquals(8, v.broadcasts.size)
        assertEquals("2026-10-01T23:30:00Z", Instant.ofEpochMilli(v.broadcasts.first().startMs).toString())
        // A month later, with no new post, it is still last month's week.
        val later = RadiogramRepository.view(week(), at("2026-11-12T00:00:00Z"))
        assertEquals(WeekState.WaitingForNextPost, later.state)
        assertEquals("2026-10-01T23:30:00Z", Instant.ofEpochMilli(later.broadcasts.first().startMs).toString())
        assertTrue(later.broadcasts.all { it.endMs < at("2026-11-12T00:00:00Z") })   // all of it counts as past
    }

    @Test fun withoutAPostThereAreNoDates() = assertEquals(WeekState.NoPost, RadiogramRepository.view(week(post = ""), at("2026-10-05T12:00:00Z")).state)

    @Test fun checkingWaitsForThursdayEveningThenRepeats() {
        val w = week()
        val h = 3_600_000L
        assertEquals(at("2026-10-08T21:00:00Z") - at("2026-10-05T12:00:00Z"), RadiogramPoller.delayMs(w, at("2026-10-05T12:00:00Z")))
        assertEquals(h, RadiogramPoller.delayMs(w, at("2026-10-08T22:00:00Z")))           // due, post not there: hourly
        assertEquals(6 * h, RadiogramPoller.delayMs(w, at("2026-10-10T00:00:00Z")))       // a day late: every six hours
        assertEquals(15 * 60_000L, RadiogramPoller.delayMs(week(post = ""), at("2026-10-05T12:00:00Z")))
    }
}
