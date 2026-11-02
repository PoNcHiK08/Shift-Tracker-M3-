package com.vkurylo.shifttracker.domain.calc

import java.math.BigDecimal
import java.math.RoundingMode

object MoneyParser {
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
