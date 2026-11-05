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
import androidx.glance.layout.ColumnScope
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
private class WidgetActions(val open: Action, val summary: Action, val add: Action, val nextShift: Action)

@Composable
private fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(WidgetLaunch.intent(context, null))
    val next = state.nextShift
    val actions = WidgetActions(
        open = open,
        summary = actionStartActivity(WidgetLaunch.intent(context, LaunchRequest.Summary)),
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
private const val HOURS_SP = 11f

/** The first amount (plus "+1" for more currencies) and "43 h · Net": what the 1-row layouts show. */
private fun oneRowAmount(state: WidgetState) = state.amountLines.first() + (state.moreCurrencies?.let { " $it" } ?: "")
private fun oneRowHours(state: WidgetState) = listOfNotNull(state.hours, state.modeTag).joinToString(" · ")

/**
 * Earnings and hours, stacked and centred, sized to fit [width] × [height] (the tile's content area):
 * the hours line is dropped only when the tile is too short for two lines.
 */
@Composable
private fun EarningsLines(state: WidgetState, width: Dp, height: Dp, fit: WidgetTextFit) {
    val color = GlanceTheme.colors.onPrimaryContainer
    val hoursHeight = fit.lineHeight(HOURS_SP)
    val showHours = height >= hoursHeight + fit.lineHeight(12f)
    val amount = oneRowAmount(state)
    val amountSp = fit.sp(listOf(amount), width, if (showHours) height - hoursHeight else height, maxSp = 18f, minSp = 9f, bold = true)
    Text(amount, style = TextStyle(color = color, fontSize = amountSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    if (showHours) {
        val hours = oneRowHours(state)
        val hoursSp = fit.sp(listOf(hours), width, hoursHeight, maxSp = HOURS_SP, minSp = 8f, bold = false)
        Text(hours, style = TextStyle(color = color, fontSize = hoursSp.sp), maxLines = 1)
    }
}

/** Mode A, 2×1: the earnings tile and a round "+". */
@Composable
private fun CompactRow(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val button = minOf(ROUND_BUTTON, inner.height)
    val tileWidth = inner.width - GAP - button
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.summary)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EarningsLines(state, tileWidth - 16.dp, inner.height - 4.dp, fit)
        }
        Spacer(GlanceModifier.width(GAP))
        AddButton(label = "+", action = actions.add, modifier = GlanceModifier.size(button))
    }
}

/** Mode B, 3×1 / 4×1: earnings | next shift | round "+", one row. */
@Composable
private fun WideRow(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val button = minOf(ROUND_BUTTON, inner.height)
    val tileWidth = (inner.width - GAP * 2 - button) / 2
    val contentWidth = tileWidth - 16.dp
    val contentHeight = inner.height - 4.dp
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.summary)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EarningsLines(state, contentWidth, contentHeight, fit)
        }
        Spacer(GlanceModifier.width(GAP))
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight()
                .rounded(GlanceTheme.colors.secondaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.nextShift)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NextShiftLines(state, contentWidth, contentHeight, fit)
        }
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
    val detailHeight = fit.lineHeight(HOURS_SP)
    val showDetail = height >= detailHeight + fit.lineHeight(12f)
    val dot = "● "
    val nameSp = fit.sp(listOf(dot + next.jobName), width, if (showDetail) height - detailHeight else height, maxSp = 13f, minSp = 9f, bold = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(dot, style = TextStyle(color = ColorProvider(Color(next.color), Color(next.color)), fontSize = nameSp.sp))
        Text(next.jobName, style = TextStyle(color = color, fontSize = nameSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
    if (showDetail) {
        val detailSp = fit.sp(listOf(detail), width, detailHeight, maxSp = HOURS_SP, minSp = 8f, bold = false)
        Text(detail, style = TextStyle(color = color, fontSize = detailSp.sp), maxLines = 1)
    }
}

/** "This month" with the Gross/Net tag. */
@Composable
private fun MonthTitle(state: WidgetState) {
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
                maxLines = 1,
            )
        }
    }
}

/**
 * The tall earnings card: title, every currency line (one shared size that fits [width]) and hours.
 * [height] is its content height; amounts never get more than what's left after title and hours.
 */
@Composable
private fun ColumnScope.EarningsCard(state: WidgetState, width: Dp, height: Dp, maxSp: Float, fit: WidgetTextFit) {
    val color = GlanceTheme.colors.onPrimaryContainer
    val fixed = fit.lineHeight(12f) + fit.lineHeight(13f) + (if (state.moreCurrencies != null) fit.lineHeight(12f) else 0.dp)
    val perLine = (height - fixed) / state.amountLines.size
    val top = if (state.amountLines.size > 1) minOf(maxSp, 17f) else maxSp
    val amountSp = fit.sp(state.amountLines, width, perLine, maxSp = top, minSp = 10f, bold = true)
    MonthTitle(state)
    Spacer(GlanceModifier.defaultWeight())
    state.amountLines.forEach { line ->
        Text(line, style = TextStyle(color = color, fontSize = amountSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
    state.moreCurrencies?.let {
        Text(it, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1)
    }
    Text(state.hours, style = TextStyle(color = color, fontSize = 13.sp), maxLines = 1)
}

/** Mode C, 2×2 / 2×3: earnings card on top; next shift and a full-width "+ Shift" below. */
@Composable
private fun NarrowStack(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val pill = if (inner.height >= 140.dp) PILL else 32.dp
    val shiftHeight = fit.lineHeight(12f) + fit.lineHeight(HOURS_SP)
    // The next shift only when the card above keeps room for title, amount and hours.
    val showShift = inner.height - pill - GAP - shiftHeight - GAP >= 64.dp
    val cardHeight = inner.height - pill - GAP - (if (showShift) shiftHeight + GAP else 0.dp)
    Column(GlanceModifier.fillMaxSize()) {
        Column(
            GlanceModifier.fillMaxWidth().defaultWeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.summary)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            EarningsCard(state, inner.width - 20.dp, cardHeight - 12.dp, maxSp = 20f, fit)
        }
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

/**
 * Mode D, 3×2 and up: the earnings card (a little wider than half, so a 3×2 amount fits) | the next
 * shift card over "+ Shift".
 */
@Composable
private fun Bento(state: WidgetState, actions: WidgetActions, inner: DpSize, fit: WidgetTextFit) {
    val gap = 8.dp
    val leftWidth = (inner.width - gap) * 0.56f
    val rightWidth = inner.width - gap - leftWidth
    Row(GlanceModifier.fillMaxSize()) {
        Column(
            GlanceModifier.width(leftWidth).fillMaxHeight()
                .rounded(GlanceTheme.colors.primaryContainer, R.dimen.widget_inner_radius, R.drawable.widget_round_inner)
                .clickable(actions.summary)
                .padding(10.dp),
        ) {
            EarningsCard(state, leftWidth - 20.dp, inner.height - 20.dp, maxSp = 22f, fit)
        }
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
