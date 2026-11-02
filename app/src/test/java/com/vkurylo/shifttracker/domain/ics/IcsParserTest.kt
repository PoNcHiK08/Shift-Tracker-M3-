package com.vkurylo.shifttracker.domain.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class IcsParserTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val parser = IcsParser(warsaw)

    private fun ics(vararg events: String) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n")
        events.forEach { append(it.trimIndent().replace("\n", "\r\n")).append("\r\n") }
        append("END:VCALENDAR\r\n")
    }

    @Test
    fun `parses floating local times`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:1@test
                SUMMARY:Morning shift
                DTSTART:20261102T080000
                DTEND:20261102T160000
                END:VEVENT
                """,
            ),
        )
        assertEquals(1, result.events.size)
        val event = result.events.single()
        assertEquals("1@test", event.uid)
        assertEquals("Morning shift", event.summary)
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), event.start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 16, 0), event.end)
    }

    @Test
    fun `converts UTC times to the local zone`() {
        // Winter: Warsaw is UTC+1. Summer: UTC+2.
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                DTSTART:20261102T070000Z
                DTEND:20261102T150000Z
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:20260701T060000Z
                DTEND:20260701T140000Z
                END:VEVENT
                """,
            ),
        )
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 0), result.events[0].start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), result.events[1].start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 16, 0), result.events[1].end)
    }

    @Test
    fun `respects TZID and converts between zones`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Kyiv:20261103T100000
                DTEND;TZID=Europe/Kyiv:20261103T183000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART;TZID="Central European Standard Time":20261104T090000
                DTEND;TZID="Central European Standard Time":20261104T170000
                END:VEVENT
                """,
            ),
        )
        // Kyiv is one hour ahead of Warsaw.
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), result.events[0].start)
        assertEquals(LocalDateTime.of(2026, 11, 3, 17, 30), result.events[0].end)
        // Outlook's Windows zone name maps to Warsaw.
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), result.events[1].start)
    }

    @Test
    fun `handles overnight events and DURATION`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                SUMMARY:Night
                DTSTART:20261105T220000
                DTEND:20261106T060000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                SUMMARY:With duration
                DTSTART:20261107T090000
                DURATION:PT7H30M
                END:VEVENT
                """,
            ),
        )
        assertEquals(LocalDateTime.of(2026, 11, 6, 6, 0), result.events[0].end)
        assertEquals(LocalDateTime.of(2026, 11, 7, 16, 30), result.events[1].end)
    }

    @Test
    fun `unfolds long lines and unescapes text`() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
            "SUMMARY:Shift at Caf\\, Main\\; floor\r\n  two\r\n" +
            "DESCRIPTION:Line one\\nLine two\r\n" +
            "DTSTART:20261108T080000\r\nDTEND:20261108T120000\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = parser.parse(text).events.single()
        assertEquals("Shift at Caf, Main; floor two", event.summary)
        assertEquals("Line one\nLine two", event.description)
    }

    @Test
    fun `nested alarms do not override event fields`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                SUMMARY:Real summary
                DTSTART:20261109T080000
                DTEND:20261109T160000
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                SUMMARY:Alarm summary
                TRIGGER:-PT15M
                END:VALARM
                END:VEVENT
                """,
            ),
        )
        assertEquals("Real summary", result.events.single().summary)
        assertNull(result.events.single().description)
    }

    @Test
    fun `skips all-day, cancelled, broken and too long events`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                SUMMARY:Holiday
                DTSTART;VALUE=DATE:20261111
                DTEND;VALUE=DATE:20261112
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                STATUS:CANCELLED
                DTSTART:20261112T080000
                DTEND:20261112T160000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:20261113T080000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:20261114T080000
                DTEND:20261116T080000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:not-a-date
                DTEND:20261116T080000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:20261115T1000
                DTEND:20261115T1400
                END:VEVENT
                """,
            ),
        )
        assertEquals(1, result.events.size)
        assertEquals(LocalDateTime.of(2026, 11, 15, 10, 0), result.events.single().start)
        assertEquals(5, result.skippedCount)
    }

    @Test
    fun `events are returned sorted by start`() {
        val result = parser.parse(
            ics(
                """
                BEGIN:VEVENT
                DTSTART:20261120T080000
                DTEND:20261120T100000
                END:VEVENT
                """,
                """
                BEGIN:VEVENT
                DTSTART:20261101T080000
                DTEND:20261101T100000
                END:VEVENT
                """,
            ),
        )
        assertEquals(listOf(1, 20), result.events.map { it.start.dayOfMonth })
    }

    @Test
    fun `garbage input yields no events`() {
        val result = parser.parse("hello\nworld")
        assertEquals(0, result.events.size)
    }
}
