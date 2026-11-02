package com.vkurylo.shifttracker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vkurylo.shifttracker.R
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The week always starts on Monday (Polish/Ukrainian convention). */
val WeekDays: List<DayOfWeek> = DayOfWeek.entries

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

object Formatters {
    fun money(grosze: Long, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return format.format(grosze / 100.0) + " zł"
    }

    fun rate(grosze: Long, locale: Locale): String = money(grosze, locale) + "/h"

    /** 450 -> "7:30" (hours:minutes). */
    fun hoursMinutes(minutes: Long): String = "%d:%02d".format(minutes / 60, minutes % 60)

    /** 450 -> "7,5" style decimal hours. */
    fun decimalHours(minutes: Long, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return format.format(minutes / 60.0)
    }

    fun time(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    fun monthYear(month: YearMonth, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).replaceFirstChar { it.titlecase(locale) }

    fun fullDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date).replaceFirstChar { it.titlecase(locale) }

    fun mediumDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    fun shortDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(date).replaceFirstChar { it.titlecase(locale) }

    fun weekdayNarrow(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun hoursLabel(minutes: Long): String =
    stringResource(R.string.hours_value, Formatters.decimalHours(minutes, currentLocale()))

@Composable
fun shiftsCountLabel(count: Int): String = pluralStringResource(R.plurals.shift_count, count, count)
