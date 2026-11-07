package com.vkurylo.shifttracker.ui.util

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.EarningsMode

/**
 * Which earnings the app shows (gross, or net of each job's deductions) and whether to say so: with no
 * deductions anywhere gross and net are the same, so no tag clutters the screens.
 */
@Immutable
data class EarningsDisplay(val mode: EarningsMode = EarningsMode.GROSS, val showTag: Boolean = false)

val LocalEarningsDisplay = compositionLocalOf { EarningsDisplay() }

internal const val EARNINGS_TAG = "earnings-mode-tag"

/** "Gross" / "Net" for the mode in use, or null when there is nothing to tell apart. */
@Composable
fun earningsModeLabel(): String? {
    val display = LocalEarningsDisplay.current
    if (!display.showTag) return null
    return stringResource(if (display.mode == EarningsMode.NET) R.string.earnings_net else R.string.earnings_gross)
}

/** A small "Gross" / "Net" chip next to an earnings figure; nothing when there are no deductions. */
@Composable
fun EarningsModeTag(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val label = earningsModeLabel() ?: return
    Surface(
        modifier = modifier.testTag(EARNINGS_TAG),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
