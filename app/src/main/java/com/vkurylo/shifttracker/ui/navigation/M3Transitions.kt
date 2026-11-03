package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/*
 * Material 3 Expressive screen transitions (m3.material.io → Motion → Transition patterns), with
 * every value on the M3 Expressive motion scheme springs:
 *
 *  - Forward / backward (detail screens): shared axis X — the incoming screen slides 30 dp in from
 *    the side of travel while fading in, the outgoing one slides 30 dp the other way while fading
 *    out. The slide uses the bouncy *spatial* spring, the fades the non-overshooting *effects*
 *    springs (outgoing on the fast one, so the two screens hardly overlap: a fade through).
 *  - Predictive back gesture: the screen being closed shrinks to 90 %, rounds its corners, lifts on
 *    a shadow and drifts with the finger, revealing the previous screen behind it (M3 predictive
 *    back for full-screen surfaces). Releasing fades it away, cancelling brings it back.
 *    This path is driven by the finger, so like Material's own predictive back components it maps
 *    the gesture progress through M3's predictive-back curve instead of a spring.
 *  - Top level (bottom bar tabs): fade through — incoming fades in while growing from 92 %.
 *  - Card -> editor: container transform (see SharedContainer.kt).
 */

/** Material's shared-axis travel distance. */
private val SharedAxisDistance = 30.dp

/** How far the closing screen drifts towards the finger during the predictive back gesture. */
private val PredictiveBackDrift = 8.dp

/** Corner radius the closing screen gets while it shrinks on predictive back. */
private val PredictiveBackCorner = 28.dp

/** Swipe edge reported by the system for a back gesture that starts at the left edge. */
private const val EDGE_LEFT = 0

/** Length of the gesture-driven timeline; the finger position picks a point on it. */
private const val PREDICTIVE_BACK_MS = 350

/** The curve Material 3 components use to map predictive back progress (fast start, gentle end). */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

private fun <T> predictiveSpec() = tween<T>(PREDICTIVE_BACK_MS, easing = PredictiveBackEasing)

/** Opaque while the finger is down; fades only at the very end, i.e. after the user lets go. */
private val predictiveFade = keyframes<Float> {
    durationMillis = PREDICTIVE_BACK_MS
    1f at (PREDICTIVE_BACK_MS * 0.95f).toInt()
    0f at PREDICTIVE_BACK_MS
}

@Immutable
class M3Transitions internal constructor(
    private val axisPx: Int,
    private val driftPx: Int,
    private val spatial: FiniteAnimationSpec<IntOffset>,
    private val scaleSpec: FiniteAnimationSpec<Float>,
    private val fadeInSpec: FiniteAnimationSpec<Float>,
    private val fadeOutSpec: FiniteAnimationSpec<Float>,
) {
    // Shared axis X.
    fun forwardEnter(): EnterTransition = slideInHorizontally(spatial) { axisPx } + fadeIn(fadeInSpec)
    fun forwardExit(): ExitTransition = slideOutHorizontally(spatial) { -axisPx } + fadeOut(fadeOutSpec)
    fun backwardEnter(): EnterTransition = slideInHorizontally(spatial) { -axisPx } + fadeIn(fadeInSpec)
    fun backwardExit(): ExitTransition = slideOutHorizontally(spatial) { axisPx } + fadeOut(fadeOutSpec)

    // Predictive back: the previous screen is simply there underneath; the closing one shrinks.
    fun predictiveBackEnter(): EnterTransition = EnterTransition.None
    fun predictiveBackExit(swipeEdge: Int): ExitTransition =
        scaleOut(predictiveSpec(), targetScale = 0.9f) +
            slideOutHorizontally(predictiveSpec()) { if (swipeEdge == EDGE_LEFT) driftPx else -driftPx } +
            fadeOut(predictiveFade)

    // Fade through between top-level destinations.
    fun fadeThroughEnter(): EnterTransition = fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.92f)
    fun fadeThroughExit(): ExitTransition = fadeOut(fadeOutSpec)
}

/** The app's M3 Expressive transitions, built from the current theme's motion scheme. */
@Composable
fun rememberM3Transitions(): M3Transitions {
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val spatial = motion.defaultSpatialSpec<IntOffset>()
    val scaleSpec = motion.defaultSpatialSpec<Float>()
    val fadeInSpec = motion.defaultEffectsSpec<Float>()
    val fadeOutSpec = motion.fastEffectsSpec<Float>()
    return remember(motion, density) {
        M3Transitions(
            axisPx = with(density) { SharedAxisDistance.roundToPx() },
            driftPx = with(density) { PredictiveBackDrift.roundToPx() },
            spatial = spatial,
            scaleSpec = scaleSpec,
            fadeInSpec = fadeInSpec,
            fadeOutSpec = fadeOutSpec,
        )
    }
}

/**
 * While a screen is being closed it becomes a card — rounded corners and a soft shadow — so on the
 * predictive back gesture it reads as a surface shrinking away from the screen behind it (M3).
 * Only for screens that can be popped.
 */
@Composable
fun Modifier.closingScreenShape(scope: AnimatedVisibilityScope): Modifier {
    val closing by scope.transition.animateFloat(transitionSpec = { predictiveSpec() }, label = "closing") { state ->
        if (state == EnterExitState.PostExit) 1f else 0f
    }
    if (closing <= 0f) return this
    return graphicsLayer {
        shape = RoundedCornerShape(PredictiveBackCorner * closing)
        clip = true
        shadowElevation = PredictiveBackShadow.toPx() * closing
    }
}

/** Shadow under the shrinking screen, separating it from the identical surface behind. */
private val PredictiveBackShadow = 12.dp
