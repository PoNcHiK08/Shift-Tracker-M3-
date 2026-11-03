package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.vkurylo.shifttracker.ui.theme.AppMotion

/*
 * Container transform: a shift/bonus card (or an "add" button) physically grows into the full-screen
 * editor and shrinks back on close or predictive back.
 *
 * The whole navigation graph sits in one SharedTransitionLayout. Cards live deep inside the home
 * screen's tab NavHost, so the *root* NavHost's AnimatedVisibilityScope is handed down through a
 * CompositionLocal — the card must animate with the root transition (home <-> editor), not with
 * the tab cross-fade.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** AnimatedVisibilityScope of the root NavHost destination the caller is part of. */
val LocalRootAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Corner radius of cards that expand into editors; the editor animates from this to square. */
val ContainerCornerRadius: Dp = 24.dp

/** The editor's key when it was opened from a card/button, null when it slides in normally. */
fun NavBackStackEntry.containerKey(): String? = when {
    destination.hasRoute(EditShiftRoute::class) -> toRoute<EditShiftRoute>().sharedKey
    destination.hasRoute(EditBonusRoute::class) -> toRoute<EditBonusRoute>().sharedKey
    else -> null
}

/**
 * Marks this element as one end of a container transform identified by [key]. No-op when [key] is
 * null or when there is no shared-transition scope (e.g. previews).
 *
 * Like Material's container transform the morphing container is never see-through: the card end
 * ([isEditor] = false) stays fully opaque underneath, and the editor end fades in/out on top of it.
 *
 * Opening and closing use the container-transform spring. When a back gesture closes the editor,
 * the transition is seeked by the finger: a spring (fast start) would collapse the editor into the
 * card while the finger has barely moved, so the bounds then follow a linear timeline - the editor
 * shrinks in step with the finger, stops when the finger stops, and after release carries on into
 * the card. Its content stays readable through the first part of the drag.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: String?, shape: Shape, isEditor: Boolean = false): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalRootAnimatedVisibilityScope.current ?: return this
    if (key == null) return this
    val effects = AppMotion.quicker(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())
    val gesture = PredictiveBackGesture.closingEntryId != null
    return with(sharedScope) {
        this@sharedContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibilityScope,
            enter = if (isEditor) fadeIn(effects) else EnterTransition.None,
            exit = if (!isEditor) ExitTransition.None else if (gesture) fadeOut(GestureEditorFade) else fadeOut(effects),
            zIndexInOverlay = if (isEditor) 1f else 0f,
            boundsTransform = BoundsTransform { _, _ ->
                if (PredictiveBackGesture.closingEntryId != null) {
                    tween(PREDICTIVE_BACK_MS, easing = LinearEasing)
                } else {
                    AppMotion.containerTransform(Rect.VisibilityThreshold)
                }
            },
            // The container itself follows the animated bounds, so its background always fills the
            // morphing shape; the editor inside keeps its full-screen layout (see ContainerTransformHost).
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** The editor's content, closed by a back gesture: visible through the early drag, gone before the card. */
private val GestureEditorFade = keyframes<Float> {
    durationMillis = PREDICTIVE_BACK_MS
    1f at (PREDICTIVE_BACK_MS * 0.3f).toInt()
    0f at (PREDICTIVE_BACK_MS * 0.8f).toInt()
}

/**
 * Root of an editor that may be opened with a container transform: dims the screen underneath while
 * the card grows, and gives [content]
 *  - the editor's animated shape (rounded like the card at the start, square when full screen),
 *  - a modifier for the editor's content that keeps its full-screen layout pinned to the container's
 *    top-left corner, so the growing container reveals the editor like a window opening (instead of
 *    squeezing the form into a card-sized box, or showing its middle first).
 */
@Composable
fun ContainerTransformHost(
    scope: AnimatedVisibilityScope,
    enabled: Boolean,
    content: @Composable (editorShape: Shape, contentModifier: Modifier) -> Unit,
) {
    if (!enabled) {
        content(RoundedCornerShape(0.dp), Modifier)
        return
    }
    val spatial = AppMotion.containerTransform<Dp>(Dp.VisibilityThreshold)
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    // Closed by a back gesture: on the gesture's own linear timeline (same length as the screen's
    // predictive back), so the scrim lifts in step with the finger. Springs otherwise.
    val corner by scope.transition.animateDp(
        transitionSpec = { if (PredictiveBackGesture.isClosing(entryId)) tween(PREDICTIVE_BACK_MS, easing = LinearEasing) else spatial },
        label = "editorCorner",
    ) { state ->
        if (state == EnterExitState.Visible) 0.dp else ContainerCornerRadius
    }
    val scrim by scope.transition.animateFloat(
        transitionSpec = {
            if (PredictiveBackGesture.isClosing(entryId)) tween(PREDICTIVE_BACK_MS, easing = LinearEasing) else AppMotion.containerTransform(0.001f)
        },
        label = "editorScrim",
    ) { state -> if (state == EnterExitState.Visible) 0.32f else 0f }
    // Forget the gesture mark when it was cancelled or the editor is gone.
    val exiting = scope.transition.targetState == EnterExitState.PostExit
    SideEffect { if (!exiting) PredictiveBackGesture.clear(entryId) }
    DisposableEffect(entryId) { onDispose { PredictiveBackGesture.clear(entryId) } }
    // The destination fills the (edge-to-edge) window, so the editor's final size is the window size.
    // Read it directly instead of BoxWithConstraints: no subcomposition inside the shared transition.
    val windowSize = LocalWindowInfo.current.containerDpSize
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = scrim.coerceIn(0f, 1f))) },
    ) {
        val fullSize = Modifier
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(windowSize)
        // The spatial spring overshoots a little: near the end of the transform the radius dips just
        // below zero, and a RoundedCornerShape with a negative radius throws while drawing (this was
        // the "opening a shift closes the app" crash). Never hand a negative radius to the shape.
        content(RoundedCornerShape(corner.coerceAtLeast(0.dp)), fullSize)
    }
}

/** Background for an editor surface so the morphing container is never transparent. */
@Composable
fun Modifier.editorBackground(shape: Shape): Modifier = background(MaterialTheme.colorScheme.surface, shape)
