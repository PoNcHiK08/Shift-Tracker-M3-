package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.reflect.KClass

data class HomeTab(
    val route: Any,
    val routeClass: KClass<*>,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

val HomeTabs = listOf(
    HomeTab(CalendarRoute, CalendarRoute::class, R.string.nav_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    HomeTab(SummaryRoute, SummaryRoute::class, R.string.nav_summary, Icons.Filled.Insights, Icons.Outlined.Insights),
    HomeTab(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** The floating bar's distance from the screen's sides. */
val FloatingBarSideMargin = 16.dp

/** The floating bar's distance above the system navigation bar (gesture handle or buttons): low, but clear of it. */
val FloatingBarBottomGap = 8.dp

/** The bar's height at the default font size (taller with a larger font: it is measured, see [HomeScreen]). */
val FloatingBarHeight = 72.dp

/**
 * How much of a tab's content the floating bar covers above the system navigation bar: lists add it to their
 * bottom padding so their last item can scroll clear of the bar. 0 outside the home tabs.
 */
val LocalHomeBarClearance = compositionLocalOf { 0.dp }

const val HOME_BAR_TAG = "home-bar"
const val HOME_BAR_INDICATOR_TAG = "home-bar-indicator"

// The stock M3 navigation indicator: 64 × 32 dp, fully rounded.
private val IndicatorWidth = 64.dp
private val IndicatorHeight = 32.dp
private val BarPaddingHorizontal = 8.dp
private val BarPaddingVertical = 10.dp

/**
 * Slight bounce, about 300 ms: overshoots by ~3 % of the distance (≈ 4 dp from one tab to the next), well inside
 * the room the 64 dp pill leaves around a 24 dp icon.
 */
private const val INDICATOR_DAMPING = 0.75f
private const val INDICATOR_STIFFNESS = 700f

/**
 * The home screen's floating navigation bar (M3): a fully rounded surfaceContainer bar with the stock tonal
 * elevation, an icon and an always visible label per tab, and ONE indicator pill (secondaryContainer) that slides
 * to the selected tab with a spring. The pill follows [selectedIndex], whatever changed it (a tap, a back gesture,
 * a widget opening a tab); it is put in place without animation the first time it is known.
 */
@Composable
fun HomeNavigationBar(
    tabs: List<HomeTab>,
    selectedIndex: Int,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavigationBarItemDefaults.colors()
    // The tab index the pill is at, as a float so it can be between two tabs while it moves.
    val position = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex !in tabs.indices) return@LaunchedEffect
        if (!placed) {
            position.snapTo(selectedIndex.toFloat())
            placed = true
        } else {
            position.animateTo(selectedIndex.toFloat(), spring(INDICATOR_DAMPING, INDICATOR_STIFFNESS))
        }
    }
    var rowWidth by remember { mutableIntStateOf(0) }

    Surface(
        modifier = modifier.fillMaxWidth().testTag(HOME_BAR_TAG),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = NavigationBarDefaults.Elevation,
    ) {
        Box(Modifier.heightIn(min = FloatingBarHeight).padding(horizontal = BarPaddingHorizontal, vertical = BarPaddingVertical)) {
            if (placed && rowWidth > 0) {
                Box(
                    Modifier
                        .offset {
                            // Read at layout time: the pill moves without recomposing the bar.
                            val itemWidth = rowWidth.toFloat() / tabs.size
                            val pill = IndicatorWidth.toPx()
                            IntOffset((itemWidth * position.value + (itemWidth - pill) / 2).roundToInt(), 0)
                        }
                        .size(IndicatorWidth, IndicatorHeight)
                        .clip(CircleShape)
                        .background(colors.selectedIndicatorColor)
                        .testTag(HOME_BAR_INDICATOR_TAG),
                )
            }
            Row(
                Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width },
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEachIndexed { index, tab ->
                    HomeBarItem(
                        tab = tab,
                        selected = index == selectedIndex,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeBarItem(tab: HomeTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NavigationBarItemDefaults.colors()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val iconColor by animateColorAsState(if (selected) colors.selectedIconColor else colors.unselectedIconColor, effects)
    val textColor by animateColorAsState(if (selected) colors.selectedTextColor else colors.unselectedTextColor, effects)
    val interactions = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab,
            interactionSource = interactions,
            // The ripple is drawn in the pill's shape around the icon, as on the stock bar.
            indication = null,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(IndicatorWidth, IndicatorHeight)
                .clip(CircleShape)
                .indication(interactions, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            // The visible label names the tab (TalkBack reads it once).
            Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = iconColor)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The clearance lists need for a bar [barHeight] tall: the bar plus its gap above the system navigation bar. */
fun homeBarClearance(barHeight: Dp): Dp = barHeight + FloatingBarBottomGap

/**
 * Whether the floating bar shows, from how the tab's content scrolls (a nested-scroll listener around the tabs, so
 * every scrolling screen counts without knowing about it): it hides once the content has moved [thresholdPx] down in
 * one go, and shows once it has moved [thresholdPx] up, so small or jittery movements never toggle it. It also shows
 * when the content can't scroll any further (the end of a list, or nothing to scroll), and when [show] is called (a
 * tab switch). Horizontal scrolling (the calendar's month pager) is ignored.
 */
@Stable
class HomeBarVisibility(private val thresholdPx: Float) {
    var visible by mutableStateOf(true)
        private set

    /** Content movement since the last direction change: negative = scrolled down (towards the end). */
    private var run = 0f

    fun show() {
        visible = true
        run = 0f
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            onScrolled(consumed.y, available.y)
            return Offset.Zero
        }
    }

    /** [moved]: how far the content scrolled (negative = down); [left]: what it couldn't scroll (it is at an end). */
    internal fun onScrolled(moved: Float, left: Float) {
        if (moved != 0f) {
            if (run != 0f && (moved < 0f) != (run < 0f)) run = 0f
            run += moved
            if (run <= -thresholdPx) {
                visible = false
                run = 0f
            } else if (run >= thresholdPx) {
                show()
            }
        }
        // Pushing past an end: the end of the list (or a screen with nothing to scroll) brings the bar back.
        if (abs(left) > END_SLOP_PX) show()
    }

    private companion object {
        const val END_SLOP_PX = 1f
    }
}

/** How far the content must move in one direction before the bar hides or shows. */
val HomeBarScrollThreshold = 24.dp

@Composable
fun rememberHomeBarVisibility(): HomeBarVisibility {
    val threshold = with(LocalDensity.current) { HomeBarScrollThreshold.toPx() }
    return remember(threshold) { HomeBarVisibility(threshold) }
}

/** Slide down + fade over ~200 ms (M3 short4), emphasized easing: decelerate in, accelerate out. */
private const val HIDE_DURATION_MS = 200
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Moves the bar below the bottom of the screen and fades it out while [visible] is false. [below] is how far its
 * bottom is from the screen's (the gap plus the system navigation bar), so it leaves the screen entirely.
 */
@Composable
fun Modifier.homeBarHiding(visible: Boolean, below: () -> Float): Modifier {
    val hidden by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(HIDE_DURATION_MS, easing = if (visible) EmphasizedDecelerate else EmphasizedAccelerate),
        label = "homeBarHidden",
    )
    return graphicsLayer {
        translationY = hidden * (size.height + below())
        alpha = 1f - hidden
    }
}
