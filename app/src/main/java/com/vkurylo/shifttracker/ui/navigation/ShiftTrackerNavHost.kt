package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.bonus.EditBonusScreen
import com.vkurylo.shifttracker.ui.calendar.CalendarScreen
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.importics.ImportIcsScreen
import com.vkurylo.shifttracker.ui.jobs.JobsScreen
import com.vkurylo.shifttracker.ui.settings.SettingsScreen
import com.vkurylo.shifttracker.ui.shift.EditShiftScreen
import com.vkurylo.shifttracker.ui.summary.SummaryScreen
import kotlin.reflect.KClass

private data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(CalendarRoute, CalendarRoute::class, R.string.nav_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    TopLevelDestination(SummaryRoute, SummaryRoute::class, R.string.nav_summary, Icons.Filled.Insights, Icons.Outlined.Insights),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

private const val DURATION = 300

// Material shared-axis (X) transition for drilling into detail screens.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisEnter(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { it / 8 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { -it / 8 } + fadeOut(tween(DURATION / 2))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopEnter(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { -it / 8 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { it / 8 } + fadeOut(tween(DURATION / 2))

// Fade-through between bottom navigation tabs.
private val fadeThroughEnter = fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.96f)
private val fadeThroughExit = fadeOut(tween(90))

private fun NavDestination?.isTopLevel(): Boolean =
    this?.hierarchy?.any { dest -> topLevelDestinations.any { dest.hasRoute(it.routeClass) } } == true

@Composable
fun ShiftTrackerNavHost() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, scope) { SnackbarController(snackbarHostState, scope) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination == null || currentDestination.isTopLevel()

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(),
                ) {
                    NavigationBar {
                        topLevelDestinations.forEach { destination ->
                            val selected = currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
                            val label = stringResource(destination.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = CalendarRoute,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                enterTransition = { sharedAxisEnter() },
                exitTransition = { sharedAxisExit() },
                popEnterTransition = { sharedAxisPopEnter() },
                popExitTransition = { sharedAxisPopExit() },
            ) {
                composable<CalendarRoute>(
                    enterTransition = { if (initialState.destination.isTopLevel()) fadeThroughEnter else sharedAxisPopEnter() },
                    exitTransition = { if (targetState.destination.isTopLevel()) fadeThroughExit else sharedAxisExit() },
                ) {
                    CalendarScreen(
                        onAddShift = { date -> navController.navigate(EditShiftRoute(dateEpochDay = date.toEpochDay())) },
                        onEditShift = { id -> navController.navigate(EditShiftRoute(shiftId = id)) },
                        onAddBonus = { date -> navController.navigate(EditBonusRoute(dateEpochDay = date.toEpochDay())) },
                        onEditBonus = { id -> navController.navigate(EditBonusRoute(bonusId = id)) },
                        onManageJobs = { navController.navigate(JobsRoute) },
                    )
                }
                composable<SummaryRoute>(
                    enterTransition = { if (initialState.destination.isTopLevel()) fadeThroughEnter else sharedAxisPopEnter() },
                    exitTransition = { if (targetState.destination.isTopLevel()) fadeThroughExit else sharedAxisExit() },
                ) {
                    SummaryScreen()
                }
                composable<SettingsRoute>(
                    enterTransition = { if (initialState.destination.isTopLevel()) fadeThroughEnter else sharedAxisPopEnter() },
                    exitTransition = { if (targetState.destination.isTopLevel()) fadeThroughExit else sharedAxisExit() },
                ) {
                    SettingsScreen(
                        onManageJobs = { navController.navigate(JobsRoute) },
                        onImportIcs = { uri -> navController.navigate(ImportIcsRoute(uri.toString())) },
                    )
                }
                composable<JobsRoute> {
                    JobsScreen(onBack = { navController.navigateUp() })
                }
                composable<EditShiftRoute> {
                    EditShiftScreen(
                        onClose = { navController.navigateUp() },
                        onManageJobs = { navController.navigate(JobsRoute) },
                    )
                }
                composable<EditBonusRoute> {
                    EditBonusScreen(
                        onClose = { navController.navigateUp() },
                        onManageJobs = { navController.navigate(JobsRoute) },
                    )
                }
                composable<ImportIcsRoute> {
                    ImportIcsScreen(
                        onClose = { navController.navigateUp() },
                        onManageJobs = { navController.navigate(JobsRoute) },
                    )
                }
            }
        }
    }
}
