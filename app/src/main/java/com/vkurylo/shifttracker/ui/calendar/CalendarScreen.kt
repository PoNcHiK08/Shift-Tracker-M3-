package com.vkurylo.shifttracker.ui.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobColorDot
import com.vkurylo.shifttracker.ui.components.ShiftListItem
import com.vkurylo.shifttracker.ui.components.StatBlock
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.WeekDays
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel
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
    onManageJobs: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val scope = rememberCoroutineScope()
    val baseMonth = remember { YearMonth.now() }
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(Formatters.monthYear(visibleMonth, locale)) },
                actions = {
                    IconButton(onClick = { goToMonth(visibleMonth.minusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
                    }
                    IconButton(onClick = { goToMonth(visibleMonth.plusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
                    }
                    IconButton(onClick = { goToMonth(YearMonth.now()) }) {
                        Icon(Icons.Filled.Today, stringResource(R.string.today))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (hasJobs) {
                val label = stringResource(R.string.add_shift)
                ExtendedFloatingActionButton(
                    // M3 clears the FAB's inner text semantics; give TalkBack an explicit label.
                    modifier = Modifier.semantics { contentDescription = label },
                    onClick = {
                        val today = LocalDate.now()
                        onAddShift(if (YearMonth.from(today) == visibleMonth) today else visibleMonth.atDay(1))
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(label) },
                    expanded = scrollBehavior.state.contentOffset > -10f,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
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
                        onDayClick = viewModel::selectDate,
                    )
                }
            }
            item(key = "summary") {
                MonthStatsCard(
                    minutes = state.monthSummary.totalPaidMinutes,
                    earnings = state.monthSummary.totalEarningsGrosze,
                    shifts = state.monthSummary.shiftCount,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
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
            } else if (!state.loading && state.monthShifts.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.EventAvailable,
                        title = stringResource(R.string.no_shifts_title),
                        message = stringResource(R.string.no_shifts_message),
                    )
                }
            } else {
                items(state.monthShifts, key = { it.shift.id }) { item ->
                    ShiftListItem(
                        item = item,
                        showDate = true,
                        modifier = Modifier.animateItem().clickable { onEditShift(item.shift.id) },
                    )
                }
            }
        }
    }

    state.selectedDate?.let { date ->
        DayBottomSheet(
            date = date,
            shifts = state.shiftsByDate[date].orEmpty(),
            canAdd = hasJobs,
            onDismiss = viewModel::dismissDay,
            onAddShift = {
                viewModel.dismissDay()
                onAddShift(date)
            },
            onEditShift = { id ->
                viewModel.dismissDay()
                onEditShift(id)
            },
        )
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
    onDayClick: (LocalDate) -> Unit,
) {
    val firstDay = month.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1 // Monday = 1
    val totalCells = 42 // always 6 rows so the pager height never jumps
    val today = LocalDate.now()

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        for (row in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    val dayNumber = index - leadingBlanks + 1
                    Box(Modifier.weight(1f).aspectRatio(0.9f), contentAlignment = Alignment.Center) {
                        if (index < totalCells && dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            DayCell(
                                date = date,
                                isToday = date == today,
                                shifts = shiftsByDate[date].orEmpty(),
                                onClick = { onDayClick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    shifts: List<ShiftWithJob>,
    onClick: () -> Unit,
) {
    val locale = currentLocale()
    val hasShifts = shifts.isNotEmpty()
    val background by animateColorAsState(
        when {
            isToday -> MaterialTheme.colorScheme.primary
            hasShifts -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surface
        },
        label = "dayBackground",
    )
    val contentColor = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val jobColors = shifts.map { it.job }.distinctBy { it.id }.map { it.composeColor() }
    val description = buildString {
        append(Formatters.fullDate(date, locale))
        if (hasShifts) append(", ").append(pluralStringResource(R.plurals.shift_count, shifts.size, shifts.size))
    }

    Column(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isToday || hasShifts) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(7.dp)) {
            jobColors.take(3).forEach { color ->
                JobColorDot(
                    color = color,
                    size = 7.dp,
                    modifier = if (isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.onPrimary, CircleShape) else Modifier,
                )
            }
            if (jobColors.size > 3) {
                Text("+", style = MaterialTheme.typography.labelSmall, color = contentColor)
            }
        }
    }
}

@Composable
private fun MonthStatsCard(minutes: Long, earnings: Long, shifts: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(
                label = stringResource(R.string.earned),
                value = Formatters.money(earnings, locale),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1.4f),
            )
            StatBlock(
                label = stringResource(R.string.hours),
                value = Formatters.decimalHours(minutes, locale),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            StatBlock(
                label = stringResource(R.string.shifts),
                value = shifts.toString(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(0.8f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayBottomSheet(
    date: LocalDate,
    shifts: List<ShiftWithJob>,
    canAdd: Boolean,
    onDismiss: () -> Unit,
    onAddShift: () -> Unit,
    onEditShift: (Long) -> Unit,
) {
    val locale = currentLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = shifts.size < 4)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                text = Formatters.fullDate(date, locale),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (shifts.isNotEmpty()) {
                val minutes = shifts.sumOf { it.shift.paidMinutes }
                val earnings = shifts.sumOf { it.shift.earningsGrosze }
                Text(
                    text = "${hoursLabel(minutes)} · ${Formatters.money(earnings, locale)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (shifts.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_shifts_this_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                shifts.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ShiftListItem(
                        item = item,
                        modifier = Modifier.clickable { onEditShift(item.shift.id) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                }
            }
            if (canAdd) {
                FilledTonalButton(
                    onClick = onAddShift,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(52.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_shift))
                }
            }
        }
    }
}
