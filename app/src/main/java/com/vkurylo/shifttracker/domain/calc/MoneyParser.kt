package com.vkurylo.shifttracker.domain.calc

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amounts typed by the user, in minor units of a currency with 0 to 3 decimals ("fraction digits":
 * 2 for PLN/EUR, 0 for JPY, 3 for KWD).
 *
 * Limits: an hourly rate has at most [RATE_WHOLE_DIGITS] whole digits. That keeps pay maths far from
 * Long overflow: a 24 h shift at the largest 3-decimal rate is 1440 min × ~1e10, and the net deduction
 * multiplies a result by at most 10 000 basis points: ~1.4e17, below Long.MAX_VALUE (9.2e18). A bonus
 * or a monthly salary is never multiplied by minutes, so it may have [AMOUNT_WHOLE_DIGITS]: ~1e13 ×
 * 10 000 = 1e17, also safe.
 */
object MoneyParser {
    const val RATE_WHOLE_DIGITS = 7
    const val AMOUNT_WHOLE_DIGITS = 10

    /** The largest 2-decimal rate (9 999 999,99), as before multi-currency support. */
    const val MAX_GROSZE: Long = 999_999_999L

    /** The largest amount with [wholeDigits] whole digits and [fractionDigits] decimals, in minor units. */
    fun maxMinor(fractionDigits: Int, wholeDigits: Int = RATE_WHOLE_DIGITS): Long {
        var max = 1L
        repeat(wholeDigits + fractionDigits) { max *= 10 }
        return max - 1
    }

    /**
     * Parses user input such as "27,70", "27.7" or "30" into minor units of a currency with
     * [fractionDigits] decimals. Returns null for empty, negative or malformed input, for more decimals
     * than the currency has (e.g. "5.5" yen) and for more than [wholeDigits] whole digits.
     */
    fun parseMinor(input: String, fractionDigits: Int, wholeDigits: Int = RATE_WHOLE_DIGITS): Long? {
        val normalized = input.filterNot { it.isWhitespace() || it == ' ' }.replace(',', '.')
        if (normalized.isEmpty()) return null
        val decimals = if (fractionDigits > 0) """(\.\d{0,$fractionDigits})?""" else """\.?"""
        if (!normalized.matches(Regex("""\d{1,$wholeDigits}$decimals"""))) return null
        return BigDecimal(normalized).movePointRight(fractionDigits).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** "2770" -> "27,70"; "3000" -> "30" (2 decimals) — used to prefill edit fields. */
    fun toInput(minor: Long, fractionDigits: Int, decimalSeparator: Char = ','): String {
        if (fractionDigits == 0) return minor.toString()
        var unit = 1L
        repeat(fractionDigits) { unit *= 10 }
        val whole = minor / unit
        val fraction = minor % unit
        return if (fraction == 0L) whole.toString() else "$whole$decimalSeparator${fraction.toString().padStart(fractionDigits, '0')}"
    }

    /** 2-decimal shorthand for [parseMinor] (PLN and most currencies). */
    fun parseGrosze(input: String): Long? = parseMinor(input, 2)

    /** 2-decimal shorthand for [toInput]. */
    fun toInput(grosze: Long, decimalSeparator: Char = ','): String = toInput(grosze, 2, decimalSeparator)
}
