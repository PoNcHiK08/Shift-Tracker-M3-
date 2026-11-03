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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.serialization.Serializable

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
fun ShiftTrackerNavHost() {
    val rootNav = rememberNavController()
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
                        enterTransition = {
                            if (targetState.containerKey() != null) EnterTransition.None else transitions.forwardEnter()
                        },
                        exitTransition = {
                            if (targetState.containerKey() != null) ExitTransition.KeepUntilTransitionsFinished else transitions.forwardExit()
                        },
                        popEnterTransition = {
                            if (initialState.containerKey() != null) EnterTransition.None else transitions.backwardEnter()
                        },
                        popExitTransition = {
                            if (initialState.containerKey() != null) ExitTransition.KeepUntilTransitionsFinished else transitions.backwardExit()
                        },
                        predictivePopEnterTransition = { transitions.predictiveBackEnter() },
                        predictivePopExitTransition = { swipeEdge ->
                            if (initialState.containerKey() != null) {
                                ExitTransition.KeepUntilTransitionsFinished // the editor shrinks back into its card
                            } else {
                                transitions.predictiveBackExit(swipeEdge)
                            }
                        },
                    ) {
                        composable<MainRoute> {
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                HomeScreen(rootNav, transitions)
                            }
                        }
                        composable<JobsRoute> {
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(Modifier.closingScreenShape(this@composable)) {
                                    JobsScreen(onBack = { rootNav.navigateUp() })
                                }
                            }
                        }
                        composable<EditShiftRoute> { entry ->
                            val key = entry.toRoute<EditShiftRoute>().sharedKey
                            CompositionLocalProvider(LocalRootAnimatedVisibilityScope provides this) {
                                Box(if (key == null) Modifier.closingScreenShape(this@composable) else Modifier) {
                                    ContainerTransformHost(this@composable, enabled = key != null) { shape, contentModifier ->
                                        Box(Modifier.fillMaxSize().sharedContainer(key, shape, isEditor = true).background(MaterialTheme.colorScheme.surface)) {
                                            EditShiftScreen(
                                                onClose = { rootNav.navigateUp() },
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
                                Box(if (key == null) Modifier.closingScreenShape(this@composable) else Modifier) {
                                    ContainerTransformHost(this@composable, enabled = key != null) { shape, contentModifier ->
                                        Box(Modifier.fillMaxSize().sharedContainer(key, shape, isEditor = true).background(MaterialTheme.colorScheme.surface)) {
                                            EditBonusScreen(
                                                onClose = { rootNav.navigateUp() },
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
                                Box(Modifier.closingScreenShape(this@composable)) {
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
            enterTransition = { transitions.fadeThroughEnter() },
            exitTransition = { transitions.fadeThroughExit() },
            popEnterTransition = { transitions.fadeThroughEnter() },
            popExitTransition = { transitions.fadeThroughExit() },
            // Back gesture from Summary/Settings: the tab shrinks away over the calendar (M3 predictive
            // back) instead of two half-transparent screens cross-fading under the finger.
            predictivePopEnterTransition = { transitions.predictiveBackEnter() },
            predictivePopExitTransition = { swipeEdge -> transitions.predictiveBackExit(swipeEdge) },
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
                Box(Modifier.closingScreenShape(this)) { SummaryScreen() }
            }
            composable<SettingsRoute> {
                Box(Modifier.closingScreenShape(this)) {
                    SettingsScreen(
                        onManageJobs = { rootNav.navigate(JobsRoute) },
                        onImportIcs = { uri -> rootNav.navigate(ImportIcsRoute(uri.toString())) },
                    )
                }
            }
        }
    }
}
