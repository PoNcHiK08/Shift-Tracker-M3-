package com.vkurylo.shifttracker.ui.navigation

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt
import com.vkurylo.shifttracker.ui.theme.AppMotion

/*
 * Container transform: a shift/bonus card (or an "add" button) physically grows into the full-screen
 * editor and shrinks back on close (and, after a back gesture, flies back into it: see
 * ContainerTransformHost).
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
 * While a back gesture holds an editor, its shared element is switched off: the gesture must not
 * morph the editor into its card under the finger. The card then simply stays in its place behind
 * it, and after release [EditorFlightOverlay] flies the editor into it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: String?, shape: Shape, isEditor: Boolean = false): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalRootAnimatedVisibilityScope.current ?: return this
    if (key == null) return this
    val effects = AppMotion.quicker(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val config = remember(isEditor, entryId) {
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                get() = !(isEditor && PredictiveBackGesture.isClosing(entryId))

            override val shouldKeepEnabledForOngoingAnimation: Boolean get() = false
        }
    }
    // Where the card is, for the flight after a back gesture. Measured in place (before sharedBounds).
    val cardBounds = if (isEditor) Modifier else Modifier.onGloballyPositioned { CardBounds[key] = it.boundsInRoot() }
    return with(sharedScope) {
        this@sharedContainer.then(cardBounds).sharedBounds(
            sharedContentState = rememberSharedContentState(key, config),
            animatedVisibilityScope = visibilityScope,
            enter = if (isEditor) fadeIn(effects) else EnterTransition.None,
            exit = if (isEditor) fadeOut(effects) else ExitTransition.None,
            zIndexInOverlay = if (isEditor) 1f else 0f,
            boundsTransform = BoundsTransform { _, _ -> AppMotion.containerTransform(Rect.VisibilityThreshold) },
            // The container itself follows the animated bounds, so its background always fills the
            // morphing shape; the editor inside keeps its full-screen layout (see ContainerTransformHost).
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** Where each card (the closed end of a container transform) is on screen, by shared key. */
internal object CardBounds {
    private val bounds = mutableStateMapOf<String, Rect>()

    operator fun get(key: String): Rect? = bounds[key]

    operator fun set(key: String, rect: Rect) {
        if (bounds[key] != rect) bounds[key] = rect
    }
}

/** How much an editor shrinks at most while a back gesture holds it (M3 predictive back: to 90 %). */
private const val EDITOR_DRAG_SHRINK = 0.1f

/** The scrim under the editor. */
private const val EDITOR_SCRIM = 0.32f

/** Of the scrim, how much lifts while the editor is held by a back gesture. */
private const val EDITOR_DRAG_SCRIM_LIFT = 0.4f

/** How far the held editor drifts towards the swiping edge. */
private val EditorDragDrift = 8.dp

/**
 * Back gesture progress (0..1, the finger's travel) -> how far the held editor has shrunk (0..1 of
 * [EDITOR_DRAG_SHRINK]). Rubber band: it follows the finger at first and then stops at a limit, so
 * however far the finger goes the editor never turns into its card under the finger.
 */
internal fun editorDragAmount(progress: Float): Float = 1f - exp(-4f * progress.coerceIn(0f, 1f))

private class EditorGestureLatch {
    var releasedAt = Float.NaN
    var edge = BackEventCompat.EDGE_LEFT
    var bounds: Rect? = null
    var flown = false
}

/**
 * Root of an editor that may be opened with a container transform: dims the screen underneath while
 * the card grows, and gives [content]
 *  - the editor's animated shape (rounded like the card at the start, square when full screen),
 *  - a modifier for the editor's container (a back gesture shrinks it, see below),
 *  - a modifier for the editor's content that keeps its full-screen layout pinned to the container's
 *    top-left corner, so the growing container reveals the editor like a window opening (instead of
 *    squeezing the form into a card-sized box, or showing its middle first).
 *
 * Back gesture: NavHost drives it (so the calendar is there behind the editor), but the editor is
 * not morphed by the finger. While the finger is down it only shrinks a little, rounds its corners
 * and drifts towards the finger, on a spring and up to a limit ([editorDragAmount]); cancelling
 * springs it back. On release a snapshot of it flies into its card from exactly where it was held
 * ([EditorFlightOverlay]), on a calm spring (a little longer than a normal close), however far the finger went.
 */
@Composable
fun ContainerTransformHost(
    scope: AnimatedVisibilityScope,
    sharedKey: String?,
    content: @Composable (editorShape: Shape, containerModifier: Modifier, contentModifier: Modifier) -> Unit,
) {
    if (sharedKey == null) {
        content(RoundedCornerShape(0.dp), Modifier, Modifier)
        return
    }
    val transition = scope.transition
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry
    val entryId = entry?.id
    val exiting = transition.targetState == EnterExitState.PostExit
    val gesture = exiting && PredictiveBackGesture.isClosing(entryId)
    // During a gesture everything on this transition is linear on the gesture's timeline, so the
    // timeline is PREDICTIVE_BACK_MS long and its position is the finger's progress.
    val corner by transition.animateDp(
        transitionSpec = { if (PredictiveBackGesture.isClosing(entryId)) tween(PREDICTIVE_BACK_MS, easing = LinearEasing) else AppMotion.containerTransform(Dp.VisibilityThreshold) },
        label = "editorCorner",
    ) { state -> if (state == EnterExitState.Visible) 0.dp else ContainerCornerRadius }
    val scrim by transition.animateFloat(
        transitionSpec = { if (PredictiveBackGesture.isClosing(entryId)) tween(PREDICTIVE_BACK_MS, easing = LinearEasing) else AppMotion.containerTransform(0.001f) },
        label = "editorScrim",
    ) { state -> if (state == EnterExitState.Visible) EDITOR_SCRIM else 0f }
    val progress by transition.animateFloat(
        transitionSpec = { tween(PREDICTIVE_BACK_MS, easing = LinearEasing) },
        label = "editorBackProgress",
    ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
    val lifecycle by (entry?.lifecycle?.currentStateFlow ?: remember { MutableStateFlow(Lifecycle.State.RESUMED) })
        .collectAsState()

    val latch = remember(gesture) { EditorGestureLatch() }
    if (gesture) {
        latch.edge = PredictiveBackGesture.swipeEdge
        // NavHost pops the entry when the gesture is committed.
        if (latch.releasedAt.isNaN() && !lifecycle.isAtLeast(Lifecycle.State.STARTED)) latch.releasedAt = progress
    }
    val released = gesture && !latch.releasedAt.isNaN()
    // Springy: a quick swipe overshoots the limit a little and settles; a cancel springs back.
    val held by animateFloatAsState(
        targetValue = if (gesture) editorDragAmount(if (released) latch.releasedAt else progress) else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "editorHeld",
    )
    val amount = held.coerceIn(0f, 1.1f)

    // A snapshot of the editor for the flight, taken as the gesture starts (the form can't change
    // under a back gesture).
    val layer = rememberGraphicsLayer()
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(gesture) {
        snapshot = null
        if (gesture) {
            withFrameNanos { } // recorded at least once
            snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
        }
    }
    val heldCorner = ContainerCornerRadius * amount.coerceAtMost(1f)
    val heldScrim = EDITOR_SCRIM * (1f - EDITOR_DRAG_SCRIM_LIFT * amount.coerceAtMost(1f))
    if (released && !latch.flown) {
        val image = snapshot
        val from = latch.bounds
        if (image != null && from != null) {
            latch.flown = true
            SideEffect { EditorFlights.start(EditorFlight(sharedKey, image, from, heldCorner, heldScrim)) }
        }
    }
    // Forget the gesture mark when it was cancelled or the editor is gone.
    SideEffect { if (!exiting) PredictiveBackGesture.clear(entryId) }
    DisposableEffect(entryId) { onDispose { PredictiveBackGesture.clear(entryId) } }

    // The destination fills the (edge-to-edge) window, so the editor's final size is the window size.
    // Read it directly instead of BoxWithConstraints: no subcomposition inside the shared transition.
    val windowSize = LocalWindowInfo.current.containerDpSize
    val density = LocalDensity.current
    // Released without a snapshot (a flick quicker than a frame): fade out over the rest of the timeline.
    val fallbackAlpha = if (released && !latch.flown) 1f - ((progress - latch.releasedAt) / (1f - latch.releasedAt).coerceAtLeast(0.01f)).coerceIn(0f, 1f) else 1f
    Box(
        Modifier
            .fillMaxSize()
            // Hidden from the frame its flight is drawn (not before: no empty frame in between).
            .graphicsLayer { alpha = if (EditorFlights.isFlying(sharedKey)) 0f else fallbackAlpha }
            .drawBehind {
                val dim = if (gesture) heldScrim else scrim
                drawRect(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
            },
    ) {
        // Held by the gesture: the container itself gets smaller (a layout change, so its bounds are
        // where the flight starts), centred and drifting towards the finger.
        val container = Modifier
            .layout { measurable, constraints ->
                val scale = 1f - EDITOR_DRAG_SHRINK * amount
                val width = (constraints.maxWidth * scale).roundToInt()
                val height = (constraints.maxHeight * scale).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                val towardsFinger = if (latch.edge == BackEventCompat.EDGE_LEFT) 1 else -1
                val drift = (towardsFinger * with(density) { EditorDragDrift.toPx() } * amount.coerceAtMost(1f)).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((constraints.maxWidth - width) / 2 + drift, (constraints.maxHeight - height) / 2)
                }
            }
            .onGloballyPositioned { latch.bounds = it.boundsInRoot() }
            .drawWithContent {
                if (gesture) {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                } else {
                    drawContent()
                }
            }
        // ...and the content shrinks with it, so it looks like the same screen, only smaller.
        val fullSize = Modifier
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(windowSize)
            .graphicsLayer {
                val scale = 1f - EDITOR_DRAG_SHRINK * amount
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        // The spatial spring overshoots a little: near the end of the transform the radius dips just
        // below zero, and a RoundedCornerShape with a negative radius throws while drawing (this was
        // the "opening a shift closes the app" crash). Never hand a negative radius to the shape.
        val radius = (if (gesture) heldCorner else corner).coerceAtLeast(0.dp)
        content(RoundedCornerShape(radius), container, fullSize)
    }
}

/** Where on its way to the card (0..1) the flying editor starts to dissolve, and over how much of the way. */
private const val FLIGHT_FADE_FROM = 0.2f
private const val FLIGHT_FADE_LENGTH = 0.7f

/** How far ahead the flight's clock starts: one 60 Hz frame (from rest the spring barely moves in it). */
private const val FLIGHT_HEAD_START_NANOS = 16_666_667L

/** An editor closed by a back gesture, flying from where it was held into its card. */
internal class EditorFlight(
    val key: String,
    val image: ImageBitmap,
    val from: Rect,
    val fromCorner: Dp,
    val fromScrim: Float,
) {
    /** The flight has been drawn: from now on the editor itself can disappear. */
    var shown by mutableStateOf(false)
}

internal object EditorFlights {
    var current: EditorFlight? by mutableStateOf(null)
        private set

    fun start(flight: EditorFlight) {
        current = flight
    }

    fun finish(flight: EditorFlight) {
        if (current === flight) current = null
    }

    fun isFlying(key: String): Boolean = current?.let { it.key == key && it.shown } == true
}

/**
 * Draws the flight of an editor closed by a back gesture, above all screens: the held editor shrinks
 * into its card on the container-transform spring (calmer after a gesture, see AppMotion), its corners
 * round to the card's, it dissolves before it reaches the card and the scrim lifts. It only draws; touches
 * go to the screen below.
 */
@Composable
fun EditorFlightOverlay(modifier: Modifier = Modifier) {
    val flight = EditorFlights.current ?: return
    val t = remember(flight) { mutableFloatStateOf(0f) }
    LaunchedEffect(flight) {
        launch {
            withFrameNanos { } // the first frame of the flight is on screen (over the editor, same place)
            flight.shown = true
        }
        // The flight's clock starts one frame early: the frame the editor hands over to the flight
        // already moves, instead of the spring spending it at rest (one static frame fewer).
        val spring = TargetBasedAnimation(
            AppMotion.containerTransform(0.001f, afterBackGesture = true), Float.VectorConverter, 0f, 1f,
        )
        var start = Long.MIN_VALUE
        do {
            val now = withFrameNanos { it }
            if (start == Long.MIN_VALUE) start = now - FLIGHT_HEAD_START_NANOS
            val playTime = now - start
            t.floatValue = spring.getValueFromNanos(playTime)
        } while (!spring.isFinishedFromNanos(playTime))
        EditorFlights.finish(flight)
    }
    val surface = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxSize()) {
        val p = t.floatValue
        val done = p.coerceIn(0f, 1f)
        val from = flight.from
        // No card on screen (scrolled away): shrink towards the middle instead.
        val to = CardBounds[flight.key] ?: Rect(from.center, 0f).inflate(from.minDimension * 0.05f)
        val rect = Rect(
            left = lerp(from.left, to.left, p),
            top = lerp(from.top, to.top, p),
            right = lerp(from.right, to.right, p),
            bottom = lerp(from.bottom, to.bottom, p),
        )
        drawRect(Color.Black.copy(alpha = (flight.fromScrim * (1f - done)).coerceIn(0f, 1f)))
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        val radius = lerp(flight.fromCorner.toPx(), ContainerCornerRadius.toPx(), done).coerceAtLeast(0f)
        // The editor dissolves as a whole while it shrinks, and is gone before it reaches the card's
        // size: the card is already there in its place, so no square-to-card morph is ever seen.
        val alpha = 1f - FastOutSlowInEasing.transform(((done - FLIGHT_FADE_FROM) / FLIGHT_FADE_LENGTH).coerceIn(0f, 1f))
        if (alpha <= 0f) return@Canvas
        val outline = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
        clipPath(outline) {
            drawRect(surface, topLeft = rect.topLeft, size = rect.size, alpha = alpha)
            // Pinned to the top-left like the container transform, scaled with the width.
            val scale = rect.width / flight.image.width
            drawImage(
                image = flight.image,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize((flight.image.width * scale).roundToInt(), (flight.image.height * scale).roundToInt()),
                alpha = alpha,
            )
        }
    }
}

/** Background for an editor surface so the morphing container is never transparent. */
@Composable
fun Modifier.editorBackground(shape: Shape): Modifier = background(MaterialTheme.colorScheme.surface, shape)
