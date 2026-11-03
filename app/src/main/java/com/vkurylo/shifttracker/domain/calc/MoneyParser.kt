package com.vkurylo.shifttracker.domain.calc

import java.math.BigDecimal
import java.math.RoundingMode

object MoneyParser {
    /**
     * The largest amount the parser accepts (9 999 999,99 zł): at most 7 whole-złoty digits and 2
     * decimals. Every rate and bonus in the app comes from this parser (or is copied from a job), and
     * backups are checked against it. It also keeps pay calculations far from Long overflow: a full
     * 24 h shift at this rate needs ~2.1e18 in the intermediate product, below Long.MAX_VALUE (9.2e18).
     */
    const val MAX_GROSZE: Long = 999_999_999L

    /**
     * Parses user input such as "27,70", "27.7" or "30" into grosze.
     * Returns null for empty, negative or malformed input.
     */
    fun parseGrosze(input: String): Long? {
        val normalized = input.filterNot { it.isWhitespace() || it == '\u00A0' }.replace(',', '.')
        if (normalized.isEmpty()) return null
        if (!normalized.matches(Regex("""\d{1,7}(\.\d{0,2})?"""))) return null
        return BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** "2770" -> "27.70"; "3000" -> "30" — used to prefill edit fields. */
    fun toInput(grosze: Long, decimalSeparator: Char = ','): String {
        val zl = grosze / 100
        val gr = grosze % 100
        return if (gr == 0L) zl.toString() else "$zl$decimalSeparator${gr.toString().padStart(2, '0')}"
    }
}
