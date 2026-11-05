package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vkurylo.shifttracker.CrashReporter
import com.vkurylo.shifttracker.ui.bonus.EditBonusScreen
import com.vkurylo.shifttracker.ui.calendar.CalendarScreen
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.importics.ImportIcsScreen
import com.vkurylo.shifttracker.ui.jobs.JobsScreen
import com.vkurylo.shifttracker.ui.settings.SettingsScreen
import com.vkurylo.shifttracker.ui.shift.EditShiftScreen
import com.vkurylo.shifttracker.ui.summary.SummaryScreen
import com.vkurylo.shifttracker.widget.LaunchRequest
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Test tag of the morphing editor container (its size follows the container transform). */
const val EDITOR_CONTAINER_TAG = "editor-container"

/** The tabbed home screen (calendar / summary / settings with the bottom bar). */
@Serializable
data object MainRoute

/**
 * Two levels of navigation inside one SharedTransitionLayout, all on Material 3 Expressive motion
 * (see M3Transitions.kt):
 *  - the root stack holds the tabbed home screen and every detail screen. Editors opened from a
 *    card/button grow out of it (container transform); other screens use the shared axis X, and
 *    the predictive back gesture shrinks the closing screen away;
 *  - the tabs live in their own nested NavHost and switch with a fade through.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ShiftTrackerNavHost(launchRequest: LaunchRequest? = null, onLaunchHandled: () -> Unit = {}) {
    val rootNav = rememberNavController()
    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        onLaunchHandled()
        val top = rootNav.currentBackStackEntry?.destination
        // An open editor stays as it is: nothing is stacked on it and nothing typed is thrown away.
        val editorOpen = top?.hasRoute(EditShiftRoute::class) == true || top?.hasRoute(EditBonusRoute::class) == true
        if (editorOpen) return@LaunchedEffect
        when (request) {
            // No shared key: the card isn't on screen, so the editor slides in instead of growing out of it.
            LaunchRequest.NewShift -> rootNav.navigate(EditShiftRoute(dateEpochDay = LocalDate.now().toEpochDay()))
            is LaunchRequest.EditShift -> rootNav.navigate(EditShiftRoute(shiftId = request.shiftId))
            LaunchRequest.Jobs -> if (top?.hasRoute(JobsRoute::class) != true) rootNav.navigate(JobsRoute)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, scope) { SnackbarController(snackbarHostState, scope) }
    val rootEntry by rootNav.currentBackStackEntryAsState()
    val onHome = rootEntry?.destination?.hasRoute(MainRoute::class) != false
    val transitions = rememberM3Transitions()
    SideEffect {
        CrashReporter.currentScreen = if (onHome) "home" else rootEntry?.destination?.route ?: "?"
    }

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navController = rootNav,
                        startDestination = MainRoute,
                        modifier = Modifier.fillMaxSize(),
                        // Container transform: the editor itself is the shared element, the screen below
                        // stays put until the card has finished growing (or shrinking back).
                        // Forward navigation is never a back gesture: drop a leftover mark of these entries.
                        // (Only theirs: a NavHost composing its first screen runs this too, e.g. the tab
                        // host re-created under an editor that a gesture is closing.)
                        enterTransition = {
                            PredictiveBackGesture.clear(initialState.id, targetState.id)
                            if (targetState.containerKey() != null) EnterTransition.None else transitions.forwardEnter()
                        },
                        exitTransition = {
                            PredictiveBackGesture.clear(initialState.id, targetState.id)
                            if (targetState.containerKey() != null) ExitTransition.KeepUntilTransitionsFinished else transitions.forwardExit()
                        },
                        popEnterTransition = {
                            if (initialState.containerKey() != null) EnterTransition.None else transitions.backwardEnter()
                        },
                        popExitTransition = {
                            if (initialState.containerKey() != null) {
                                // An editor closed by back that NavHost didn't treat as a gesture: a quick
                                // flick released before NavHost's predictive back caught up (it needs a
                                // couple of frames), or the system back button. It closes like a released
                                // gesture (flies into its card) instead of the card morph being cut short.
                                if (!PredictiveBackGesture.isClosedByButton(initialState.id)) {
                                    PredictiveBackGesture.mark(initialState.id, PredictiveBackGesture.swipeEdge)
                                }
                                ExitTransition.KeepUntilTransitionsFinished
                            } else {
                                transitions.backwardExit()
                            }
                        },
                        predictivePopEnterTransition = { transitions.predictiveBackEnter() },
                        // Only called for a back gesture. An editor opened from a card is then held and
                        // flown into its card (ContainerTransformHost); any other screen is drawn by
                        // predictiveBackSurface.
                        predictivePopExitTransition = { swipeEdge ->
                            PredictiveBackGesture.mark(initialState.id, swipeEdge)
                            ExitTransition.KeepUntilTransitionsFinished
                        },
                    ) {
                        composable<MainRoute> {
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                HomeScreen(rootNav, transitions)
                            }
                        }
                        composable<JobsRoute> {
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(Modifier.predictiveBackSurface(this@composable)) {
                                    JobsScreen(onBack = { rootNav.navigateUp() })
                                }
                            }
                        }
                        composable<EditShiftRoute> { entry ->
                            val key = entry.toRoute<EditShiftRoute>().sharedKey
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(if (key == null) Modifier.predictiveBackSurface(this@composable) else Modifier) {
                                    ContainerTransformHost(this@composable, sharedKey = key) { shape, containerModifier, contentModifier ->
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .then(containerModifier)
                                                .sharedContainer(key, shape, isEditor = true)
                                                .clip(shape)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .testTag(EDITOR_CONTAINER_TAG),
                                        ) {
                                            EditShiftScreen(
                                                onClose = {
                                                    PredictiveBackGesture.closingByButton(entry.id)
                                                    rootNav.navigateUp()
                                                },
                                                onManageJobs = { rootNav.navigate(JobsRoute) },
                                                modifier = contentModifier,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        composable<EditBonusRoute> { entry ->
                            val key = entry.toRoute<EditBonusRoute>().sharedKey
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(if (key == null) Modifier.predictiveBackSurface(this@composable) else Modifier) {
                                    ContainerTransformHost(this@composable, sharedKey = key) { shape, containerModifier, contentModifier ->
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .then(containerModifier)
                                                .sharedContainer(key, shape, isEditor = true)
                                                .clip(shape)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .testTag(EDITOR_CONTAINER_TAG),
                                        ) {
                                            EditBonusScreen(
                                                onClose = {
                                                    PredictiveBackGesture.closingByButton(entry.id)
                                                    rootNav.navigateUp()
                                                },
                                                onManageJobs = { rootNav.navigate(JobsRoute) },
                                                modifier = contentModifier,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        composable<ImportIcsRoute> {
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(Modifier.predictiveBackSurface(this@composable)) {
                                    ImportIcsScreen(
                                        onClose = { rootNav.navigateUp() },
                                        onManageJobs = { rootNav.navigate(JobsRoute) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // An editor closed by a back gesture flies into its card above everything (draw only).
            EditorFlightOverlay()
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Keep snackbars above the bottom bar on the home screen.
                    .padding(bottom = if (onHome) HomeNavigationBarHeight else 0.dp),
            )
        }
    }
}

@Composable
private fun HomeScreen(rootNav: NavHostController, transitions: M3Transitions) {
    val tabNav = rememberNavController()
    val tabEntry by tabNav.currentBackStackEntryAsState()
    val currentTab = tabEntry?.destination

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            HomeNavigationBar(
                tabs = HomeTabs,
                isSelected = { tab -> currentTab?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true },
                onSelect = { tab ->
                    tabNav.navigate(tab.route) {
                        popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = tabNav,
            startDestination = CalendarRoute,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            // Top-level destinations: fade through (also when going back to the first tab).
            enterTransition = {
                PredictiveBackGesture.clear(initialState.id, targetState.id)
                transitions.fadeThroughEnter()
            },
            exitTransition = {
                PredictiveBackGesture.clear(initialState.id, targetState.id)
                transitions.fadeThroughExit()
            },
            popEnterTransition = { transitions.fadeThroughEnter() },
            popExitTransition = { transitions.fadeThroughExit() },
            // Back gesture from Summary/Settings: the tab shrinks away over the calendar (M3 predictive
            // back) instead of two half-transparent screens cross-fading under the finger.
            predictivePopEnterTransition = { transitions.predictiveBackEnter() },
            predictivePopExitTransition = { swipeEdge ->
                PredictiveBackGesture.mark(initialState.id, swipeEdge)
                ExitTransition.KeepUntilTransitionsFinished
            },
        ) {
            composable<CalendarRoute> {
                CalendarScreen(
                    onAddShift = { date ->
                        rootNav.navigate(EditShiftRoute(dateEpochDay = date.toEpochDay(), sharedKey = SharedKeys.NEW_SHIFT))
                    },
                    onEditShift = { id -> rootNav.navigate(EditShiftRoute(shiftId = id, sharedKey = SharedKeys.shift(id))) },
                    onAddBonus = { date ->
                        rootNav.navigate(EditBonusRoute(dateEpochDay = date.toEpochDay(), sharedKey = SharedKeys.NEW_BONUS))
                    },
                    onEditBonus = { id -> rootNav.navigate(EditBonusRoute(bonusId = id, sharedKey = SharedKeys.bonus(id))) },
                    onManageJobs = { rootNav.navigate(JobsRoute) },
                )
            }
            composable<SummaryRoute> {
                Box(Modifier.predictiveBackSurface(this)) { SummaryScreen() }
            }
            composable<SettingsRoute> {
                Box(Modifier.predictiveBackSurface(this)) {
                    SettingsScreen(
                        onManageJobs = { rootNav.navigate(JobsRoute) },
                        onImportIcs = { uri -> rootNav.navigate(ImportIcsRoute(uri.toString())) },
                    )
                }
            }
        }
    }
}
