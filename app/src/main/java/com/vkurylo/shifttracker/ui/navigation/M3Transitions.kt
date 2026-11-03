package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.activity.BackEventCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
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
 *  - Predictive back gesture: the screen being closed shrinks towards 90 %, rounds its corners,
 *    lifts on a shadow and drifts with the finger, revealing the previous screen behind it (M3
 *    predictive back for full-screen surfaces). Cancelling brings it back. Releasing continues
 *    from wherever the finger let go: it keeps shrinking and fades out, without stopping first.
 *    See [predictiveBackVisuals] and [predictiveBackSurface].
 *  - Top level (bottom bar tabs): fade through — incoming fades in while growing from 92 %.
 *  - Card -> editor: container transform (see SharedContainer.kt).
 */

/** Material's shared-axis travel distance. */
private val SharedAxisDistance = 30.dp

/** How far the closing screen drifts towards the finger during the predictive back gesture. */
private val PredictiveBackDrift = 8.dp

/** Corner radius the closing screen gets while it shrinks on predictive back. */
private val PredictiveBackCorner = 28.dp

/** Shadow under the shrinking screen, separating it from the identical surface behind. */
private val PredictiveBackShadow = 12.dp

/**
 * Length of the predictive back timeline. The system seeks it with the gesture's progress, and after
 * the finger lifts the rest of it plays in real time, so the exit after release takes
 * (1 - progress at release) * this.
 */
internal const val PREDICTIVE_BACK_MS = 300

/** The curve Material 3 components use to map predictive back progress (fast start, gentle end). */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * The exit after release starts at a moderate, linear-like pace (no jump in the first frame) and
 * settles at the end.
 */
private val ReleaseEasing = CubicBezierEasing(0.33f, 0.33f, 0.2f, 1f)

/** Scale of the closing screen while dragged all the way, and when it has left after release. */
private const val DRAG_SCALE = 0.9f
private const val EXIT_SCALE = 0.85f

@Immutable
class M3Transitions internal constructor(
    private val axisPx: Int,
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

    // Predictive back: the previous screen is simply there underneath; the closing one is drawn by
    // predictiveBackSurface (or, for an editor, shrinks back into its card). The NavHosts mark the
    // closing entry in PredictiveBackGesture and keep it until the transition ends.
    fun predictiveBackEnter(): EnterTransition = EnterTransition.None

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
            spatial = spatial,
            scaleSpec = scaleSpec,
            fadeInSpec = fadeInSpec,
            fadeOutSpec = fadeOutSpec,
        )
    }
}

/**
 * The entry a back gesture is closing right now. NavHost calls `predictivePopExitTransition` only for
 * a back gesture, so marking it there is how the closing screen (and a container transform) knows
 * the gesture is driving its transition. It is cleared when that gesture is cancelled, when the
 * closed screen is gone, and by any ordinary navigation away from or to the marked entry.
 */
object PredictiveBackGesture {
    // Snapshot state: the mark is set while NavHost composes the transition, and everything that
    // reads it (e.g. sharedContainer, which must drop the shared element) recomposes before the frame
    // is drawn. Setting the same value again is a no-op, so clearing costs nothing normally.
    var closingEntryId: String? by mutableStateOf(null)
        private set

    var swipeEdge: Int by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set

    fun mark(entryId: String, edge: Int) {
        closingEntryId = entryId
        swipeEdge = edge
    }

    fun isClosing(entryId: String?): Boolean = entryId != null && closingEntryId == entryId

    fun clear(vararg entryIds: String?) {
        if (closingEntryId != null && closingEntryId in entryIds) closingEntryId = null
    }
}

/** What the closing screen looks like at a point of the predictive back timeline. */
@Immutable
internal data class BackVisuals(
    val scale: Float,
    val alpha: Float,
    /** 0..1 of [PredictiveBackCorner] / [PredictiveBackShadow]. */
    val shape: Float,
    /** 0..1 of [PredictiveBackDrift], towards the finger. */
    val drift: Float,
)

/**
 * The closing screen at timeline position [progress] (0..1). While the finger is down ([releasedAt]
 * is NaN) it follows M3's predictive back curve: quick to respond, shrinking towards [DRAG_SCALE].
 * After release, the rest of the timeline (from [releasedAt] to 1) is one continuous exit: it keeps
 * shrinking to [EXIT_SCALE] and fades out, starting immediately - there is no pause in which the
 * screen sits still, and no last-frame pop. Continuous at the release point.
 */
internal fun predictiveBackVisuals(progress: Float, releasedAt: Float): BackVisuals {
    val p = progress.coerceIn(0f, 1f)
    if (releasedAt.isNaN()) {
        val drag = PredictiveBackEasing.transform(p)
        return BackVisuals(scale = 1f - (1f - DRAG_SCALE) * drag, alpha = 1f, shape = drag, drift = drag)
    }
    val r = releasedAt.coerceIn(0f, 1f)
    val atRelease = PredictiveBackEasing.transform(r)
    val startScale = 1f - (1f - DRAG_SCALE) * atRelease
    val exit = if (r >= 1f) 1f else ((p - r) / (1f - r)).coerceIn(0f, 1f)
    val move = ReleaseEasing.transform(exit)
    // Gone a little before the timeline ends, so the removal of the screen is never visible.
    val fade = FastOutSlowInEasing.transform((exit / 0.9f).coerceAtMost(1f))
    return BackVisuals(
        scale = startScale + (EXIT_SCALE - startScale) * move,
        alpha = 1f - fade,
        shape = atRelease + (1f - atRelease) * move,
        drift = atRelease + (1f - atRelease) * move,
    )
}

private class GestureLatch {
    /** This exit is driven by a back gesture (not a normal pop or forward navigation). */
    var gesture = false

    /** Timeline position at which the finger was lifted; NaN while it's down. */
    var releasedAt = Float.NaN
    var edge = BackEventCompat.EDGE_LEFT
}

/**
 * Predictive back for a full-screen destination: while a back gesture closes it, the screen becomes
 * a card (scale, rounded corners, shadow, drift towards the finger) over the previous screen, and
 * after release it leaves in one continuous motion ([predictiveBackVisuals]). Any other exit (normal
 * pop, tab switch, forward navigation) is left untouched.
 *
 * Release is detected from the destination's lifecycle: NavHost pops the entry when the gesture is
 * committed (CREATED), while during the drag and on cancel it stays STARTED or above.
 */
@Composable
fun Modifier.predictiveBackSurface(scope: AnimatedVisibilityScope): Modifier {
    val transition = scope.transition
    // Linear, so the position on this timeline is the gesture's progress while it's being dragged.
    val progress by transition.animateFloat(
        transitionSpec = { tween(PREDICTIVE_BACK_MS, easing = LinearEasing) },
        label = "backProgress",
    ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry
    val lifecycle by (entry?.lifecycle?.currentStateFlow ?: remember { MutableStateFlow(Lifecycle.State.RESUMED) })
        .collectAsState()
    val exiting = transition.targetState == EnterExitState.PostExit
    val latch = remember(exiting) { GestureLatch() }
    if (exiting && !latch.gesture && PredictiveBackGesture.isClosing(entry?.id)) {
        latch.gesture = true
        latch.edge = PredictiveBackGesture.swipeEdge
    }
    if (latch.gesture && latch.releasedAt.isNaN() && !lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        latch.releasedAt = progress.coerceIn(0f, 1f)
    }
    // Forget the mark when the gesture is cancelled or this screen is gone.
    val entryId = entry?.id
    SideEffect { if (!exiting) PredictiveBackGesture.clear(entryId) }
    DisposableEffect(entryId) { onDispose { PredictiveBackGesture.clear(entryId) } }

    if (!exiting || !latch.gesture) return this
    val v = predictiveBackVisuals(progress, latch.releasedAt)
    val towardsFinger = if (latch.edge == BackEventCompat.EDGE_LEFT) 1f else -1f
    return graphicsLayer {
        scaleX = v.scale
        scaleY = v.scale
        translationX = towardsFinger * PredictiveBackDrift.toPx() * v.drift
        alpha = v.alpha
        // Values stay within 0..1 by construction; the clamp guards shapes against negative radii.
        shape = RoundedCornerShape(PredictiveBackCorner * v.shape.coerceIn(0f, 1f))
        clip = true
        shadowElevation = PredictiveBackShadow.toPx() * v.shape.coerceIn(0f, 1f) * v.alpha
    }
}
