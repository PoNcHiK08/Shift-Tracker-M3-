package com.vkurylo.shifttracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Job
import kotlin.math.sign

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

/**
 * "‹ label ›" switcher for a period. When [period] changes, the label slides in from the side of
 * travel ([direction] > 0: forward, < 0: backward) or cross-fades ([direction] == 0, e.g. when the
 * kind of period changes). The arrows are M3 Expressive icon buttons that morph their shape on press.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> PeriodSwitcher(
    period: T,
    label: @Composable (T) -> String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    direction: (from: T, to: T) -> Int = { _, _ -> 0 },
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = onPrevious, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_period))
        }
        AnimatedContent(
            targetState = period,
            transitionSpec = {
                val d = direction(initialState, targetState).sign
                if (d == 0) {
                    fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec)
                } else {
                    (slideInHorizontally(spatial) { w -> d * w / 3 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(spatial) { w -> -d * w / 3 } + fadeOut(fadeOutSpec))
                }
            },
            contentAlignment = Alignment.Center,
            // The label slides under the arrows, not over them.
            modifier = Modifier.weight(1f).clipToBounds(),
            label = "periodLabel",
        ) { p ->
            Text(
                text = label(p),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        }
        FilledTonalIconButton(onClick = onNext, shapes = IconButtonDefaults.shapes()) {
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
