package com.vkurylo.shifttracker.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.vkurylo.shifttracker.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/** Read-only text field that opens a picker; keeps native field visuals and accessibility. */
@Composable
fun PickerField(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Release) currentOnClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        leadingIcon = icon,
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    title: String,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val haptics = rememberPickerHaptics()
    val touch = remember { DialTouch() }
    TimeTickHaptics(state, haptics, touch)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = {
                haptics.confirm()
                onConfirm(LocalTime.of(state.hour, state.minute))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        Box(Modifier.trackDialTouch(touch)) { TimePicker(state = state) }
    }
}

/**
 * Haptic feedback for pickers. The default plays the platform's clock tick; tests provide their own
 * through [LocalPickerHaptics] to count the ticks.
 */
interface PickerHaptics {
    /** The selected value changed under the finger (one notch of the dial). */
    fun tick()

    /** The choice was confirmed. */
    fun confirm()
}

val LocalPickerHaptics = staticCompositionLocalOf<PickerHaptics?> { null }

@Composable
fun rememberPickerHaptics(): PickerHaptics {
    LocalPickerHaptics.current?.let { return it }
    val view = LocalView.current
    return remember(view) { ViewPickerHaptics(view) }
}

/**
 * CLOCK_TICK is the platform's feedback for exactly this (AOSP's own dial time picker uses it) and,
 * like all View haptics, it follows the system "touch feedback" setting.
 */
private class ViewPickerHaptics(private val view: View) : PickerHaptics {
    override fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
}

/**
 * Whether the user's finger is on the dial, or was lifted a moment ago: tapping a number selects it
 * on release, and the hand settles right after a drag. Changes outside that window are programmatic
 * and don't tick. Counted in frames, not wall-clock time.
 */
@Stable
class DialTouch {
    internal var pressed = false
    internal var justReleased = false
    internal var releaseJob: Job? = null

    val isUserChange: Boolean get() = pressed || justReleased
}

/** Observes the dial's pointer events (without consuming them) for [DialTouch]. */
fun Modifier.trackDialTouch(touch: DialTouch): Modifier = pointerInput(touch) {
    coroutineScope {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed }) {
                    touch.releaseJob?.cancel()
                    touch.pressed = true
                    touch.justReleased = false
                } else if (touch.pressed) {
                    touch.pressed = false
                    touch.justReleased = true
                    touch.releaseJob = launch {
                        repeat(RELEASE_GRACE_FRAMES) { withFrameNanos { } }
                        touch.justReleased = false
                    }
                }
            }
        }
    }
}

private const val RELEASE_GRACE_FRAMES = 2

/** How many times the dial's tick collector has started; for tests (one per opened dialog). */
@VisibleForTesting
internal val timeTickCollectorStarts = AtomicInteger()

/**
 * One tick per hour/minute change of [state] made by the user's finger ([touch]). The first value
 * (opening the dialog) and programmatic changes don't tick. [minTickIntervalMs] can thin out ticks
 * during very fast drags; 0 = every change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeTickHaptics(state: TimePickerState, haptics: PickerHaptics, touch: DialTouch, minTickIntervalMs: Long = 0L) {
    val currentHaptics by rememberUpdatedState(haptics)
    // Keyed on the state object only: recompositions (new title, new lambdas) must not restart it.
    LaunchedEffect(state, touch) {
        timeTickCollectorStarts.incrementAndGet()
        var lastTick = Long.MIN_VALUE / 2
        snapshotFlow { state.hour * 60 + state.minute }
            .drop(1)
            .collect {
                if (!touch.isUserChange) return@collect
                val now = SystemClock.uptimeMillis()
                if (minTickIntervalMs <= 0L || now - lastTick >= minTickIntervalMs) {
                    lastTick = now
                    currentHaptics.tick()
                }
            }
    }
}
