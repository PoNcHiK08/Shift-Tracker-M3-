package com.vkurylo.shifttracker.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.theme.DarkColors
import com.vkurylo.shifttracker.ui.theme.LightColors
import dagger.hilt.android.EntryPointAccessors

/**
 * Home-screen widget: this month's earnings (per currency, Gross/Net tag), paid hours, the shift in
 * progress or the next one, and "+ Shift". Two layouts: compact (~2×1: the total and "+") and medium
 * (~4×2: everything). All content comes from [WidgetStateBuilder]; this only lays it out.
 */
class ShiftTrackerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val model = entryPoint.widgetRepository().current()
        provideContent {
            // Dynamic colors on Android 12+ (when the app uses them too), otherwise the app's own scheme.
            val colors = if (model.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GlanceTheme.colors
            } else {
                ColorProviders(light = LightColors, dark = DarkColors)
            }
            GlanceTheme(colors = colors) { WidgetContent(model.state) }
        }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 48.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
    }
}

@Composable
private fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val open = actionStartActivity(WidgetLaunch.intent(context, null))
    val summary = actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.Summary))
    val add = actionStartActivity(WidgetLaunch.intent(context, if (state.noJobs) LaunchRequest.Jobs else LaunchRequest.NewShift))
    val compact = LocalSize.current.width < ShiftTrackerWidget.MEDIUM.width || LocalSize.current.height < ShiftTrackerWidget.MEDIUM.height
    Box(
        GlanceModifier.fillMaxSize()
            .rounded(GlanceTheme.colors.widgetBackground, R.dimen.widget_radius, R.drawable.widget_round_outer)
            .clickable(open)
            .padding(if (compact) 6.dp else 10.dp),
    ) {
        if (compact) Compact(state, summary, add) else Medium(state, summary, add, open)
    }
}

@Composable
private fun Compact(state: WidgetState, summary: Action, add: Action) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(summary)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.amountLines.first() + (state.moreCurrencies?.let { " $it" } ?: ""),
                style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                listOfNotNull(state.hours, state.modeTag).joinToString(" · "),
                style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        AddButton(label = "+", action = add, modifier = GlanceModifier.size(44.dp))
    }
}

@Composable
private fun Medium(state: WidgetState, summary: Action, add: Action, open: Action) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxSize()) {
        // Earnings tile.
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(summary)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.monthTitle, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp), maxLines = 1)
                state.modeTag?.let { tag ->
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        tag,
                        modifier = GlanceModifier
                            .rounded(GlanceTheme.colors.surface, R.dimen.widget_tag_radius, R.drawable.widget_round_tag)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            state.amountLines.forEach { line ->
                Text(
                    line,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = if (state.amountLines.size > 1) 17.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
            state.moreCurrencies?.let {
                Text(it, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp), maxLines = 1)
            }
            Text(state.hours, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            // Next shift tile (or the empty state).
            val next = state.nextShift
            val tileAction = when {
                state.noJobs -> actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.Jobs))
                next != null -> actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.EditShift(next.shiftId)))
                else -> open
            }
            Column(
                GlanceModifier.fillMaxWidth().defaultWeight()
                    .rounded(GlanceTheme.colors.secondaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                    .clickable(tileAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onTile = GlanceTheme.colors.onSecondaryContainer
                if (next != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("●", style = TextStyle(color = ColorProvider(Color(next.color), Color(next.color)), fontSize = 12.sp))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(next.jobName, style = TextStyle(color = onTile, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                    Text(next.whenLabel, style = TextStyle(color = onTile, fontSize = 12.sp), maxLines = 1)
                    Text(next.time, style = TextStyle(color = onTile, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                } else {
                    Text(state.emptyMessage.orEmpty(), style = TextStyle(color = onTile, fontSize = 13.sp), maxLines = 2)
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            AddButton(label = state.addShiftLabel, action = add, modifier = GlanceModifier.fillMaxWidth().height(36.dp))
        }
    }
}

@Composable
private fun AddButton(label: String, action: Action, modifier: GlanceModifier) {
    Box(
        modifier
            .rounded(GlanceTheme.colors.primary, R.dimen.widget_button_radius, R.drawable.widget_round_button)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/**
 * A rounded background: real corner clipping on Android 12+ (system widget radii), a tinted rounded
 * shape drawable before that (cornerRadius isn't supported there).
 */
private fun GlanceModifier.rounded(color: ColorProvider, radius: Int, fallbackShape: Int): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(radius)
    } else {
        background(ImageProvider(fallbackShape), colorFilter = ColorFilter.tint(color))
    }
