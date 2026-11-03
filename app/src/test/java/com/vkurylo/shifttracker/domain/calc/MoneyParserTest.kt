package com.vkurylo.shifttracker.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
