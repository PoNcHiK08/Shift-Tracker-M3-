package com.vkurylo.shifttracker.ui.util

import com.vkurylo.shifttracker.domain.model.Amounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Money text per currency: decimals from the currency, symbol placement as described in [Formatters.money]. */
class MoneyFormatTest {
    private val nbsp = ' '
    private val uk = Locale.forLanguageTag("uk")

    @Test
    fun `PLN looks exactly as before currencies per job`() {
        assertEquals("241.00${nbsp}zł", Formatters.money(24_100, "PLN", Locale.US))
        assertEquals("1,477.00${nbsp}zł", Formatters.money(147_700, "PLN", Locale.US))
        assertEquals("1,477.00${nbsp}zł", Formatters.money(147_700, Locale.US))
        assertEquals("33.00${nbsp}zł/h", Formatters.rate(3_300, "PLN", Locale.US))
        assertTrue(Formatters.money(24_100, "PLN", uk).matches(Regex("241,00${nbsp}zł")))
    }

    @Test
    fun `decimals follow the currency`() {
        assertEquals("¥1,500", Formatters.money(1_500, "JPY", Locale.US))
        assertEquals("12.345${nbsp}KWD", Formatters.money(12_345, "KWD", Locale.US))
        assertEquals("€241.00", Formatters.money(24_100, "EUR", Locale.US))
        assertEquals("$0.05", Formatters.money(5, "USD", Locale.US))
    }

    @Test
    fun `a sign follows the reader's locale, a word always follows the number`() {
        val euroUk = Formatters.money(24_100, "EUR", uk)
        assertTrue(euroUk, euroUk.startsWith("241,00") && euroUk.endsWith("€"))
        assertEquals("50.00${nbsp}Kč", Formatters.money(5_000, "CZK", Locale.US))
        assertEquals("₴241.00", Formatters.money(24_100, "UAH", Locale.US))
    }

    @Test
    fun `several currencies are listed, never added`() {
        val amounts = Amounts.of("EUR", 5_000) + Amounts.of("PLN", 24_100)
        assertEquals("241.00${nbsp}zł · €50.00", Formatters.amounts(amounts, Locale.US))
        assertEquals("0.00${nbsp}zł", Formatters.amounts(Amounts.EMPTY, Locale.US))
        assertEquals("¥0", Formatters.amounts(Amounts.EMPTY, Locale.US, fallbackCurrency = "JPY"))
    }
}
