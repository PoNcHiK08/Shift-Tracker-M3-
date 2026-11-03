package com.vkurylo.shifttracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.vkurylo.shifttracker.ui.theme.pressScale

/*
 * M3 Expressive building blocks shared by the screens.
 *
 *  - Segmented lists (Android 16 style): the items of a group sit 2 dp apart; the group's outer
 *    corners are large, the corners between items small. Pressing an item rounds all of its corners
 *    and squeezes it slightly on the bouncy spatial spring; releasing springs it back.
 *  - ChoiceButtonGroup: single-choice M3 Expressive connected button group (ToggleButtons that morph
 *    their shape on press and on selection), the same component as Week / Month / Year.
 *
 * Every radius derived from a spring is clamped at zero: a bouncy spring can overshoot below its
 * target, and a negative corner radius makes the shape throw while drawing.
 */

object SegmentedListDefaults {
    /** Corners on the outside of a group. Also the corner of cards that expand into editors. */
    val OuterCorner: Dp = 24.dp

    /** Corners between two items of the same group. */
    val InnerCorner: Dp = 6.dp

    /** Space between the items of a group. */
    val Gap: Dp = 2.dp

    /** Padding of one item in a screen-wide list: screen margin + half the gap above and below. */
    val ItemPadding = PaddingValues(horizontal = 16.dp, vertical = Gap / 2)

    /** Scale of a pressed item. */
    const val PressedScale = 0.98f
}

/**
 * Shape of item [index] of [count] in a segmented group. [pressed] (0..1, may overshoot) moves the
 * inner corners towards the outer radius.
 */
fun segmentShape(index: Int, count: Int, pressed: Float = 0f): Shape {
    val outer = SegmentedListDefaults.OuterCorner
    val inner = lerp(SegmentedListDefaults.InnerCorner, outer, pressed).coerceAtLeast(0.dp)
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** [segmentShape] whose corners spring to fully rounded while [pressed]. */
@Composable
fun animateSegmentShape(index: Int, count: Int, pressed: Boolean): Shape {
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "segmentPress",
    )
    return segmentShape(index, count, progress)
}

/**
 * One item of a segmented list. With [onClick] it is pressable (corner morph + squeeze + ripple);
 * with [checked] as well it behaves as a switch row. Without [onClick] it is static.
 *
 * [content] receives the modifier with the click/toggle behaviour and must apply it to its root, so
 * the action lands on the same semantics node as the text (a Material ListItem merges its own
 * children, so a clickable wrapped around it would be announced without a label). The ripple still
 * follows the item's shape: the item clips its content.
 */
@Composable
fun SegmentedItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    role: Role? = null,
    content: @Composable (interaction: Modifier) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressable = onClick != null && enabled
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = animateSegmentShape(index, count, pressed && pressable)
    val interaction = when {
        onClick == null -> Modifier
        checked != null -> Modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role ?: Role.Switch,
            onValueChange = { onClick() },
        )
        else -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .pressScale(interactionSource, SegmentedListDefaults.PressedScale, pressable)
            .clip(shape)
            .background(color),
    ) {
        content(interaction)
    }
}

/**
 * Single-choice M3 Expressive connected button group: the selected button becomes a full pill,
 * a pressed one squeezes its corners. Labels shrink to fit instead of wrapping, so the group always
 * stays on one line.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ChoiceButtonGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    weight: (T) -> Float = { 1f },
) {
    val labelStyle = MaterialTheme.typography.labelLarge
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = contentPadding,
                modifier = Modifier
                    .weight(weight(option))
                    .height(height)
                    .semantics { role = Role.RadioButton },
            ) {
                Text(
                    text = label(option),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
                )
            }
        }
    }
}
