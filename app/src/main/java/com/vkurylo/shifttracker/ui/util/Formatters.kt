package com.vkurylo.shifttracker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.Currencies
import java.math.BigDecimal
import java.text.DecimalFormat
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
    /**
     * [minor] units of [currency] with the currency's decimals (0 for JPY, 3 for KWD). A symbol made
     * of letters ("zł", "Kč", "kr", "CHF") always follows the number after a no-break space, as PLN
     * always has; a sign ("€", "$", "₴") is placed the way the reader's [locale] places it
     * ("€241.00" in English, "241,00 €" in Ukrainian).
     */
    fun money(minor: Long, currency: String, locale: Locale): String {
        val digits = Currencies.fractionDigits(currency)
        val symbol = Currencies.symbol(currency)
        val value = BigDecimal.valueOf(minor, digits)
        if (symbol.all { it.isLetter() }) {
            val format = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
            return format.format(value) + "\u00A0" + symbol
        }
        val format = (NumberFormat.getCurrencyInstance(locale) as DecimalFormat).apply {
            decimalFormatSymbols = decimalFormatSymbols.apply { currencySymbol = symbol }
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return format.format(value)
    }

    /** PLN, as everything was before currencies per job (tests and previews). */
    fun money(grosze: Long, locale: Locale): String = money(grosze, Currencies.DEFAULT, locale)

    fun rate(minor: Long, currency: String, locale: Locale): String = money(minor, currency, locale) + "/h"

    /** A rate field's suffix: "zł/h", "€/h". */
    fun rateSuffix(currency: String): String = Currencies.symbol(currency) + "/h"

    /** Several currencies' totals, largest first: "1 477,00 zł · 200,00 €". */
    fun amounts(amounts: Amounts, locale: Locale, fallbackCurrency: String = Currencies.DEFAULT): String =
        if (amounts.isEmpty) money(0, fallbackCurrency, locale)
        else amounts.sortedByValue().joinToString(" · ") { (code, value) -> money(value, code, locale) }

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

    fun monthName(month: YearMonth, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL", locale).format(month).replaceFirstChar { it.titlecase(locale) }

    /** "27 жовт. – 2 лист. 2026"; the year is shown once when both ends share it. */
    fun dateRange(from: LocalDate, to: LocalDate, locale: Locale): String {
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        val start = if (from.year == to.year) dayMonth.format(from) else full.format(from)
        return "$start – ${full.format(to)}"
    }

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
