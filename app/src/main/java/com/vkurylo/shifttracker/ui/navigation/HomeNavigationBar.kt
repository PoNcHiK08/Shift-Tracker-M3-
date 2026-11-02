package com.vkurylo.shifttracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
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

/** Bar height without the system navigation inset (the M3 NavigationBar's fixed height). */
val HomeNavigationBarHeight = 80.dp

// Larger than the stock 64×32 dp M3 indicator.
private val IndicatorWidth = 80.dp
private val IndicatorHeight = 44.dp

/**
 * Icon-only bottom bar. Each item is an M3 Expressive [ToggleButton] — the same component as the
 * Week/Month/Year selector — so selecting a tab morphs the indicator into a pill with the
 * expressive spring motion, and pressing squeezes its corners.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeNavigationBar(
    tabs: List<HomeTab>,
    isSelected: (HomeTab) -> Boolean,
    onSelect: (HomeTab) -> Unit,
) {
    val shapes = ToggleButtonShapes(
        shape = RoundedCornerShape(14.dp),
        pressedShape = RoundedCornerShape(10.dp),
        checkedShape = RoundedCornerShape(50),
    )
    val colors = ToggleButtonDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    NavigationBar {
        tabs.forEach { tab ->
            val selected = isSelected(tab)
            val label = stringResource(tab.labelRes)
            Box(Modifier.weight(1f).height(HomeNavigationBarHeight), contentAlignment = Alignment.Center) {
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { onSelect(tab) },
                    shapes = shapes,
                    colors = colors,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .width(IndicatorWidth)
                        .height(IndicatorHeight)
                        .semantics { role = Role.Tab },
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = label,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

