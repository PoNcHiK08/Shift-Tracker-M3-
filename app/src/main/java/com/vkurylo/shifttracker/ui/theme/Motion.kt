package com.vkurylo.shifttracker.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
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
 * Motion comes from MaterialTheme.motionScheme (MotionScheme.expressive(), set in Theme.kt):
 *  - spatial specs (position, size, shape) are slightly bouncy,
 *  - effects specs (colour, alpha) never overshoot, so colours don't flicker.
 * Screen transitions follow the M3 transition patterns (ui/navigation/M3Transitions.kt).
 */
object AppMotion {
    /**
     * Screen transitions run about 15 % quicker than the stock springs: same damping (same character
     * and bounce), stiffness × this. A spring's duration goes with 1/√stiffness, so × 1.38 ≈ × 0.85 time.
     */
    const val TRANSITION_STIFFNESS_FACTOR = 1.38f

    /**
     * Container transform (card -> full screen editor and back), also the editor's corner and scrim.
     * Damping 0.9 overshoots by ~0.15 % (about 2 px over a full-screen move; 0.86 did ~0.5 %, a few
     * pixels past the card when closing), so it lands solid. The stiffness keeps the settle time of
     * the quicker 0.86 spring (~195 ms to 99 %), as more damping alone would make it slower.
     */
    fun <T> containerTransform(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 690f,
        visibilityThreshold = visibilityThreshold,
    )

    /** A motion-scheme spring made quicker for screen transitions; any other spec is returned as is. */
    fun <T> quicker(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (spec is SpringSpec<T>) {
            spring(spec.dampingRatio, spec.stiffness * TRANSITION_STIFFNESS_FACTOR, spec.visibilityThreshold)
        } else {
            spec
        }
}

/**
 * Squeezes the element while [interactionSource] reports a press and springs it back on release
 * (M3 Expressive press feedback). Put it before any clip/background so the whole surface scales.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Clickable that physically squeezes under the finger and springs back on release
 * (M3 Expressive press feedback), keeping the standard ripple (or the given [indication]) and semantics.
 */
@Composable
fun Modifier.expressiveClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    pressedScale: Float = 0.95f,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        .pressScale(source, pressedScale, enabled)
        .clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
