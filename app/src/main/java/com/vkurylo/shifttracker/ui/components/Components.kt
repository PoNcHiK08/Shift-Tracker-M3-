package com.vkurylo.shifttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel

fun Job.composeColor(): Color = Color(color)

/** Readable content color on top of a job color. */
fun Color.onColor(): Color = if (luminance() > 0.5f) Color.Black.copy(alpha = 0.87f) else Color.White

@Composable
fun JobColorDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Circular avatar with the job's first letter, tinted with the job color. */
@Composable
fun JobAvatar(job: Job, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = job.composeColor()
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = job.name.trim().take(1).uppercase(),
            color = color.onColor(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun ShiftListItem(
    item: ShiftWithJob,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    containerColor: Color = ListItemDefaults.colors().containerColor,
) {
    val locale = currentLocale()
    val shift = item.shift
    val timeRange = "${Formatters.time(shift.start)}–${Formatters.time(shift.end)}"
    val details = buildList {
        if (showDate) add(Formatters.shortDate(shift.date, locale))
        add(timeRange)
        if (shift.unpaidBreakMinutes > 0) add(stringResource(R.string.break_short, shift.unpaidBreakMinutes))
    }.joinToString(" · ")

    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = { JobAvatar(item.job) },
        headlineContent = { Text(item.job.name, maxLines = 1) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(details)
                    if (shift.isOvernight) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Filled.NightsStay,
                            contentDescription = stringResource(R.string.overnight_shift),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (!shift.note.isNullOrBlank()) {
                    Text(shift.note, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formatters.money(shift.earningsGrosze, locale),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(hoursLabel(shift.paidMinutes), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
fun PeriodSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_period))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        FilledTonalIconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_period))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** A labelled figure used in summary cards (e.g. "Hours / 42,5"). */
@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.75f))
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}
