package com.vkurylo.shifttracker.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyParserTest {
    @Test
    fun `parses comma and dot decimals`() {
        assertEquals(2770L, MoneyParser.parseGrosze("27,70"))
        assertEquals(2770L, MoneyParser.parseGrosze("27.7"))
        assertEquals(3000L, MoneyParser.parseGrosze(" 30 "))
        assertEquals(123456L, MoneyParser.parseGrosze("1 234,56"))
    }

    @Test
    fun `rejects bad input`() {
        assertNull(MoneyParser.parseGrosze(""))
        assertNull(MoneyParser.parseGrosze("abc"))
        assertNull(MoneyParser.parseGrosze("-5"))
        assertNull(MoneyParser.parseGrosze("1,234"))
    }

    @Test
    fun `formats for editing`() {
        assertEquals("27,70", MoneyParser.toInput(2770))
        assertEquals("30", MoneyParser.toInput(3000))
        assertEquals("0,05", MoneyParser.toInput(5))
    }

    @Test
    fun `MAX_GROSZE is the largest amount the parser accepts`() {
        assertEquals(MoneyParser.MAX_GROSZE, MoneyParser.parseGrosze("9999999,99"))
        assertEquals(null, MoneyParser.parseGrosze("10000000"))
    }
    @Test
    fun `decimals follow the currency`() {
        // JPY: whole yen only.
        assertEquals(1500L, MoneyParser.parseMinor("1500", 0))
        assertEquals(1500L, MoneyParser.parseMinor("1 500", 0))
        assertNull(MoneyParser.parseMinor("1500,5", 0))
        assertEquals("1500", MoneyParser.toInput(1500, 0))
        // PLN/EUR: two.
        assertEquals(2770L, MoneyParser.parseMinor("27,7", 2))
        assertNull(MoneyParser.parseMinor("27,705", 2))
        // KWD: three (fils).
        assertEquals(12_345L, MoneyParser.parseMinor("12.345", 3))
        assertEquals(12_300L, MoneyParser.parseMinor("12,3", 3))
        assertNull(MoneyParser.parseMinor("12.3456", 3))
        assertEquals("12,345", MoneyParser.toInput(12_345, 3))
        assertEquals("12,005", MoneyParser.toInput(12_005, 3))
        assertEquals("12", MoneyParser.toInput(12_000, 3))
    }

    @Test
    fun `rates keep 7 whole digits, salaries and bonuses may have 10`() {
        assertEquals(MoneyParser.maxMinor(0), MoneyParser.parseMinor("9999999", 0))
        assertNull(MoneyParser.parseMinor("10000000", 0))
        assertEquals(MoneyParser.maxMinor(3), MoneyParser.parseMinor("9999999.999", 3))
        assertEquals(9_999_999_999_999L, MoneyParser.parseMinor("9999999999.999", 3, MoneyParser.AMOUNT_WHOLE_DIGITS))
        assertNull(MoneyParser.parseMinor("10000000000", 2, MoneyParser.AMOUNT_WHOLE_DIGITS))
        // The 2-decimal rate limit is the one from before currencies.
        assertEquals(MoneyParser.MAX_GROSZE, MoneyParser.maxMinor(2))
        // Overflow bound: the largest amount times 10 000 basis points (net deduction) fits in a Long.
        val largest = MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS)
        assertTrue(Math.multiplyHigh(largest, 10_000L) == 0L && largest * 10_000L > 0)
        val largestShift = MoneyParser.maxMinor(3) * 24 * 60
        assertTrue(largestShift * 10_000L / 10_000L == largestShift)
    }
}
