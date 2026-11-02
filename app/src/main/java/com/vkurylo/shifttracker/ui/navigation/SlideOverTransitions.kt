package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavBackStackEntry

/*
 * "Slide over" screen transition modelled on Samsung One UI Settings, measured frame by frame
 * from a 120 fps screen recording:
 *  - the new screen slides in from the right edge over ~420 ms;
 *  - the screen underneath moves ~31 % of the width to the left (parallax) in sync;
 *  - the screen underneath is dimmed with up to ~16 % black, linearly with progress;
 *  - the motion fits cubic-bezier(0.22, 0.25, 0, 1): fast start, long gentle settle.
 * Going back plays the same motion in reverse, and the predictive back gesture scrubs it with
 * the finger (Navigation Compose seeks these transitions while the gesture is in progress).
 */

/** One UI's deceleration curve, matched to the recording (RMSE ≈ 1 % of the screen width). */
val OneUiEasing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)

const val SLIDE_OVER_DURATION_MS = 420
private const val PARALLAX_FRACTION = 0.31f
private const val MAX_DIM = 0.16f

private fun <T> slideSpec() = tween<T>(SLIDE_OVER_DURATION_MS, easing = OneUiEasing)

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOverEnter(): EnterTransition =
    slideInHorizontally(slideSpec()) { fullWidth -> fullWidth }

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOverExit(): ExitTransition =
    slideOutHorizontally(slideSpec()) { fullWidth -> -(fullWidth * PARALLAX_FRACTION).toInt() }

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOverPopEnter(): EnterTransition =
    slideInHorizontally(slideSpec()) { fullWidth -> -(fullWidth * PARALLAX_FRACTION).toInt() }

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOverPopExit(): ExitTransition =
    slideOutHorizontally(slideSpec()) { fullWidth -> fullWidth }

/**
 * Dims the screen *underneath* this one. Every destination draws a black scrim over the area to
 * its left, proportional to how far it has slid in. Only the screen on top ever has that area on
 * screen (the one underneath has moved left, so its "left side" is off-screen), which makes this
 * work for push, pop and the predictive back gesture without knowing the direction.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun Modifier.slideOverScrim(scope: AnimatedVisibilityScope): Modifier {
    val visibility by scope.transition.animateFloat(
        transitionSpec = { slideSpec() },
        label = "slideOverScrim",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    return drawWithContent {
        if (visibility > 0f) {
            drawRect(
                color = Color.Black.copy(alpha = MAX_DIM * visibility),
                topLeft = Offset(-size.width, 0f),
                size = size,
            )
        }
        drawContent()
    }
}
