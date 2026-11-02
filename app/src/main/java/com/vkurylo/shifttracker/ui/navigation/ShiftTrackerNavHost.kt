package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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

// Fade-through between bottom navigation tabs.
private val fadeThroughEnter = fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.96f)
private val fadeThroughExit = fadeOut(tween(90))

/**
 * Two levels of navigation:
 *  - the root stack holds the tabbed home screen and every detail screen, and moves between them
 *    with the One UI-style slide-over (the whole home screen, bottom bar included, slides away);
 *  - the tabs live in their own nested NavHost and switch with a fade-through.
 */
@Composable
fun ShiftTrackerNavHost() {
    val rootNav = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, scope) { SnackbarController(snackbarHostState, scope) }
    val rootEntry by rootNav.currentBackStackEntryAsState()
    val onHome = rootEntry?.destination?.hasRoute(MainRoute::class) != false

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = rootNav,
                startDestination = MainRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { slideOverEnter() },
                exitTransition = { slideOverExit() },
                popEnterTransition = { slideOverPopEnter() },
                popExitTransition = { slideOverPopExit() },
            ) {
                composable<MainRoute> {
                    Box(Modifier.slideOverScrim(this)) { HomeScreen(rootNav) }
                }
                composable<JobsRoute> {
                    Box(Modifier.slideOverScrim(this)) {
                        JobsScreen(onBack = { rootNav.navigateUp() })
                    }
                }
                composable<EditShiftRoute> {
                    Box(Modifier.slideOverScrim(this)) {
                        EditShiftScreen(
                            onClose = { rootNav.navigateUp() },
                            onManageJobs = { rootNav.navigate(JobsRoute) },
                        )
                    }
                }
                composable<EditBonusRoute> {
                    Box(Modifier.slideOverScrim(this)) {
                        EditBonusScreen(
                            onClose = { rootNav.navigateUp() },
                            onManageJobs = { rootNav.navigate(JobsRoute) },
                        )
                    }
                }
                composable<ImportIcsRoute> {
                    Box(Modifier.slideOverScrim(this)) {
                        ImportIcsScreen(
                            onClose = { rootNav.navigateUp() },
                            onManageJobs = { rootNav.navigate(JobsRoute) },
                        )
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
private fun HomeScreen(rootNav: NavHostController) {
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
            enterTransition = { fadeThroughEnter },
            exitTransition = { fadeThroughExit },
            popEnterTransition = { fadeThroughEnter },
            popExitTransition = { fadeThroughExit },
        ) {
            composable<CalendarRoute> {
                CalendarScreen(
                    onAddShift = { date -> rootNav.navigate(EditShiftRoute(dateEpochDay = date.toEpochDay())) },
                    onEditShift = { id -> rootNav.navigate(EditShiftRoute(shiftId = id)) },
                    onAddBonus = { date -> rootNav.navigate(EditBonusRoute(dateEpochDay = date.toEpochDay())) },
                    onEditBonus = { id -> rootNav.navigate(EditBonusRoute(bonusId = id)) },
                    onManageJobs = { rootNav.navigate(JobsRoute) },
                )
            }
            composable<SummaryRoute> {
                SummaryScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onManageJobs = { rootNav.navigate(JobsRoute) },
                    onImportIcs = { uri -> rootNav.navigate(ImportIcsRoute(uri.toString())) },
                )
            }
        }
    }
}
