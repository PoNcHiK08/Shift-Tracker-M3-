package com.vkurylo.shifttracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale

/** Money that rolls like a mechanical counter ("drum") whenever the amount changes. */
@Composable
fun RollingMoney(
    grosze: Long,
    currency: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    prefix: String = "",
) {
    RollingText(
        text = prefix + Formatters.money(grosze, currency, currentLocale()),
        value = grosze,
        style = style,
        modifier = modifier,
        color = color,
    )
}

/**
 * Totals per currency, one rolling line each, the largest first. With a single currency this is
 * exactly one [RollingMoney]; with none, zero in [fallbackCurrency].
 */
@Composable
fun RollingAmounts(
    amounts: Amounts,
    fallbackCurrency: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val lines = amounts.sortedByValue().ifEmpty { listOf(fallbackCurrency to 0L) }
    if (lines.size == 1) {
        RollingMoney(grosze = lines[0].second, currency = lines[0].first, style = style, modifier = modifier, color = color)
    } else {
        Column(modifier, horizontalAlignment = horizontalAlignment) {
            lines.forEach { (code, value) ->
                key(code) { RollingMoney(grosze = value, currency = code, style = style, color = color) }
            }
        }
    }
}

/**
 * Text whose digits roll vertically when [value] changes: up when it grows, down when it shrinks,
 * every digit in the same direction like an odometer. Digits use tabular figures so the width
 * doesn't jitter. Screen readers and tests see the whole [text] as one value.
 */
@Composable
fun RollingText(
    text: String,
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    // Direction of the change that produced this value; computed once per value (no state writes).
    val last = remember { LongArray(1) { value } }
    val direction = remember(value) {
        val d = if (value >= last[0]) 1 else -1
        last[0] = value
        d
    }
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val digitStyle = style.copy(color = textColor, fontFeatureSettings = "tnum")
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        // Key slots from the right so units stay in place when the number gets longer.
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            (slideInVertically(spatial) { h -> direction * h } + fadeIn(effects)) togetherWith
                                (slideOutVertically(spatial) { h -> -direction * h } + fadeOut(effects)) using
                                SizeTransform(clip = true) { _, _ -> sizeSpec }
                        } else {
                            // A slot switching between a digit and a separator (e.g. hours "88,5" -> "35,25"):
                            // the old glyph goes at once and the new one fades in. Without a fade both glyphs
                            // showed on top of each other for a few frames; a plain cross-fade still blended them.
                            fadeIn(effects) togetherWith fadeOut(snap()) using
                                SizeTransform(clip = true) { _, _ -> sizeSpec }
                        }
                    },
                    label = "rollingDigit",
                ) { c ->
                    Text(c.toString(), style = digitStyle)
                }
            }
        }
    }
}

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this != Color.Unspecified) this else block()
