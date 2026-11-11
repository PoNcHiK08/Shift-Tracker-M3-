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
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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

const val MONTH_BONUSES_ROW_TAG = "month-bonuses-row"
const val MONTH_BONUSES_SHEET_TAG = "month-bonuses-sheet"
const val MONTH_BONUSES_ADD_TAG = "month-bonuses-add"

/** What the month's bonuses for the whole month add up to, per currency, gross or net as the screens show. */
@Composable
internal fun monthBonusesTotal(items: List<BonusWithJob>): Amounts {
    val mode = LocalEarningsDisplay.current.mode
    return Amounts.sum(items, { it.job.currency }, { ShiftCalculator.bonusAmount(it, mode) })
}

/**
 * "Month bonuses · 2  +500,00 zł": the month's bonuses for the whole month, which have no day to show on. Opens the
 * list ([MonthBonusesSheet]). Shown only when the month has any.
 */
@Composable
internal fun MonthBonusesRow(items: List<BonusWithJob>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().testTag(MONTH_BONUSES_ROW_TAG),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Redeem, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.month_bonuses_row, items.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("+" + Formatters.amounts(monthBonusesTotal(items), locale), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** The month's bonuses for the whole month: tap one to edit or delete it in the bonus editor, or add another. */
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
                stringResource(R.string.month_bonuses_sheet_title, Formatters.monthYear(month, locale)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { item -> MonthBonusListRow(item, onClick = { onEdit(item.bonus.id) }) }
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
                if (!item.bonus.note.isNullOrBlank()) {
                    Text(
                        item.bonus.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("+" + Formatters.money(ShiftCalculator.bonusAmount(item, mode), item.job.currency, locale), style = MaterialTheme.typography.titleMedium)
        }
    }
}
