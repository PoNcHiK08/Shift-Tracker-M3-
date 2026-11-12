package com.vkurylo.shifttracker.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.ui.util.LocalEarningsDisplay
import com.vkurylo.shifttracker.domain.model.paysHourly
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.navigation.SharedKeys
import com.vkurylo.shifttracker.ui.navigation.sharedContainer
import com.vkurylo.shifttracker.ui.theme.pressScale
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel

/**
 * The card surface: one segment of the day's group (several entries of a day sit 2 dp apart, like an
 * Android 16 list) and one end of a container transform. Pressing rounds its inner corners and
 * squeezes it; the ripple stays inside the shape.
 */
@Composable
private fun EntryContainer(
    key: String,
    index: Int,
    count: Int,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = animateSegmentShape(index, count, pressed)
    Box(
        modifier
            .fillMaxWidth()
            .sharedContainer(key, shape)
            .pressScale(interactionSource, pressedScale = 0.97f)
            .clip(shape)
            .background(color)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

/**
 * A shift as a card. It is one end of a container transform: tapping it grows the card into the
 * full-screen editor (and the editor shrinks back into it on close / predictive back).
 */
@Composable
fun ShiftCard(
    item: ShiftWithJob,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
) {
    val locale = currentLocale()
    val mode = LocalEarningsDisplay.current.mode
    val shift = item.shift
    val details = buildList {
        add("${Formatters.time(shift.start)}–${Formatters.time(shift.end)}")
        if (shift.unpaidBreakMinutes > 0) add(stringResource(R.string.break_short, shift.unpaidBreakMinutes))
    }.joinToString(" · ")

    EntryContainer(
        key = SharedKeys.shift(shift.id),
        index = index,
        count = count,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = onClick,
        modifier = modifier,
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JobAvatar(item.job)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.job.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shift.isOvernight) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.NightsStay,
                        contentDescription = stringResource(R.string.overnight_shift),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (!shift.note.isNullOrBlank()) {
                Text(
                    shift.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // A salaried job's shift counts for hours only; its pay is the monthly salary.
            Text(
                if (item.job.paysHourly(shift.date)) Formatters.money(ShiftCalculator.shiftEarnings(item, mode), item.job.currency, locale)
                else stringResource(R.string.fixed_salary),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                hoursLabel(shift.paidMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}

/** A bonus as a card; like [ShiftCard] it expands into its editor. */
@Composable
fun BonusCard(
    item: BonusWithJob,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
) {
    val locale = currentLocale()
    val bonus = item.bonus
    EntryContainer(
        key = SharedKeys.bonus(bonus.id),
        index = index,
        count = count,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        onClick = onClick,
        modifier = modifier,
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Redeem, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.bonus_for_job, item.job.name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bonus.wholeMonth) {
                Text(
                    stringResource(R.string.bonus_for_whole_month),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
            if (!bonus.note.isNullOrBlank()) {
                Text(
                    bonus.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "+" + Formatters.money(ShiftCalculator.bonusAmount(item, LocalEarningsDisplay.current.mode), item.job.currency, locale),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    }
}
