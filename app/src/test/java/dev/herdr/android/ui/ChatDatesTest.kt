package dev.herdr.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class ChatDatesTest {
    @Test fun messageDatesFollowThePhonesZoneAndNeverInventMissingTimestamps() {
        val timestamp = Instant.parse("2026-10-09T00:30:00Z").epochSecond
        assertEquals(LocalDate.of(2026, 10, 9), chatMessageDate(timestamp, ZoneId.of("Europe/Berlin")))
        assertEquals(LocalDate.of(2026, 10, 8), chatMessageDate(timestamp, ZoneId.of("America/Los_Angeles")))
        assertNull(chatMessageDate(null))
        assertNull(chatMessageDate(-1))
        assertNull(chatMessageDate(Long.MAX_VALUE))
    }

    @Test fun relativeDayCaptionsAndOlderDatesUseTheActualCalendarDate() {
        val today = LocalDate.of(2026, 10, 9)
        assertEquals("Today", chatDateLabel(today, today, Locale.US))
        assertEquals("Yesterday", chatDateLabel(today.minusDays(1), today, Locale.US))
        assertEquals("Oct 7, 2026", chatDateLabel(today.minusDays(2), today, Locale.US))
    }
}
