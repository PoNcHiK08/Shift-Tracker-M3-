package com.vkurylo.shifttracker.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.TextLimits
import java.text.NumberFormat

/**
 * Supporting text for a name or note field limited by [TextLimits]: nothing while the text is short, its
 * length near the limit, and an error over it (only a text saved before the limit existed can be: the field
 * itself stops at the limit).
 */
fun textLengthSupport(length: Int): (@Composable () -> Unit)? = when {
    length > TextLimits.MAX_LENGTH -> {
        { Text(stringResource(R.string.text_too_long, count(length), count(TextLimits.MAX_LENGTH))) }
    }
    length >= TextLimits.SHOW_COUNT_FROM -> {
        { Text(stringResource(R.string.text_length, count(length), count(TextLimits.MAX_LENGTH))) }
    }
    else -> null
}

/** Over the limit: the field shows an error and the editor won't save. */
fun isTooLong(text: String): Boolean = text.length > TextLimits.MAX_LENGTH

@Composable
private fun count(value: Int): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)
