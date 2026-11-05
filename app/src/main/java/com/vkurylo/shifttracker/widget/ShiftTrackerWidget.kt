package com.vkurylo.shifttracker.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.RowScope
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
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.theme.DarkColors
import com.vkurylo.shifttracker.ui.theme.LightColors
import dagger.hilt.android.EntryPointAccessors

/**
 * Home-screen widget: a shift calendar (the month, or the current week in one row), the shift in
 * progress or the next one, and "+ Shift". The layout follows the widget's width AND height
 * ([WidgetLayout]); it is composed for the exact sizes the launcher reports, so a 4×1 bar is drawn
 * for one row instead of squeezing the 4×2 layout into it. All content comes from
 * [WidgetStateBuilder]; this only lays it out.
 */
class ShiftTrackerWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

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
}

/** The taps, shared by every layout. */
private class WidgetActions(val open: Action, val add: Action, val nextShift: Action)

@Composable
private fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(WidgetLaunch.intent(context, null))
    val next = state.nextShift
    val actions = WidgetActions(
        open = open,
        add = actionStartActivity(WidgetLaunch.intent(context, if (state.noJobs) LaunchRequest.Jobs else LaunchRequest.NewShift)),
        nextShift = when {
            state.noJobs -> actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.Jobs))
            next != null -> actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.EditShift(next.shiftId)))
            else -> open
        },
    )
    val layout = WidgetLayout.of(size)
    val padding = when (layout) {
        WidgetLayout.COMPACT_ROW, WidgetLayout.WIDE_ROW -> 6.dp
        WidgetLayout.NARROW_STACK -> 8.dp
        WidgetLayout.BENTO -> 10.dp
    }
    val inner = DpSize(size.width - padding * 2, size.height - padding * 2)
    val fit = WidgetTextFit(context)
    Box(
        GlanceModifier.fillMaxSize()
            .rounded(GlanceTheme.colors.widgetBackground, R.dimen.widget_radius, R.drawable.widget_round_outer)
            .clickable(open)
            .padding(padding),
    ) {
        when (layout) {
            WidgetLayout.COMPACT_ROW -> CompactRow(state, actions, inner, fit)
            WidgetLayout.WIDE_ROW -> WideRow(state, actions, inner, fit)
            WidgetLayout.NARROW_STACK -> NarrowStack(state, actions, inner, fit)
            WidgetLayout.BENTO -> Bento(state, actions, inner, fit)
        }
    }
}

private val GAP = 6.dp
private val ROUND_BUTTON = 40.dp
private val PILL = 36.dp
private const val DETAIL_SP = 11f

/** A tile in the secondary container color with the next shift (or the empty message), tapping into it. */
@Composable
private fun NextShiftTile(state: WidgetState, actions: WidgetActions, modifier: GlanceModifier, contentWidth: Dp, contentHeight: Dp, fit: WidgetTextFit) {
    Column(
        modifier
            .rounded(GlanceTheme.colors.secondaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
            .clickable(actions.nextShift)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NextShiftLines(state, contentWidth, contentHeight, fit)
    }
}

/** Mode A, 2×1: the next shift and a round "+". */
@Composable
private fun CompactRow(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val button = minOf(ROUND_BUTTON, inner.height)
    val tileWidth = inner.width - GAP - button
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        NextShiftTile(state, actions, GlanceModifier.defaultWeight().fillMaxHeight(), tileWidth - 16.dp, inner.height - 4.dp, fit)
        Spacer(GlanceModifier.width(GAP))
        AddButton(label = "+", action = actions.add, modifier = GlanceModifier.size(button))
    }
}

/** Mode B, 3×1 / 4×1: this week | next shift | round "+", one row. */
@Composable
private fun WideRow(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val button = minOf(ROUND_BUTTON, inner.height)
    val tileWidth = (inner.width - GAP * 2 - button) / 2
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.open)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            WeekStrip(state, tileWidth - 8.dp, inner.height - 4.dp, fit)
        }
        Spacer(GlanceModifier.width(GAP))
        NextShiftTile(state, actions, GlanceModifier.defaultWeight().fillMaxHeight(), tileWidth - 16.dp, inner.height - 4.dp, fit)
        Spacer(GlanceModifier.width(GAP))
        AddButton(label = "+", action = actions.add, modifier = GlanceModifier.size(button))
    }
}

/**
 * "● Burger King" over "Tomorrow · 18:00–01:30" (or the empty message), each on one line and sized to
 * fit [width]; the second line is dropped only when [height] has no room for it.
 */
@Composable
private fun NextShiftLines(state: WidgetState, width: Dp, height: Dp, fit: WidgetTextFit) {
    val color = GlanceTheme.colors.onSecondaryContainer
    val next = state.nextShift
    if (next == null) {
        val message = state.emptyMessage.orEmpty()
        val sp = fit.sp(listOf(message), width, height, maxSp = 12f, minSp = 9f, bold = false)
        Text(message, style = TextStyle(color = color, fontSize = sp.sp), maxLines = if (height >= fit.lineHeight(sp) * 2) 2 else 1)
        return
    }
    val detail = "${next.whenLabel} · ${next.time}"
    val detailHeight = fit.lineHeight(DETAIL_SP)
    // "Tomorrow · 18:00–01:30" on one line when it fits, else "Tomorrow" over "18:00–01:30" (2×1).
    val oneLine = fit.sp(listOf(detail), width, detailHeight, maxSp = DETAIL_SP, minSp = 9f, bold = false)
    val split = !fit.fits(detail, width, oneLine, bold = false) && height >= fit.lineHeight(12f) + detailHeight * 2
    val detailLines = if (split) listOf(next.whenLabel, next.time) else listOf(detail)
    val showDetail = height >= detailHeight * detailLines.size + fit.lineHeight(12f)
    val dot = "● "
    val nameHeight = if (showDetail) height - detailHeight * detailLines.size else height
    val nameSp = fit.sp(listOf(dot + next.jobName), width, nameHeight, maxSp = 13f, minSp = 9f, bold = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(dot, style = TextStyle(color = ColorProvider(Color(next.color), Color(next.color)), fontSize = nameSp.sp))
        Text(next.jobName, style = TextStyle(color = color, fontSize = nameSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
    if (showDetail) {
        val detailSp = fit.sp(detailLines, width, detailHeight, maxSp = DETAIL_SP, minSp = 8f, bold = false)
        detailLines.forEach { Text(it, style = TextStyle(color = color, fontSize = detailSp.sp), maxLines = 1) }
    }
}

/**
 * One day of a calendar in a [cell]-sized circle: today filled with the primary color, a day with a
 * shift tinted with its job's color, other days plain. Null (another month): an empty cell.
 */
@Composable
private fun RowScope.DayCell(day: WidgetState.CalendarDay?, cell: Dp, sp: Float) {
    Box(GlanceModifier.defaultWeight().height(cell), contentAlignment = Alignment.Center) {
        if (day == null) return@Box
        val background = when {
            day.today -> GlanceTheme.colors.primary
            day.shiftColor != null -> Color(day.shiftColor).copy(alpha = SHIFT_TINT).let { ColorProvider(it, it) }
            else -> null
        }
        val textColor = if (day.today) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onPrimaryContainer
        val number = @Composable {
            Text(
                day.day.toString(),
                style = TextStyle(
                    color = textColor,
                    fontSize = sp.sp,
                    fontWeight = if (day.today || day.shiftColor != null) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
        if (background == null) {
            number()
        } else {
            Box(
                GlanceModifier.size(cell).rounded(background, R.dimen.widget_day_radius, R.drawable.widget_round_day),
                contentAlignment = Alignment.Center,
            ) { number() }
        }
    }
}

/** How much of a job's color tints its shift days (the number stays readable on it, light or dark). */
private const val SHIFT_TINT = 0.45f

/** A day number's font size for a [cell]-sized circle ("30" fits with a little room). */
private fun daySp(fit: WidgetTextFit, cell: Dp): Float =
    fit.sp(listOf("30"), cell * 0.8f, cell, maxSp = 13f, minSp = 7f, bold = true)

/** Weekday letters over the current week's days (the letters are dropped when the tile is too short). */
@Composable
private fun WeekStrip(state: WidgetState, width: Dp, height: Dp, fit: WidgetTextFit) {
    val labelHeight = fit.lineHeight(9f)
    val showLabels = height >= labelHeight + 20.dp
    val cell = minOf(width / 7, (if (showLabels) height - labelHeight else height) - 2.dp, 30.dp)
    val sp = daySp(fit, cell)
    Column(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showLabels) WeekdayRow(state, fit.sp(state.weekdays, width / 7, labelHeight, maxSp = 9f, minSp = 7f, bold = false))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            state.week.forEach { DayCell(it, cell, sp) }
        }
    }
}

@Composable
private fun WeekdayRow(state: WidgetState, sp: Float) {
    Row(GlanceModifier.fillMaxWidth()) {
        state.weekdays.forEach { label ->
            Text(
                label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = sp.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

/**
 * The month grid card: its name, the weekday letters and 4–6 weeks, sized to [width] × [height] (the
 * card's content area). Tapping it opens the app.
 */
@Composable
private fun MonthCalendar(state: WidgetState, actions: WidgetActions, modifier: GlanceModifier, width: Dp, height: Dp, fit: WidgetTextFit) {
    val titleHeight = fit.lineHeight(12f)
    val labelHeight = fit.lineHeight(9f)
    val rows = state.monthWeeks.size
    val rowHeight = (height - titleHeight - labelHeight - 2.dp) / rows
    val cell = minOf(width / 7, rowHeight, 30.dp)
    val sp = daySp(fit, cell)
    Column(
        modifier
            .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
            .clickable(actions.open)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(
            state.monthTitle,
            modifier = GlanceModifier.padding(start = 4.dp),
            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        WeekdayRow(state, fit.sp(state.weekdays, width / 7, labelHeight, maxSp = 9f, minSp = 7f, bold = false))
        Spacer(GlanceModifier.defaultWeight())
        state.monthWeeks.forEach { week ->
            Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { DayCell(it, cell, sp) }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** Mode C, 2×2 / 2×3: the month on top; the next shift (when there's room) and a full-width "+ Shift" below. */
@Composable
private fun NarrowStack(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val pill = if (inner.height >= 140.dp) PILL else 32.dp
    val shiftHeight = fit.lineHeight(12f) + fit.lineHeight(DETAIL_SP)
    // The next shift only when the month keeps at least 16 dp a week.
    val calendarMin = fit.lineHeight(12f) + fit.lineHeight(9f) + 14.dp + 16.dp * state.monthWeeks.size
    val showShift = inner.height - pill - GAP - shiftHeight - GAP >= calendarMin
    val cardHeight = inner.height - pill - GAP - (if (showShift) shiftHeight + GAP else 0.dp)
    Column(GlanceModifier.fillMaxSize()) {
        MonthCalendar(state, actions, GlanceModifier.fillMaxWidth().defaultWeight(), inner.width - 12.dp, cardHeight - 12.dp, fit)
        if (showShift) {
            Spacer(GlanceModifier.height(GAP))
            Column(GlanceModifier.fillMaxWidth().clickable(actions.nextShift).padding(horizontal = 4.dp)) {
                NextShiftLines(state, inner.width - 8.dp, shiftHeight, fit)
            }
        }
        Spacer(GlanceModifier.height(GAP))
        AddButton(label = state.addShiftLabel, action = actions.add, modifier = GlanceModifier.fillMaxWidth().height(pill))
    }
}

/** Mode D, 3×2 and up: the month (a little wider than half) | the next shift card over "+ Shift". */
@Composable
private fun Bento(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val gap = 8.dp
    val leftWidth = (inner.width - gap) * 0.56f
    val rightWidth = inner.width - gap - leftWidth
    Row(GlanceModifier.fillMaxSize()) {
        MonthCalendar(state, actions, GlanceModifier.width(leftWidth).fillMaxHeight(), leftWidth - 12.dp, inner.height - 12.dp, fit)
        Spacer(GlanceModifier.width(gap))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Column(
                GlanceModifier.fillMaxWidth().defaultWeight()
                    .rounded(GlanceTheme.colors.secondaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                    .clickable(actions.nextShift)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShiftCard(state, rightWidth - 20.dp, fit)
            }
            Spacer(GlanceModifier.height(GAP))
            AddButton(label = state.addShiftLabel, action = actions.add, modifier = GlanceModifier.fillMaxWidth().height(PILL))
        }
    }
}

/** The tall next-shift card: name, when, time (or the empty message). */
@Composable
private fun ShiftCard(state: WidgetState, width: Dp, fit: WidgetTextFit) {
    val onTile = GlanceTheme.colors.onSecondaryContainer
    val next = state.nextShift
    if (next == null) {
        Text(state.emptyMessage.orEmpty(), style = TextStyle(color = onTile, fontSize = 13.sp), maxLines = 2)
        return
    }
    val nameSp = fit.sp(listOf("● " + next.jobName), width, 40.dp, maxSp = 14f, minSp = 10f, bold = true)
    val timeSp = fit.sp(listOf(next.time, next.whenLabel), width, 40.dp, maxSp = 13f, minSp = 9f, bold = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("● ", style = TextStyle(color = ColorProvider(Color(next.color), Color(next.color)), fontSize = nameSp.sp))
        Text(next.jobName, style = TextStyle(color = onTile, fontSize = nameSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
    Text(next.whenLabel, style = TextStyle(color = onTile, fontSize = minOf(timeSp, 12f).sp), maxLines = 1)
    Text(next.time, style = TextStyle(color = onTile, fontSize = timeSp.sp, fontWeight = FontWeight.Medium), maxLines = 1)
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
