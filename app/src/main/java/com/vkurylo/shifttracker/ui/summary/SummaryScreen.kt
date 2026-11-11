package com.vkurylo.shifttracker.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.ui.components.BonusCard
import com.vkurylo.shifttracker.ui.components.ChoiceButtonGroup
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobAvatar
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.PeriodSwitcher
import com.vkurylo.shifttracker.ui.components.RollingAmounts
import com.vkurylo.shifttracker.ui.components.RollingText
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.SegmentedItem
import com.vkurylo.shifttracker.ui.components.SegmentedListDefaults
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.ui.util.EarningsModeTag
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel
import com.vkurylo.shifttracker.ui.util.shiftsCountLabel
import java.time.LocalDate
import java.time.YearMonth

const val SUMMARY_ADD_MONTH_BONUS_TAG = "summary-add-month-bonus"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onEditBonus: (Long) -> Unit = {},
    onAddMonthBonus: (YearMonth) -> Unit = {},
    viewModel: SummaryViewModel = hiltViewModel(),
    payoutEditor: PayoutEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    // The job and month (yyyymm) whose received payments are open in the sheet; 0: closed.
    var sheetJobId by rememberSaveable { mutableLongStateOf(0L) }
    var sheetMonthKey by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(payoutEditor) {
        payoutEditor.events.collect { event ->
            when (event) {
                PayoutEvent.Saved -> snackbar.show(context.getString(R.string.received_pay_saved))
                is PayoutEvent.Deleted -> snackbar.show(
                    message = context.getString(R.string.received_pay_deleted),
                    actionLabel = context.getString(R.string.undo),
                    onAction = { payoutEditor.undoDelete(event.payout) },
                )
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val range = state.range

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
                actions = {
                    if (LocalDate.now() !in range) {
                        IconButton(onClick = viewModel::goToToday) {
                            Icon(Icons.Filled.Today, stringResource(R.string.current_period))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item(key = "period") {
                PeriodSelector(
                    selected = range.period,
                    onSelect = viewModel::setPeriod,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item(key = "switcher") {
                PeriodSwitcher(
                    period = range,
                    label = { periodLabel(it) },
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    // Paging slides the label in the direction of travel; switching week/month/year fades.
                    direction = { from, to -> if (from.period != to.period) 0 else to.from.compareTo(from.from) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "hero") {
                // One card that stays put: switching week/month/year or paging rolls its numbers.
                TotalsCard(range.period, state.summary, state.defaultCurrency, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (range.period == SummaryPeriod.WEEK && state.summary.fixedPayShiftCount > 0) {
                item(key = "fixed-pay-note") {
                    Text(
                        stringResource(R.string.week_fixed_pay_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            // A month with received pay but no entries still has something to show.
            if (!state.loading && state.summary.isEmpty && state.receivedPay.isEmpty() && state.receivedPayYear.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.QueryStats,
                        title = stringResource(R.string.summary_empty_title),
                        message = stringResource(R.string.summary_empty_message),
                    )
                }
            } else {
                if (state.summary.perJob.isNotEmpty()) {
                    item(key = "by-job-header") { SectionHeader(stringResource(R.string.by_job)) }
                    val jobs = state.summary.perJob
                    itemsIndexed(jobs, key = { _, it -> "job-${it.job.id}" }) { index, jobSummary ->
                        JobSummaryItem(
                            summary = jobSummary,
                            totalEarnings = state.summary.totalEarnings[jobSummary.currency],
                            index = index,
                            count = jobs.size,
                            modifier = Modifier.animateItem().padding(SegmentedListDefaults.ItemPadding),
                        )
                    }
                }
                if (range.period == SummaryPeriod.MONTH) {
                    // Bonuses for the whole month have no day: this is where they are seen, edited and deleted.
                    item(key = "month-bonuses-header") { SectionHeader(stringResource(R.string.month_bonuses_title)) }
                    val monthBonuses = state.monthBonuses
                    if (monthBonuses.isEmpty()) {
                        item(key = "month-bonuses-none") {
                            Text(
                                stringResource(R.string.month_bonuses_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        itemsIndexed(monthBonuses, key = { _, it -> "month-bonus-${it.bonus.id}" }) { index, item ->
                            BonusCard(
                                item = item,
                                onClick = { onEditBonus(item.bonus.id) },
                                index = index,
                                count = monthBonuses.size,
                                modifier = Modifier.animateItem().padding(SegmentedListDefaults.ItemPadding),
                            )
                        }
                    }
                    item(key = "month-bonuses-add") {
                        FilledTonalButton(
                            onClick = { onAddMonthBonus(YearMonth.from(range.from)) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(SUMMARY_ADD_MONTH_BONUS_TAG),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_month_bonus))
                        }
                    }
                }
                if (state.receivedPay.isNotEmpty()) {
                    // The salary that actually arrived, next to what the app calculates (net). A check, never an earning.
                    item(key = "received-pay-header") { SectionHeader(stringResource(R.string.received_pay_title)) }
                    item(key = "received-pay-caption") {
                        Text(
                            stringResource(R.string.received_pay_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    val rows = state.receivedPay
                    itemsIndexed(rows, key = { _, it -> "received-${it.job.id}" }) { index, row ->
                        ReceivedPayItem(
                            row = row,
                            index = index,
                            count = rows.size,
                            onClick = {
                                sheetJobId = row.job.id
                                sheetMonthKey = Payout.keyOf(row.month)
                            },
                            modifier = Modifier.animateItem().padding(SegmentedListDefaults.ItemPadding),
                        )
                    }
                }
                if (state.receivedPayYear.isNotEmpty()) {
                    item(key = "received-pay-year-header") { SectionHeader(stringResource(R.string.received_pay_title)) }
                    item(key = "received-pay-year-caption") {
                        Text(
                            stringResource(R.string.received_pay_year_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    val lines = state.receivedPayYear
                    itemsIndexed(lines, key = { _, it -> "received-year-${it.job.id}" }) { index, line ->
                        ReceivedPayYearItem(line, index, lines.size, Modifier.animateItem().padding(SegmentedListDefaults.ItemPadding))
                    }
                }
                if (state.breakdown.isNotEmpty()) {
                    item(key = "breakdown-header") {
                        SectionHeader(stringResource(if (range.period == SummaryPeriod.WEEK) R.string.by_day else R.string.by_month))
                    }
                    val entries = state.breakdown
                    // Bars compare earnings; amounts in different currencies can't share one scale, so
                    // with several currencies in the period the bars compare hours instead.
                    val currencies = entries.flatMap { it.earnings.currencies }.toSet()
                    val barOf: (BreakdownEntry) -> Long =
                        if (currencies.size <= 1) { e -> e.earnings.byCurrency.values.sum() } else { e -> e.paidMinutes }
                    val max = entries.maxOf(barOf).coerceAtLeast(1)
                    // Keyed by position: paging keeps the rows in place (only values and bars change),
                    // so old and new rows never fade over each other.
                    itemsIndexed(entries, key = { index, _ -> "breakdown-$index" }) { index, entry ->
                        BreakdownItem(
                            period = range.period,
                            entry = entry,
                            bar = barOf(entry),
                            max = max,
                            index = index,
                            count = entries.size,
                            modifier = Modifier.animateItem().padding(SegmentedListDefaults.ItemPadding),
                        )
                    }
                }
            }
        }
    }

    if (sheetJobId != 0L) {
        val month = Payout.monthFromKey(sheetMonthKey)
        val row = state.receivedPay.firstOrNull { it.job.id == sheetJobId && it.month == month }
        if (row == null || month == null) {
            // Nothing left to list (the month changed, or its last payment of a job without entries was deleted).
            LaunchedEffect(Unit) { sheetJobId = 0L }
        } else {
            ReceivedPaySheet(job = row.job, month = month, row = row, editor = payoutEditor, onDismiss = { sheetJobId = 0L })
        }
    }
}

/** M3 Expressive connected button group: Week | Month | Year. */
@Composable
private fun PeriodSelector(
    selected: SummaryPeriod,
    onSelect: (SummaryPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceButtonGroup(
        options = SummaryPeriod.entries,
        selected = selected,
        onSelect = onSelect,
        label = { period ->
            stringResource(
                when (period) {
                    SummaryPeriod.WEEK -> R.string.period_week
                    SummaryPeriod.MONTH -> R.string.period_month
                    SummaryPeriod.YEAR -> R.string.period_year
                },
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun periodLabel(range: PeriodRange): String {
    val locale = currentLocale()
    return when (range.period) {
        SummaryPeriod.WEEK -> Formatters.dateRange(range.from, range.to, locale)
        SummaryPeriod.MONTH -> Formatters.monthYear(YearMonth.from(range.from), locale)
        SummaryPeriod.YEAR -> range.from.year.toString()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TotalsCard(period: SummaryPeriod, summary: MonthSummary, defaultCurrency: String, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val onColor = MaterialTheme.colorScheme.onPrimaryContainer
    // An average hourly rate only where there was hourly-paid time (a salaried job has none).
    val rated = summary.perJob.filter { it.hourlyPaidMinutes > 0 }.map { it.currency }.distinct()
    val currencies = rated.ifEmpty { listOf(summary.currencies.firstOrNull() ?: defaultCurrency) }
    val averageText = currencies.joinToString(" · ") { Formatters.money(summary.averageRate(it), it, locale) }
    val title = when (period) {
        SummaryPeriod.WEEK -> R.string.earnings_week
        SummaryPeriod.MONTH -> R.string.earnings_month
        SummaryPeriod.YEAR -> R.string.earnings_year
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = onColor),
    ) {
        Column(Modifier.padding(24.dp)) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                label = "totalsTitle",
            ) { res ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = onColor.copy(alpha = 0.8f))
                    // Gross or net, whenever jobs have deductions.
                    EarningsModeTag(Modifier.padding(start = 8.dp), color = onColor)
                }
            }
            RollingAmounts(
                amounts = summary.totalEarnings,
                fallbackCurrency = defaultCurrency,
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = onColor,
            )
            val bonuses = summary.bonuses.byCurrency.filterValues { it > 0 }
            if (bonuses.isNotEmpty()) {
                Text(
                    stringResource(R.string.including_bonuses, Formatters.amounts(Amounts(bonuses), locale)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RollingStat(
                    label = stringResource(R.string.hours),
                    text = Formatters.decimalHours(summary.totalPaidMinutes, locale),
                    value = summary.totalPaidMinutes,
                    color = onColor,
                    modifier = Modifier.weight(1f),
                )
                RollingStat(
                    label = stringResource(R.string.shifts),
                    text = summary.shiftCount.toString(),
                    value = summary.shiftCount.toLong(),
                    color = onColor,
                    modifier = Modifier.weight(1f),
                )
                RollingStat(
                    label = stringResource(R.string.average_rate),
                    text = averageText,
                    value = summary.averageRate(currencies.first()),
                    color = onColor,
                    modifier = Modifier.weight(1.3f),
                )
            }
        }
    }
}

/** One job of the "By job" group: earnings, hours/shifts/bonuses and its share of the total. */
@Composable
private fun JobSummaryItem(summary: JobSummary, totalEarnings: Long, index: Int, count: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val share = if (totalEarnings > 0) summary.earningsGrosze.toFloat() / totalEarnings else 0f
    val animatedShare by animateFloatAsState(share, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "share")
    SegmentedItem(index = index, count = count, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobAvatar(summary.job)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(summary.job.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val details = buildList {
                        if (summary.shiftCount > 0) {
                            add(hoursLabel(summary.paidMinutes))
                            add(shiftsCountLabel(summary.shiftCount))
                        }
                        if (summary.salaryMinor > 0) {
                            add(stringResource(R.string.salary_value, Formatters.money(summary.salaryMinor, summary.currency, locale)))
                        }
                        if (summary.bonusGrosze > 0) {
                            add(stringResource(R.string.bonuses_value, Formatters.money(summary.bonusGrosze, summary.currency, locale)))
                        }
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Formatters.money(summary.earningsGrosze, summary.currency, locale), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            val percent = (share * 100).toInt()
            val shareDescription = stringResource(R.string.share_of_earnings, percent)
            LinearProgressIndicator(
                // The spatial spring may overshoot a little; the bar itself must stay within 0..1.
                progress = { animatedShare.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics { contentDescription = shareDescription },
                color = summary.job.composeColor(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/** One day (week) or month (year) of the breakdown group, with a bar relative to the best entry. */
@Composable
private fun BreakdownItem(
    period: SummaryPeriod,
    entry: BreakdownEntry,
    bar: Long,
    max: Long,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val today = LocalDate.now()
    val isCurrent = when (period) {
        SummaryPeriod.WEEK -> entry.date == today
        else -> YearMonth.from(entry.date) == YearMonth.from(today)
    }
    val label = when (period) {
        SummaryPeriod.WEEK -> Formatters.shortDate(entry.date, locale)
        else -> Formatters.monthName(YearMonth.from(entry.date), locale)
    }
    val empty = entry.isEmpty
    val valueText = Formatters.amounts(entry.earnings, locale)
    val hoursText = hoursLabel(entry.paidMinutes)
    SegmentedItem(
        index = index,
        count = count,
        // Today / this month: a slightly lifted segment and a primary label.
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $valueText, $hoursText" },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = if (isCurrent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (entry.shiftCount > 0) {
                    Text(
                        hoursText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (empty) "—" else valueText,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!empty) {
                Spacer(Modifier.height(8.dp))
                val progress by animateFloatAsState(
                    targetValue = bar.toFloat() / max,
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "breakdown",
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Label + value whose digits roll when the value changes. */
@Composable
private fun RollingStat(label: String, text: String, value: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.75f))
        RollingText(text = text, value = value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}
