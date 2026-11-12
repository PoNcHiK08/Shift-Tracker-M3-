package com.vkurylo.shifttracker.domain.model

import java.util.Currency

/**
 * Currencies a job can be paid in (ISO 4217 codes). Amounts everywhere in the app are `Long` minor
 * units of the job's currency (the `*Grosze` names predate other currencies: read them as "minor
 * units"): cents, grosze, kopiyky; whole yen for JPY (0 decimals), fils for KWD (3 decimals).
 */
object Currencies {
    const val DEFAULT = "PLN"

    /** Shown first in the job editor's list. */
    val pinned: List<String> = listOf("PLN", "UAH", "EUR", "USD", "GBP", "CZK")

    private val common: List<String> = listOf(
        "AED", "AUD", "BGN", "BHD", "BRL", "CAD", "CHF", "CNY", "DKK", "GEL", "HKD", "HUF", "IDR", "ILS",
        "INR", "ISK", "JOD", "JPY", "KRW", "KWD", "KZT", "MDL", "MXN", "MYR", "NOK", "NZD", "OMR", "PHP",
        "RON", "RSD", "SAR", "SEK", "SGD", "THB", "TRY", "VND", "ZAR",
    )

    /** The editor's list: [pinned] first, then the others alphabetically. */
    val all: List<String> = pinned + common.filter { it !in pinned }.sorted()

    /** Display symbols. Anything missing is shown by its code (e.g. "KWD"). */
    private val symbols: Map<String, String> = mapOf(
        "PLN" to "zł", "UAH" to "₴", "EUR" to "€", "USD" to "$", "GBP" to "£", "CZK" to "Kč",
        "JPY" to "¥", "CNY" to "¥", "CHF" to "CHF", "SEK" to "kr", "NOK" to "kr", "DKK" to "kr",
        "ISK" to "kr", "HUF" to "Ft", "RON" to "lei", "BGN" to "лв", "INR" to "₹", "KRW" to "₩",
        "ILS" to "₪", "TRY" to "₺", "GEL" to "₾", "KZT" to "₸", "BRL" to "R$", "CAD" to "CA$",
        "AUD" to "A$", "NZD" to "NZ$", "HKD" to "HK$", "SGD" to "S$", "MXN" to "MX$", "THB" to "฿",
        "PHP" to "₱", "VND" to "₫", "ZAR" to "R",
    )

    /**
     * A valid ISO 4217 code with 0 to 3 decimals (metals, funds and "no currency" codes have none
     * and are refused). Backups may carry any such code, the editor offers [all].
     */
    fun isValid(code: String): Boolean {
        if (!code.matches(Regex("[A-Z]{3}"))) return false
        val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return false
        return currency.defaultFractionDigits in 0..3
    }

    /** Decimals of [code]: 2 for PLN/EUR, 0 for JPY, 3 for KWD. */
    fun fractionDigits(code: String): Int =
        runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it in 0..3 } ?: 2

    /** Default for a new job and for showing zero: the currency of the most recently created job. */
    fun defaultFor(jobs: List<Job>): String = jobs.maxByOrNull { it.id }?.currency ?: DEFAULT

    fun symbol(code: String): String = symbols[code] ?: code

    /** Name in [locale], e.g. "Ukrainian hryvnia"; the code when the platform doesn't know it. */
    fun displayName(code: String, locale: java.util.Locale): String =
        runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrNull() ?: code
}

/**
 * Money totals kept apart by currency: amounts in different currencies are never added together.
 * Iteration order is the order the currencies first appeared in.
 */
data class Amounts(val byCurrency: Map<String, Long> = emptyMap()) {
    val currencies: Set<String> get() = byCurrency.keys
    val isEmpty: Boolean get() = byCurrency.isEmpty()
    val isSingleCurrency: Boolean get() = byCurrency.size <= 1

    operator fun get(currency: String): Long = byCurrency[currency] ?: 0L

    operator fun plus(other: Amounts): Amounts {
        if (other.isEmpty) return this
        if (isEmpty) return other
        val merged = LinkedHashMap(byCurrency)
        other.byCurrency.forEach { (code, value) -> merged[code] = (merged[code] ?: 0L) + value }
        return Amounts(merged)
    }

    fun plus(currency: String, value: Long): Amounts = this + of(currency, value)

    /** Subtracts per currency; a currency of [other] missing here appears with a negative value. */
    operator fun minus(other: Amounts): Amounts {
        if (other.isEmpty) return this
        val merged = LinkedHashMap(byCurrency)
        other.byCurrency.forEach { (code, value) -> merged[code] = (merged[code] ?: 0L) - value }
        return Amounts(merged)
    }

    /** The largest amount first (ties keep their order); used to pick what to show first. */
    fun sortedByValue(): List<Pair<String, Long>> = byCurrency.entries.sortedByDescending { it.value }.map { it.key to it.value }

    companion object {
        val EMPTY = Amounts()

        fun of(currency: String, value: Long) = Amounts(linkedMapOf(currency to value))

        fun <T> sum(items: Iterable<T>, currency: (T) -> String, value: (T) -> Long): Amounts {
            val map = LinkedHashMap<String, Long>()
            items.forEach { map[currency(it)] = (map[currency(it)] ?: 0L) + value(it) }
            return Amounts(map)
        }
    }
}
