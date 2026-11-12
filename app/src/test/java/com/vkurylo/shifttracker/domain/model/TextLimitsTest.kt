package com.vkurylo.shifttracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextLimitsTest {

    private val max = TextLimits.MAX_LENGTH

    @Test
    fun theEditorsLimitIsTheOneFirestoreRulesEnforce() {
        // Unit tests run in app/; the rules are in firebase/.
        val rules = File("../firebase/firestore.rules").readText()
        assertTrue("firestore.rules must allow exactly $max characters", rules.contains("value.size() <= $max;"))
    }

    @Test
    fun cuttingNeverSplitsAnEmoji() {
        val emoji = "😀" // one emoji, two UTF-16 units
        assertEquals("a".repeat(max), TextLimits.cut("a".repeat(max + 5)))
        // The cut would fall between the emoji's halves: it goes before the emoji instead.
        val text = "a".repeat(max - 1) + emoji
        assertEquals("a".repeat(max - 1), TextLimits.cut(text))
        assertEquals(text, TextLimits.cut(text + "", max + 1))
        assertEquals("short", TextLimits.cut("short"))
    }

    @Test
    fun aFieldStopsAtTheLimitAndAnOlderLongerTextCanOnlyGetShorter() {
        val full = "a".repeat(max)
        assertEquals("typing up to the limit", full, TextLimits.limitInput(full.dropLast(1), full))
        assertEquals("typing past it", full, TextLimits.limitInput(full, full + "b"))
        assertEquals("a paste that goes over is cut", "x" + "a".repeat(max - 1), TextLimits.limitInput("x", "x$full"))

        val legacy = "a".repeat(max + 500)
        assertEquals("shortening an over-long text", legacy.dropLast(10), TextLimits.limitInput(legacy, legacy.dropLast(10)))
        assertEquals("but not lengthening it", legacy, TextLimits.limitInput(legacy, legacy + "b"))
    }
}
