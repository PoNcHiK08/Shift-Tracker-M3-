package com.vkurylo.shifttracker.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/*
 * Motion for the app. Everything is spring-based (M3 Expressive): springs keep their velocity when
 * an animation is interrupted, so a tap during a transition never makes things jump.
 *
 * Component motion comes from MaterialTheme.motionScheme (MotionScheme.expressive(), set in Theme.kt):
 *  - spatial specs (position, size, shape) are slightly bouncy,
 *  - effects specs (colour, alpha) never overshoot, so colours don't flicker.
 * The only hand-tuned spring is the screen slide, fitted to a One UI screen recording.
 */
object AppMotion {
    /**
     * One UI "slide over" screen transition as a spring, fitted to the 120 fps recording of Samsung
     * Settings (RMSE 1.6 % of the screen width; reaches 90 % in ~195 ms and settles by ~360 ms).
     * Slightly over-damped, so it never overshoots — exactly like the system transition.
     */
    fun <T> screenSlide(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.15f, stiffness = 580f, visibilityThreshold = visibilityThreshold)

    /** Container transform (card -> full screen editor): bouncy enough to feel physical, no wobble. */
    fun <T> containerTransform(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = visibilityThreshold)
}

/**
 * Clickable that physically squeezes under the finger and springs back on release
 * (M3 Expressive press feedback), keeping the standard ripple and semantics.
 */
@Composable
fun Modifier.expressiveClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    pressedScale: Float = 0.95f,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
