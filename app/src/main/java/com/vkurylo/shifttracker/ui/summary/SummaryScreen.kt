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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobAvatar
import com.vkurylo.shifttracker.ui.components.MonthSwitcher
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.StatBlock
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel
import com.vkurylo.shifttracker.ui.util.shiftsCountLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(viewModel: SummaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item(key = "switcher") {
                MonthSwitcher(
                    month = state.month,
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "hero") {
                AnimatedContent(
                    targetState = state.month to state.summary,
                    transitionSpec = {
                        val forward = targetState.first > initialState.first
                        (slideInHorizontally { if (forward) it / 6 else -it / 6 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 6 else it / 6 } + fadeOut())
                    },
                    label = "summaryHero",
                ) { (_, summary) ->
                    TotalsCard(summary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            if (!state.loading && state.summary.shiftCount == 0) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.QueryStats,
                        title = stringResource(R.string.summary_empty_title),
                        message = stringResource(R.string.summary_empty_message),
                    )
                }
            } else if (state.summary.perJob.isNotEmpty()) {
                item(key = "by-job-header") { SectionHeader(stringResource(R.string.by_job)) }
                items(state.summary.perJob, key = { it.job.id }) { jobSummary ->
                    JobSummaryCard(
                        summary = jobSummary,
                        totalEarnings = state.summary.totalEarningsGrosze,
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(summary: MonthSummary, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val onColor = MaterialTheme.colorScheme.onPrimaryContainer
    val average = if (summary.totalPaidMinutes > 0) summary.totalEarningsGrosze * 60 / summary.totalPaidMinutes else 0
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = onColor),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.total_earnings), style = MaterialTheme.typography.labelLarge, color = onColor.copy(alpha = 0.8f))
            Text(
                Formatters.money(summary.totalEarningsGrosze, locale),
                style = MaterialTheme.typography.displaySmall,
                color = onColor,
            )
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
                    Text(
                        "${hoursLabel(summary.paidMinutes)} · ${shiftsCountLabel(summary.shiftCount)}",
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
