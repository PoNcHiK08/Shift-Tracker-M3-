package com.vkurylo.shifttracker.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.LocalEarningsDisplay
import com.vkurylo.shifttracker.ui.util.currentLocale
import java.time.YearMonth

const val MONTH_BONUSES_LINE_TAG = "month-bonuses-line"
const val MONTH_BONUSES_SHEET_TAG = "month-bonuses-sheet"
const val MONTH_BONUSES_ADD_TAG = "month-bonuses-add"

/** What bonuses add up to, per currency, gross or net as the screens show. */
@Composable
internal fun monthBonusesTotal(items: List<BonusWithJob>): Amounts {
    val mode = LocalEarningsDisplay.current.mode
    return Amounts.sum(items, { it.job.currency }, { ShiftCalculator.bonusAmount(it, mode) })
}

/**
 * Every bonus of [month] (for a day or for the whole month), as the "Earned this month" tile counts and lists them:
 * the whole-month ones first, then by day.
 */
internal fun bonusesOfMonth(month: YearMonth, monthBonuses: List<BonusWithJob>, bonusesByDate: Map<LocalDate, List<BonusWithJob>>): List<BonusWithJob> =
    monthBonuses + bonusesByDate.filterKeys { YearMonth.from(it) == month }.toSortedMap().values.flatten()

/**
 * The bonus line of the "Earned this month" tile, always there so the tile is as tall every month: "+300,00 zł bonus",
 * "+500,00 zł · 3 bonuses", or "No bonuses" (muted). One line of fixed height: a long amount (or several currencies)
 * shrinks its text to fit instead of wrapping.
 */
@Composable
internal fun MonthBonusesLine(items: List<BonusWithJob>, color: Color, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val style = MaterialTheme.typography.labelMedium
    val text = when (items.size) {
        0 -> stringResource(R.string.bonuses_line_none)
        1 -> stringResource(R.string.bonuses_line_one, "+" + Formatters.amounts(monthBonusesTotal(items), locale))
        else -> pluralStringResource(R.plurals.bonuses_line_many, items.size, "+" + Formatters.amounts(monthBonusesTotal(items), locale), items.size)
    }
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(modifier.fillMaxWidth().height(lineHeight).testTag(MONTH_BONUSES_LINE_TAG), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = style,
            // "No bonuses" is quieter than an amount.
            color = if (items.isEmpty()) color.copy(alpha = 0.72f) else color,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = color.copy(alpha = 0.72f),
            modifier = Modifier.size(lineHeight),
        )
    }
}

/**
 * Every bonus of the month, for a day or for the whole month: tap one to edit or delete it in the bonus editor, or
 * add a bonus for the month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthBonusesSheet(
    month: YearMonth,
    items: List<BonusWithJob>,
    onDismiss: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val locale = currentLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag(MONTH_BONUSES_SHEET_TAG)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.bonuses_sheet_title, Formatters.monthYear(month, locale)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.bonuses_sheet_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { item -> MonthBonusListRow(item, onClick = { onEdit(item.bonus.id) }) }
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag(MONTH_BONUSES_ADD_TAG)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_month_bonus))
            }
        }
    }
}

@Composable
private fun MonthBonusListRow(item: BonusWithJob, onClick: () -> Unit) {
    val locale = currentLocale()
    val colors = MaterialTheme.colorScheme
    val mode = LocalEarningsDisplay.current.mode
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bonus_for_job, item.job.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // When it counts: its day, or the whole month.
                val whenText = if (item.bonus.wholeMonth) stringResource(R.string.bonus_for_whole_month) else Formatters.shortDate(item.bonus.date, locale)
                val note = item.bonus.note?.takeIf { it.isNotBlank() }
                Text(
                    listOfNotNull(whenText, note).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("+" + Formatters.money(ShiftCalculator.bonusAmount(item, mode), item.job.currency, locale), style = MaterialTheme.typography.titleMedium)
        }
    }
}
