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

    /** When a full-screen move (1500 px) really ends: within half a pixel of its target. */
    private fun fullScreenMoveMs(spec: FiniteAnimationSpec<Float>): Double =
        spec.vectorize(Float.VectorConverter)
            .getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1500f), AnimationVector1D(0f)) / 1e6

    @Test
    fun `the container transform lands without visible overshoot and is not slower than in 1_0_12`() {
        val spec = AppMotion.containerTransform<Float>(0.5f) as SpringSpec<Float>
        assertTrue("damping ${spec.dampingRatio}", spec.dampingRatio >= 0.9f)
        // 1.0.11: 0.86 / StiffnessMediumLow. 1.0.12: the same with stiffness x 1.38.
        val v1011 = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)
        val v1012 = spring(0.86f, Spring.StiffnessMediumLow * AppMotion.TRANSITION_STIFFNESS_FACTOR, 0.5f)
        val now = fullScreenMoveMs(spec)
        assertTrue("$now ms vs 1.0.12 ${fullScreenMoveMs(v1012)} ms", now <= fullScreenMoveMs(v1012))
        assertTrue("$now ms vs 1.0.11 ${fullScreenMoveMs(v1011)} ms", now <= 0.85 * fullScreenMoveMs(v1011))
    }

    @Test
    fun `after a back gesture the editor flies into its card a little calmer`() {
        val normal = AppMotion.containerTransform<Float>(0.5f) as SpringSpec<Float>
        val calm = AppMotion.containerTransform<Float>(0.5f, afterBackGesture = true) as SpringSpec<Float>
        assertEquals("same bounce", normal.dampingRatio, calm.dampingRatio, 0f)
        // ~5 % longer than a normal close.
        assertEquals("vs normal close", 1.054, fullScreenMoveMs(calm) / fullScreenMoveMs(normal), 0.02)
        // ~17 % longer than 1.0.14 (stiffness 690 x 1.235).
        val v1014 = spring(0.9f, 690f * 1.235f, 0.5f)
        assertEquals("vs 1.0.14", 1.17, fullScreenMoveMs(calm) / fullScreenMoveMs(v1014), 0.03)
    }

    @Test
    fun `a spec that is not a spring is left alone`() {
        val tween = androidx.compose.animation.core.tween<Float>(300)
        assertSame(tween, AppMotion.quicker(tween))
    }
}
