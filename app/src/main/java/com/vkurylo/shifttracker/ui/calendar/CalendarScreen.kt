package com.vkurylo.shifttracker.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.components.BonusCard
import com.vkurylo.shifttracker.ui.components.CircleToSquircle
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobColorDot
import com.vkurylo.shifttracker.ui.components.MorphShape
import com.vkurylo.shifttracker.ui.components.RollingAmounts
import com.vkurylo.shifttracker.ui.components.RollingText
import com.vkurylo.shifttracker.ui.components.SegmentedListDefaults
import com.vkurylo.shifttracker.ui.components.ShiftCard
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.navigation.SharedKeys
import com.vkurylo.shifttracker.ui.navigation.sharedContainer
import com.vkurylo.shifttracker.ui.theme.expressiveClickable
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.WeekDays
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel
import com.vkurylo.shifttracker.ui.util.shiftsCountLabel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val PAGE_COUNT = 1200 // 100 years
private const val INITIAL_PAGE = PAGE_COUNT / 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onAddShift: (LocalDate) -> Unit,
    onEditShift: (Long) -> Unit,
    onAddBonus: (LocalDate) -> Unit,
    onEditBonus: (Long) -> Unit,
    onManageJobs: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val scope = rememberCoroutineScope()
    // Saved with the pager's page: if the activity is recreated in another month (e.g. just after
    // midnight on the 1st), the restored page still means the same month.
    val baseMonth = rememberSaveable { YearMonth.now() }
    val pagerState = rememberPagerState(initialPage = INITIAL_PAGE) { PAGE_COUNT }
    val visibleMonth by remember { derivedStateOf { baseMonth.plusMonths((pagerState.currentPage - INITIAL_PAGE).toLong()) } }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            viewModel.showMonth(baseMonth.plusMonths((page - INITIAL_PAGE).toLong()))
        }
    }

    fun goToMonth(month: YearMonth) {
        val page = INITIAL_PAGE + ChronoUnit.MONTHS.between(baseMonth, month).toInt()
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, PAGE_COUNT - 1)) }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hasJobs = state.jobs.isNotEmpty()
    val spatialOffset = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    // The month name rolls in the swipe direction.
                    AnimatedContent(
                        targetState = visibleMonth,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInVertically(spatialOffset) { h -> if (forward) h else -h } + fadeIn(effects)) togetherWith
                                (slideOutVertically(spatialOffset) { h -> if (forward) -h else h } + fadeOut(effects))
                        },
                        label = "monthTitle",
                    ) { month -> Text(Formatters.monthYear(month, locale)) }
                },
                actions = {
                    IconButton(onClick = { goToMonth(visibleMonth.minusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
                    }
                    IconButton(onClick = { goToMonth(visibleMonth.plusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
                    }
                    IconButton(onClick = {
                        goToMonth(YearMonth.now())
                        viewModel.selectDate(LocalDate.now())
                    }) {
                        Icon(Icons.Filled.Today, stringResource(R.string.today))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(CALENDAR_LIST_TAG),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            // The month at a glance, first thing on screen: earnings, hours and shifts in one compact row.
            if (state.loading || hasJobs) {
                item(key = "month") {
                    MonthStrip(
                        summary = state.monthSummary,
                        defaultCurrency = state.defaultCurrency,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
                    )
                }
            }
            item(key = "weekdays") { WeekdayHeader() }
            item(key = "grid") {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val month = baseMonth.plusMonths((page - INITIAL_PAGE).toLong())
                    MonthGrid(
                        month = month,
                        shiftsByDate = state.shiftsByDate,
                        bonusDates = state.bonusesByDate.keys,
                        selectedDate = state.selectedDate,
                        onDayClick = viewModel::selectDate,
                        // Only the month on screen takes focus. Its neighbours are composed off-screen for
                        // smooth swiping; when the activity is recreated without touch mode (keyboard, DeX,
                        // tests) the system hands initial focus to the first day it finds, and focusing a
                        // neighbour's day scrolled the calendar away from the month the user was on.
                        modifier = Modifier
                            .focusProperties { onEnter = { if (page != pagerState.currentPage) cancelFocusChange() } }
                            .focusGroup(),
                    )
                }
            }
            if (!state.loading && !hasJobs) {
                item(key = "no-jobs") {
                    EmptyState(
                        icon = Icons.Filled.WorkOutline,
                        title = stringResource(R.string.no_jobs_title),
                        message = stringResource(R.string.no_jobs_message),
                        action = { Button(onClick = onManageJobs) { Text(stringResource(R.string.add_job)) } },
                    )
                }
            } else {
                item(key = "day") {
                    DayPanel(
                        date = state.selectedDate,
                        shiftsByDate = state.shiftsByDate,
                        bonusesByDate = state.bonusesByDate,
                        onAddShift = { onAddShift(state.selectedDate) },
                        onEditShift = onEditShift,
                        onAddBonus = { onAddBonus(state.selectedDate) },
                        onEditBonus = onEditBonus,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val locale = currentLocale()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        WeekDays.forEach { day ->
            Text(
                text = Formatters.weekdayNarrow(day, locale),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = if (day.value >= 6) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    shiftsByDate: Map<LocalDate, List<ShiftWithJob>>,
    bonusDates: Set<LocalDate>,
    selectedDate: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1 // Monday = 1
    val today = LocalDate.now()

    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        // Always 6 rows so the pager height never jumps. Rows are a little flatter than square (the
        // day itself stays round), which makes room for the month strip above the calendar.
        for (row in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - leadingBlanks + 1
                    Box(Modifier.weight(1f).aspectRatio(DAY_CELL_ASPECT), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            DayCell(
                                date = date,
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                shifts = shiftsByDate[date].orEmpty(),
                                hasBonus = date in bonusDates,
                                onClick = { onDayClick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A day cell. Its background is a [MorphShape]: a circle that flows into an M3 Expressive squircle
 * when the day gets selected, driven by a bouncy spatial spring (with a little scale pop), while
 * the colours follow the non-overshooting effects spring.
 */
@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    shifts: List<ShiftWithJob>,
    hasBonus: Boolean,
    onClick: () -> Unit,
) {
    val locale = currentLocale()
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val hasEntries = shifts.isNotEmpty() || hasBonus
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Under the finger every day turns into the squircle (M3 Expressive shape morph on press),
    // so the press feedback has the cell's own shape instead of a square ripple.
    val morph by animateFloatAsState(if (isSelected || pressed) 1f else 0f, motion.fastSpatialSpec(), label = "cellMorph")
    val scale by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            pressed -> 0.92f
            hasEntries || isToday -> 0.86f
            else -> 0.6f
        },
        animationSpec = motion.fastSpatialSpec(),
        label = "cellScale",
    )
    val background by animateColorAsState(
        targetValue = when {
            isSelected -> colors.primary
            isToday -> colors.primaryContainer
            hasEntries || pressed -> colors.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "cellColor",
    )
    val content by animateColorAsState(
        targetValue = when {
            isSelected -> colors.onPrimary
            isToday -> colors.onPrimaryContainer
            else -> colors.onSurface
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "cellContent",
    )
    val shape = MorphShape(CircleToSquircle, morph)
    val jobColors = shifts.map { it.job }.distinctBy { it.id }.map { it.composeColor() }
    val description = buildString {
        append(Formatters.fullDate(date, locale))
        if (shifts.isNotEmpty()) append(", ").append(pluralStringResource(R.plurals.shift_count, shifts.size, shifts.size))
        if (hasBonus) append(", ").append(stringResource(R.string.bonus))
    }

    Box(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxSize()
            .expressiveClickable(
                onClick = onClick,
                pressedScale = 0.9f,
                interactionSource = interactionSource,
                indication = null, // the morphing background is the press feedback
            )
            .semantics {
                contentDescription = description
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(background),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected || isToday || hasEntries) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(8.dp),
            ) {
                jobColors.take(3).forEach { color ->
                    JobColorDot(
                        color = color,
                        size = 6.dp,
                        modifier = if (isSelected) Modifier.border(1.dp, colors.onPrimary, CircleShape) else Modifier,
                    )
                }
                if (hasBonus) {
                    Icon(Icons.Filled.Redeem, contentDescription = null, modifier = Modifier.size(8.dp), tint = content)
                }
            }
        }
    }
}

/**
 * Bento panel with the selected day: its total, shift/bonus cards (each expands into its editor)
 * and the add actions. Lives in the screen itself (not a bottom sheet window), so tapping through
 * days is instant and the cards can fly into the editors with a shared-element transition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DayPanel(
    date: LocalDate,
    shiftsByDate: Map<LocalDate, List<ShiftWithJob>>,
    bonusesByDate: Map<LocalDate, List<BonusWithJob>>,
    onAddShift: () -> Unit,
    onEditShift: (Long) -> Unit,
    onAddBonus: () -> Unit,
    onEditBonus: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val shifts = shiftsByDate[date].orEmpty()
    val bonuses = bonusesByDate[date].orEmpty()
    // Per currency: a day with entries in two currencies shows two amounts, never their sum.
    val total = ShiftCalculator.earnings(shifts, bonuses)
    val spatialOffset = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val spatialSize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // The old day disappears at once and the new one springs in, so two days never overlap;
    // SizeTransform springs the panel height between them.
    val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Clipped, so the next day's title slides in under the amount instead of over it.
                Column(Modifier.weight(1f).clipToBounds()) {
                    AnimatedContent(
                        targetState = date,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally(spatialOffset) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(enterFade)) togetherWith
                                ExitTransition.None using SizeTransform(clip = false) { _, _ -> spatialSize }
                        },
                        label = "dayTitle",
                    ) { d ->
                        val dayShifts = shiftsByDate[d].orEmpty()
                        Column {
                            Text(Formatters.fullDate(d, locale), style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = if (dayShifts.isEmpty()) {
                                    stringResource(R.string.no_shifts_this_day)
                                } else {
                                    "${hoursLabel(dayShifts.sumOf { it.shift.paidMinutes })} · ${shiftsCountLabel(dayShifts.size)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (total.byCurrency.values.any { it > 0 }) {
                    Spacer(Modifier.width(12.dp))
                    RollingAmounts(
                        amounts = Amounts(total.byCurrency.filterValues { it > 0 }),
                        fallbackCurrency = Currencies.DEFAULT,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        horizontalAlignment = Alignment.End,
                    )
                }
            }

            // The entries of the selected day; switching days cross-fades and springs the height.
            AnimatedContent(
                targetState = date,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(spatialOffset) { w -> if (forward) w / 6 else -w / 6 } + fadeIn(enterFade)) togetherWith
                        ExitTransition.None using SizeTransform(clip = false) { _, _ -> spatialSize }
                },
                label = "dayEntries",
            ) { d ->
                val dayShifts = shiftsByDate[d].orEmpty()
                val dayBonuses = bonusesByDate[d].orEmpty()
                if (dayShifts.isEmpty() && dayBonuses.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.EventAvailable,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.day_free),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    // The day's entries form one segmented group (Android 16 list style).
                    val count = dayShifts.size + dayBonuses.size
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(SegmentedListDefaults.Gap),
                    ) {
                        dayShifts.forEachIndexed { index, item ->
                            ShiftCard(item = item, onClick = { onEditShift(item.shift.id) }, index = index, count = count)
                        }
                        dayBonuses.forEachIndexed { index, item ->
                            BonusCard(
                                item = item,
                                onClick = { onEditBonus(item.bonus.id) },
                                index = dayShifts.size + index,
                                count = count,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAddShift,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .sharedContainer(SharedKeys.NEW_SHIFT, ButtonDefaults.shape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_shift))
                }
                FilledTonalButton(
                    onClick = onAddBonus,
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .sharedContainer(SharedKeys.NEW_BONUS, ButtonDefaults.shape),
                ) {
                    Icon(Icons.Filled.Redeem, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bonus))
                }
            }
        }
    }
}

/** The calendar screen's scrolling list (strip, calendar, day, …). */
internal const val CALENDAR_LIST_TAG = "calendar-list"

/** Width : height of a calendar row's cell (the round day inside is as wide as the cell is high). */
private const val DAY_CELL_ASPECT = 1.15f

internal const val MONTH_TILE_EARNINGS = "month-tile-earnings"
internal const val MONTH_TILE_HOURS = "month-tile-hours"
internal const val MONTH_TILE_SHIFTS = "month-tile-shifts"

/**
 * The month at a glance, at the top of the calendar: earnings, hours and shifts as one connected row
 * (segments 2 dp apart, large outer and small inner corners, like the app's segmented lists). All
 * three share one height (whichever needs more, e.g. with a large font), so they always line up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MonthStrip(summary: MonthSummary, defaultCurrency: String, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val colors = MaterialTheme.colorScheme
    val outer = 20.dp
    val inner = 4.dp
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Tile(
            container = colors.primaryContainer,
            shape = RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner),
            modifier = Modifier.weight(1.7f).fillMaxHeight().testTag(MONTH_TILE_EARNINGS),
        ) {
            Text(stringResource(R.string.earnings_month), style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer)
            // One line per currency (several only when jobs are paid in different currencies).
            RollingAmounts(
                amounts = summary.totalEarnings,
                fallbackCurrency = defaultCurrency,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onPrimaryContainer,
            )
        }
        Tile(
            container = colors.secondaryContainer,
            shape = RoundedCornerShape(inner),
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(MONTH_TILE_HOURS),
        ) {
            Text(stringResource(R.string.hours), style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
            RollingText(
                text = Formatters.decimalHours(summary.totalPaidMinutes, locale),
                value = summary.totalPaidMinutes,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSecondaryContainer,
            )
        }
        Tile(
            container = colors.tertiaryContainer,
            shape = RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer),
            modifier = Modifier.weight(0.85f).fillMaxHeight().testTag(MONTH_TILE_SHIFTS),
        ) {
            Text(stringResource(R.string.shifts), style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer)
            RollingText(
                text = summary.shiftCount.toString(),
                value = summary.shiftCount.toLong(),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun Tile(container: Color, shape: Shape, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = shape, color = container) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) { content() }
    }
}
