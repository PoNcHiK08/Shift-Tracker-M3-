package com.vkurylo.shifttracker.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackVisualsTest {

    private val releasePoints = listOf(0f, 0.05f, 0.2f, 0.3f, 0.5f, 0.8f, 0.95f)

    @Test
    fun `dragging shrinks towards 90 percent and stays opaque`() {
        var last = predictiveBackVisuals(0f, Float.NaN)
        assertEquals(1f, last.scale, 0f)
        for (i in 1..100) {
            val v = predictiveBackVisuals(i / 100f, Float.NaN)
            assertTrue(v.scale <= last.scale && v.scale >= 0.9f - 1e-4f)
            assertEquals(1f, v.alpha, 0f)
            last = v
        }
    }

    @Test
    fun `release continues from exactly where the drag was`() {
        for (r in releasePoints) {
            val dragged = predictiveBackVisuals(r, Float.NaN)
            val released = predictiveBackVisuals(r, r)
            assertEquals("scale at $r", dragged.scale, released.scale, 1e-4f)
            assertEquals("alpha at $r", dragged.alpha, released.alpha, 1e-4f)
            assertEquals("shape at $r", dragged.shape, released.shape, 1e-4f)
            assertEquals("drift at $r", dragged.drift, released.drift, 1e-4f)
        }
    }

    @Test
    fun `after release it keeps shrinking and fades out before the timeline ends`() {
        for (r in releasePoints) {
            var last = predictiveBackVisuals(r, r)
            val steps = 50
            for (i in 1..steps) {
                val p = r + (1f - r) * i / steps
                val v = predictiveBackVisuals(p, r)
                assertTrue("scale grew after release at $r", v.scale <= last.scale + 1e-5f)
                assertTrue("alpha grew after release at $r", v.alpha <= last.alpha + 1e-5f)
                assertTrue(v.shape in 0f..1f && v.drift in 0f..1f && v.alpha in 0f..1f)
                last = v
            }
            assertEquals("not faded out at the end (release $r)", 0f, predictiveBackVisuals(r + (1f - r) * 0.9f, r).alpha, 1e-4f)
            assertEquals(0.85f, predictiveBackVisuals(1f, r).scale, 1e-4f)
        }
    }

    @Test
    fun `the first part of the exit already moves (no pause after release)`() {
        for (r in listOf(0.1f, 0.3f, 0.6f)) {
            val start = predictiveBackVisuals(r, r)
            val tenth = predictiveBackVisuals(r + (1f - r) * 0.1f, r)
            assertTrue("no visible motion right after release at $r", start.scale - tenth.scale > 0.002f)
            assertTrue("no fade right after release at $r", start.alpha - tenth.alpha > 0.01f)
        }
    }

    @Test
    fun `a held editor follows the finger at first, then stops at a limit`() {
        assertEquals(0f, editorDragAmount(0f), 0f)
        var last = 0f
        for (i in 1..100) {
            val v = editorDragAmount(i / 100f)
            assertTrue("not increasing at ${i / 100f}", v > last)
            assertTrue("past the limit", v < 1f)
            last = v
        }
        // Most of the way in the first quarter of the finger's travel, little after half of it.
        assertTrue(editorDragAmount(0.25f) > 0.6f)
        assertTrue(editorDragAmount(1f) - editorDragAmount(0.5f) < 0.15f)
        assertEquals(editorDragAmount(1f), editorDragAmount(3f), 0f) // clamped
    }
}
