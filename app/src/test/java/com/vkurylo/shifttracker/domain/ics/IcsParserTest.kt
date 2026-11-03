package com.vkurylo.shifttracker.domain.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun event(start: String, end: String, extra: String = "") = """
        BEGIN:VEVENT
        $extra
        DTSTART$start
        DTEND$end
        END:VEVENT
    """.lines().filter { it.isNotBlank() }.joinToString("\n")

    private val utc = IcsParser(ZoneId.of("UTC"))

    // --- Time zones the parser can't resolve -----------------------------------------------------

    @Test
    fun `unknown TZID keeps the clock time and is reported`() {
        val result = parser.parse(ics(event(";TZID=Some/Unknown_Zone:20261102T080000", ";TZID=Some/Unknown_Zone:20261102T160000")))
        assertEquals(setOf("Some/Unknown_Zone"), result.unknownZones)
        val e = result.events.single()
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 16, 0), e.end)
    }

    @Test
    fun `known zones are not reported`() {
        val result = parser.parse(ics(event(";TZID=Europe/Warsaw:20261102T080000", ";TZID=Europe/Warsaw:20261102T160000")))
        assertTrue(result.unknownZones.isEmpty())
    }

    @Test
    fun `path-style TZID resolves to its IANA zone`() {
        // Old Thunderbird/Lightning exports. 08:00 in Warsaw in November (UTC+1) is 07:00 UTC.
        val result = utc.parse(
            ics(event(";TZID=/mozilla.org/20050126_1/Europe/Warsaw:20261102T080000", ";TZID=/mozilla.org/20050126_1/Europe/Warsaw:20261102T160000")),
        )
        assertTrue(result.unknownZones.isEmpty())
        assertEquals(LocalDateTime.of(2026, 11, 2, 7, 0), result.events.single().start)
    }

    @Test
    fun `VTIMEZONE with X-LIC-LOCATION resolves a custom TZID, without it the zone is reported`() {
        val zones = """
            BEGIN:VTIMEZONE
            TZID:Firma Time
            X-LIC-LOCATION:Europe/Warsaw
            BEGIN:STANDARD
            DTSTART:19701025T030000
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VTIMEZONE
            TZID:Custom Rules Only
            BEGIN:STANDARD
            DTSTART:19701025T030000
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            END:STANDARD
            END:VTIMEZONE
        """.trimIndent()
        val result = utc.parse(
            ics(
                zones,
                event(";TZID=\"Firma Time\":20261102T080000", ";TZID=\"Firma Time\":20261102T160000"),
                event(";TZID=Custom Rules Only:20261103T080000", ";TZID=Custom Rules Only:20261103T160000"),
            ),
        )
        assertEquals(LocalDateTime.of(2026, 11, 2, 7, 0), result.events[0].start) // resolved via X-LIC-LOCATION
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 0), result.events[1].start) // custom rules: not interpreted
        assertEquals(setOf("Custom Rules Only"), result.unknownZones)
    }

    // --- Broken events don't fail the file ------------------------------------------------------

    @Test
    fun `an absurd DURATION skips that event only`() {
        val result = parser.parse(
            ics(
                "BEGIN:VEVENT\nDTSTART:20261102T080000\nDURATION:P9999999999999999D\nEND:VEVENT",
                event(":20261103T080000", ":20261103T160000"),
            ),
        )
        assertEquals(1, result.skippedCount)
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 0), result.events.single().start)
    }

    // --- Daylight saving time in Warsaw (parsing/conversion only; pay is a separate question) ----

    @Test
    fun `UTC times convert correctly across the spring DST change`() {
        // 29 Mar 2026: 02:00 CET -> 03:00 CEST.
        val result = parser.parse(ics(event(":20260329T003000Z", ":20260329T013000Z")))
        val e = result.events.single()
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), e.start) // UTC+1
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), e.end) // UTC+2: one real hour, two on the clock
    }

    @Test
    fun `UTC times convert correctly across the autumn DST change`() {
        // 25 Oct 2026: 03:00 CEST -> 02:00 CET, so 02:00-03:00 happens twice.
        val result = parser.parse(ics(event(":20261024T223000Z", ":20261025T013000Z")))
        val e = result.events.single()
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 30), e.start) // UTC+2
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), e.end) // UTC+1 (the second 02:30)
    }

    @Test
    fun `Europe-Warsaw times convert to another zone on both sides of DST`() {
        val spring = utc.parse(ics(event(";TZID=Europe/Warsaw:20260329T013000", ";TZID=Europe/Warsaw:20260329T033000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 3, 29, 0, 30), spring.start) // CET  (+1)
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), spring.end) // CEST (+2)
        val autumn = utc.parse(ics(event(";TZID=Europe/Warsaw:20261025T013000", ";TZID=Europe/Warsaw:20261025T033000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 10, 24, 23, 30), autumn.start) // CEST (+2)
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), autumn.end) // CET  (+1)
    }

    @Test
    fun `zoned non-existent and repeated Warsaw times follow java-time rules`() {
        // 02:30 doesn't exist on 29 Mar: java.time moves it forward by the gap (03:30 CEST = 01:30 UTC).
        val gap = utc.parse(ics(event(";TZID=Europe/Warsaw:20260329T023000", ";TZID=Europe/Warsaw:20260329T050000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), gap.start)
        // 02:30 happens twice on 25 Oct: java.time takes the earlier offset (CEST, +2 -> 00:30 UTC).
        val overlap = utc.parse(ics(event(";TZID=Europe/Warsaw:20261025T023000", ";TZID=Europe/Warsaw:20261025T050000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 30), overlap.start)
    }

    @Test
    fun `floating non-existent and repeated local times are kept as written`() {
        // Floating times have no zone, so there is nothing to resolve: the clock time is kept.
        val gap = parser.parse(ics(event(":20260329T023000", ":20260329T060000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), gap.start)
        val overlap = parser.parse(ics(event(":20261025T023000", ":20261025T060000"))).events.single()
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), overlap.start)
    }
}
