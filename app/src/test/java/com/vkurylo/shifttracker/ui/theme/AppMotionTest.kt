package com.vkurylo.shifttracker.ui.theme

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen transitions are a little quicker than the stock springs, with the same character. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class AppMotionTest {

    private fun durationMs(spec: FiniteAnimationSpec<Float>): Double =
        spec.vectorize(Float.VectorConverter)
            .getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1000f), AnimationVector1D(0f)) / 1e6

    private fun assertAboutFifteenPercentQuicker(base: FiniteAnimationSpec<Float>, quick: FiniteAnimationSpec<Float>) {
        base as SpringSpec<Float>
        quick as SpringSpec<Float>
        assertEquals("same bounce", base.dampingRatio, quick.dampingRatio, 0f)
        val ratio = durationMs(quick) / durationMs(base)
        assertTrue("duration ratio $ratio", ratio in 0.8..0.9)
    }

    @Test
    fun `the motion scheme's springs become about 15 percent quicker`() {
        val scheme = MotionScheme.expressive()
        listOf(scheme.defaultSpatialSpec<Float>(), scheme.defaultEffectsSpec(), scheme.fastEffectsSpec()).forEach {
            assertAboutFifteenPercentQuicker(it, AppMotion.quicker(it))
        }
    }

    @Test
    fun `the container transform is about 15 percent quicker than before`() {
        val before = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
        assertAboutFifteenPercentQuicker(before, AppMotion.containerTransform())
    }

    @Test
    fun `a spec that is not a spring is left alone`() {
        val tween = androidx.compose.animation.core.tween<Float>(300)
        assertSame(tween, AppMotion.quicker(tween))
    }
}
