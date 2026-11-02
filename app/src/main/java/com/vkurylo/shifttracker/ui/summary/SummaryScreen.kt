package com.vkurylo.shifttracker.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobAvatar
import com.vkurylo.shifttracker.ui.components.PeriodSwitcher
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.StatBlock
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel
import com.vkurylo.shifttracker.ui.util.shiftsCountLabel
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(viewModel: SummaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
                    label = periodLabel(range),
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "hero") {
                AnimatedContent(
                    targetState = state,
                    contentKey = { it.range },
                    transitionSpec = {
                        if (targetState.range.period != initialState.range.period) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            val forward = targetState.range.from > initialState.range.from
                            (slideInHorizontally { if (forward) it / 6 else -it / 6 } + fadeIn()) togetherWith
                                (slideOutHorizontally { if (forward) -it / 6 else it / 6 } + fadeOut())
                        }
                    },
                    label = "summaryHero",
                ) { target ->
                    TotalsCard(target.range.period, target.summary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            if (!state.loading && state.summary.isEmpty) {
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
                    items(state.summary.perJob, key = { "job-${it.job.id}" }) { jobSummary ->
                        JobSummaryCard(
                            summary = jobSummary,
                            totalEarnings = state.summary.totalEarningsGrosze,
                            modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                if (state.breakdown.isNotEmpty()) {
                    item(key = "breakdown-header") {
                        SectionHeader(stringResource(if (range.period == SummaryPeriod.WEEK) R.string.by_day else R.string.by_month))
                    }
                    item(key = "breakdown") {
                        BreakdownCard(
                            period = range.period,
                            entries = state.breakdown,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** M3 Expressive connected button group: Week | Month | Year. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeriodSelector(
    selected: SummaryPeriod,
    onSelect: (SummaryPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        SummaryPeriod.WEEK to R.string.period_week,
        SummaryPeriod.MONTH to R.string.period_month,
        SummaryPeriod.YEAR to R.string.period_year,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (period, label) ->
            ToggleButton(
                checked = period == selected,
                onCheckedChange = { onSelect(period) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
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

@Composable
private fun TotalsCard(period: SummaryPeriod, summary: MonthSummary, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val onColor = MaterialTheme.colorScheme.onPrimaryContainer
    val average = summary.averageRateGrosze
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
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = onColor.copy(alpha = 0.8f))
            Text(
                Formatters.money(summary.totalEarningsGrosze, locale),
                style = MaterialTheme.typography.displaySmall,
                color = onColor,
            )
            if (summary.bonusGrosze > 0) {
                Text(
                    stringResource(R.string.including_bonuses, Formatters.money(summary.bonusGrosze, locale)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatBlock(stringResource(R.string.hours), Formatters.decimalHours(summary.totalPaidMinutes, locale), color = onColor, modifier = Modifier.weight(1f))
                StatBlock(stringResource(R.string.shifts), summary.shiftCount.toString(), color = onColor, modifier = Modifier.weight(1f))
                StatBlock(stringResource(R.string.average_rate), Formatters.money(average, locale), color = onColor, modifier = Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun JobSummaryCard(summary: JobSummary, totalEarnings: Long, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val share = if (totalEarnings > 0) summary.earningsGrosze.toFloat() / totalEarnings else 0f
    val animatedShare by animateFloatAsState(share, label = "share")
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
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
                        if (summary.bonusGrosze > 0) {
                            add(stringResource(R.string.bonuses_value, Formatters.money(summary.bonusGrosze, locale)))
                        }
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Formatters.money(summary.earningsGrosze, locale), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            val percent = (share * 100).toInt()
            val shareDescription = stringResource(R.string.share_of_earnings, percent)
            LinearProgressIndicator(
                progress = { animatedShare },
                modifier = Modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics { contentDescription = shareDescription },
                color = summary.job.composeColor(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/** Per-day (week) or per-month (year) earnings, each with a bar relative to the best entry. */
@Composable
private fun BreakdownCard(period: SummaryPeriod, entries: List<BreakdownEntry>, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val max = entries.maxOf { it.earningsGrosze }.coerceAtLeast(1)
    val today = LocalDate.now()
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(vertical = 8.dp)) {
            entries.forEach { entry ->
                val isCurrent = when (period) {
                    SummaryPeriod.WEEK -> entry.date == today
                    else -> YearMonth.from(entry.date) == YearMonth.from(today)
                }
                val label = when (period) {
                    SummaryPeriod.WEEK -> Formatters.shortDate(entry.date, locale)
                    else -> Formatters.monthName(YearMonth.from(entry.date), locale)
                }
                val empty = entry.isEmpty
                val valueText = Formatters.money(entry.earningsGrosze, locale)
                val hoursText = hoursLabel(entry.paidMinutes)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clearAndSetSemantics { contentDescription = "$label: $valueText, $hoursText" },
                ) {
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
                        Spacer(Modifier.height(6.dp))
                        val progress by animateFloatAsState(entry.earningsGrosze.toFloat() / max, label = "breakdown")
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
