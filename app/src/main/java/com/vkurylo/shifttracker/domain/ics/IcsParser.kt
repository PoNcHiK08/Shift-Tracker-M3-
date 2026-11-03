package com.vkurylo.shifttracker.domain.ics

import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A timed calendar event, already converted to the target time zone. */
data class IcsEvent(
    val uid: String?,
    val summary: String?,
    val description: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
)

data class IcsParseResult(
    val events: List<IcsEvent>,
    /** Events that can't become shifts: all-day, missing times, longer than 24 h, broken values, etc. */
    val skippedCount: Int,
    /**
     * TZIDs the parser could not resolve. Events using them keep their clock times as written, i.e.
     * they are taken as local time; the import preview tells the user so they can check them.
     */
    val unknownZones: Set<String> = emptySet(),
)

/**
 * A small RFC 5545 parser that understands what work-schedule exports actually contain:
 * folded lines, property parameters (TZID, VALUE=DATE), UTC/floating/zoned times,
 * DURATION instead of DTEND, escaped text and nested components such as VALARM.
 * Recurring rules (RRULE) are not expanded; only the first occurrence is imported.
 *
 * Time zones: IANA ids, path-style ids ("/mozilla.org/20050126_1/Europe/Warsaw"), common Outlook
 * (Windows) names, and VTIMEZONE blocks that name their IANA zone in X-LIC-LOCATION. Custom VTIMEZONE
 * rules (STANDARD/DAYLIGHT offsets) are not interpreted; such zones are reported as unknown.
 */
class IcsParser(private val targetZone: ZoneId = ZoneId.systemDefault()) {

    fun parse(text: String): IcsParseResult {
        val lines = unfold(text).mapNotNull(::parseLine)
        val zones = ZoneResolver(vtimezoneAliases(lines))
        val events = mutableListOf<IcsEvent>()
        var skipped = 0

        var inEvent = false
        var nestedDepth = 0
        var props = mutableMapOf<String, IcsProperty>()

        for (prop in lines) {
            when {
                prop.name == "BEGIN" && prop.value.equals("VEVENT", ignoreCase = true) && !inEvent -> {
                    inEvent = true
                    nestedDepth = 0
                    props = mutableMapOf()
                }
                prop.name == "END" && prop.value.equals("VEVENT", ignoreCase = true) && inEvent && nestedDepth == 0 -> {
                    // One broken event (an absurd DURATION, a date out of range...) is skipped and
                    // counted; it must not fail the whole file. Only those expected errors are caught.
                    val event = try {
                        buildEvent(props, zones)
                    } catch (_: DateTimeException) {
                        null
                    } catch (_: ArithmeticException) {
                        null
                    }
                    if (event != null) events += event else skipped++
                    inEvent = false
                }
                inEvent && prop.name == "BEGIN" -> nestedDepth++
                inEvent && prop.name == "END" -> nestedDepth = (nestedDepth - 1).coerceAtLeast(0)
                inEvent && nestedDepth == 0 -> props.putIfAbsent(prop.name, prop)
            }
        }
        return IcsParseResult(events.sortedBy { it.start }, skipped, zones.unknown.toSet())
    }

    /** TZID -> IANA zone from VTIMEZONE blocks that carry X-LIC-LOCATION (Thunderbird, Google). */
    private fun vtimezoneAliases(lines: List<IcsProperty>): Map<String, String> {
        val aliases = mutableMapOf<String, String>()
        var inZone = false
        var tzid: String? = null
        var location: String? = null
        for (prop in lines) {
            when {
                prop.name == "BEGIN" && prop.value.equals("VTIMEZONE", ignoreCase = true) -> {
                    inZone = true; tzid = null; location = null
                }
                prop.name == "END" && prop.value.equals("VTIMEZONE", ignoreCase = true) -> {
                    val id = tzid
                    val loc = location
                    if (inZone && id != null && loc != null) aliases[cleanTzid(id)] = loc.trim()
                    inZone = false
                }
                inZone && prop.name == "TZID" -> tzid = prop.value
                inZone && prop.name == "X-LIC-LOCATION" -> location = prop.value
            }
        }
        return aliases
    }

    private fun buildEvent(props: Map<String, IcsProperty>, zones: ZoneResolver): IcsEvent? {
        val startProp = props["DTSTART"] ?: return null
        if (props["STATUS"]?.value.equals("CANCELLED", ignoreCase = true)) return null
        if (isDateOnly(startProp)) return null

        val start = parseDateTime(startProp, zones) ?: return null
        val end = props["DTEND"]?.let { if (isDateOnly(it)) null else parseDateTime(it, zones) }
            ?: props["DURATION"]?.let { parseDuration(it.value) }?.let { start.plus(it) }
            ?: return null

        val length = Duration.between(start, end)
        if (length.isNegative || length.isZero || length > Duration.ofHours(24)) return null

        return IcsEvent(
            uid = props["UID"]?.value?.takeIf { it.isNotBlank() },
            summary = props["SUMMARY"]?.value?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() },
            description = props["DESCRIPTION"]?.value?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() },
            start = start,
            end = end,
        )
    }

    private fun isDateOnly(prop: IcsProperty): Boolean =
        prop.params["VALUE"].equals("DATE", ignoreCase = true) || prop.value.trim().length == 8

    private fun parseDateTime(prop: IcsProperty, zones: ZoneResolver): LocalDateTime? {
        val raw = prop.value.trim()
        return try {
            when {
                raw.endsWith("Z", ignoreCase = true) -> {
                    val local = LocalDateTime.parse(raw.dropLast(1), dateTimeFormat(raw.length - 1))
                    local.atOffset(ZoneOffset.UTC).atZoneSameInstant(targetZone).toLocalDateTime()
                }
                prop.params["TZID"] != null -> {
                    val local = LocalDateTime.parse(raw, dateTimeFormat(raw.length))
                    // An unknown zone keeps the clock time as written (taken as local time) and is
                    // reported, so the preview can warn instead of silently shifting nothing or everything.
                    val zone = zones.resolve(prop.params.getValue("TZID")) ?: return local
                    local.atZone(zone).withZoneSameInstant(targetZone).toLocalDateTime()
                }
                else -> LocalDateTime.parse(raw, dateTimeFormat(raw.length)) // floating time
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun dateTimeFormat(length: Int): DateTimeFormatter =
        if (length == 13) FORMAT_NO_SECONDS else FORMAT

    /** Resolves TZIDs for one file and remembers the ones it could not. */
    private class ZoneResolver(private val aliases: Map<String, String>) {
        val unknown = linkedSetOf<String>()
        private val cache = mutableMapOf<String, ZoneId?>()

        fun resolve(tzid: String): ZoneId? = cache.getOrPut(tzid) {
            val cleaned = cleanTzid(tzid)
            (
                zoneOrNull(cleaned)
                    ?: aliases[cleaned]?.let(::zoneOrNull)
                    ?: WINDOWS_ZONES[cleaned]?.let(::zoneOrNull)
                    ?: pathStyleZone(cleaned)
                ).also { if (it == null) unknown += cleaned }
        }

        /** "mozilla.org/20050126_1/Europe/Warsaw" -> Europe/Warsaw (also 3-part ids like America/Argentina/Salta). */
        private fun pathStyleZone(id: String): ZoneId? {
            val parts = id.split('/').filter { it.isNotBlank() }
            for (take in 3 downTo 2) {
                if (parts.size > take) zoneOrNull(parts.takeLast(take).joinToString("/"))?.let { return it }
            }
            return null
        }

        private fun zoneOrNull(id: String): ZoneId? = try {
            ZoneId.of(id)
        } catch (_: DateTimeException) { // ZoneRulesException and invalid ids
            null
        }
    }

    companion object {
        private fun cleanTzid(tzid: String): String = tzid.trim().trim('"').removePrefix("/")

        private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val FORMAT_NO_SECONDS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")

        /** Outlook exports use Windows zone names. These cover Poland and its neighbours. */
        private val WINDOWS_ZONES = mapOf(
            "Central European Standard Time" to "Europe/Warsaw",
            "Central Europe Standard Time" to "Europe/Budapest",
            "W. Europe Standard Time" to "Europe/Berlin",
            "FLE Standard Time" to "Europe/Kiev",
            "E. Europe Standard Time" to "Europe/Chisinau",
            "GMT Standard Time" to "Europe/London",
            "UTC" to "UTC",
        )

        /** Joins RFC 5545 folded lines (continuations start with a space or tab). */
        internal fun unfold(text: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (rawLine in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && current.isNotEmpty()) {
                    current.append(rawLine, 1, rawLine.length)
                } else {
                    if (current.isNotEmpty()) result += current.toString()
                    current.clear()
                    current.append(rawLine.removePrefix("﻿"))
                }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result.filter { it.isNotBlank() }
        }

        internal fun parseLine(line: String): IcsProperty? {
            // The name/params part ends at the first ':' that is not inside double quotes.
            var inQuotes = false
            var colon = -1
            for ((i, c) in line.withIndex()) {
                if (c == '"') inQuotes = !inQuotes
                if (c == ':' && !inQuotes) { colon = i; break }
            }
            if (colon <= 0) return null
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val parts = head.split(';')
            val name = parts.first().trim().uppercase()
            val params = parts.drop(1).mapNotNull { param ->
                val eq = param.indexOf('=')
                if (eq <= 0) null else param.substring(0, eq).trim().uppercase() to param.substring(eq + 1).trim('"')
            }.toMap()
            return IcsProperty(name, params, value)
        }

        internal fun unescape(value: String): String = buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        'n', 'N' -> append('\n')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        /** Parses ISO-8601-style iCalendar durations like PT8H30M or P1DT2H. */
        internal fun parseDuration(value: String): Duration? {
            val match = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")
                .matchEntire(value.trim().uppercase()) ?: return null
            val (sign, weeks, days, hours, minutes, seconds) = match.destructured
            var duration = Duration.ofDays((weeks.toLongOrNull() ?: 0) * 7 + (days.toLongOrNull() ?: 0))
                .plusHours(hours.toLongOrNull() ?: 0)
                .plusMinutes(minutes.toLongOrNull() ?: 0)
                .plusSeconds(seconds.toLongOrNull() ?: 0)
            if (sign == "-") duration = duration.negated()
            return duration
        }
    }
}

internal data class IcsProperty(
    val name: String,
    val params: Map<String, String>,
    val value: String,
)

/** Converts a parsed event into the date/start/end triple a [com.vkurylo.shifttracker.domain.model.Shift] needs. */
fun IcsEvent.shiftDate(): LocalDate = start.toLocalDate()
