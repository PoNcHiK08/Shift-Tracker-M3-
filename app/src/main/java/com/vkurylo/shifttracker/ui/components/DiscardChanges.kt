package com.vkurylo.shifttracker.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vkurylo.shifttracker.R
import kotlinx.coroutines.launch

const val DISCARD_DIALOG = "discard-changes-dialog"
const val DISCARD_CONFIRM = "discard-changes-confirm"
const val DISCARD_KEEP = "discard-changes-keep"

/** "Discard changes?": [onDiscard] closes without saving, [onKeepEditing] (or dismissing the dialog) stays. */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        modifier = Modifier.testTag(DISCARD_DIALOG),
        title = { Text(stringResource(R.string.discard_changes_title)) },
        text = { Text(stringResource(R.string.discard_changes_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag(DISCARD_CONFIRM)) { Text(stringResource(R.string.discard_changes_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing, modifier = Modifier.testTag(DISCARD_KEEP)) { Text(stringResource(R.string.discard_changes_keep)) }
        },
    )
}

/**
 * Closing a full-screen editor: straight away without unsaved changes, after "Discard changes?" with them. Returns
 * what the Close button calls; system back (gesture or button) asks the same while [hasChanges]. Without changes
 * back is left to the navigation, so its predictive-back animation is unchanged.
 */
@Composable
fun rememberDiscardGuard(hasChanges: Boolean, onClose: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = hasChanges) { asking = true }
    if (asking) {
        DiscardChangesDialog(
            onDiscard = {
                asking = false
                onClose()
            },
            onKeepEditing = { asking = false },
        )
    }
    return { if (hasChanges) asking = true else onClose() }
}

/**
 * The state of a bottom sheet holding a form. While [hasChanges], swiping it down, tapping outside it or system back
 * leave it open and ask "Discard changes?"; "Discard" calls [onDiscard] (drop the changes), hides the sheet and then
 * calls [onDismissed]. Without changes the sheet closes as usual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDiscardGuardedSheetState(hasChanges: Boolean, onDiscard: () -> Unit, onDismissed: () -> Unit): SheetState {
    var asking by rememberSaveable { mutableStateOf(false) }
    val dirty by rememberUpdatedState(hasChanges)
    // Set once "Discard" is chosen, so the sheet's own hide isn't stopped by changes that are being dropped.
    var discarding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && dirty && !discarding) {
                asking = true
                false
            } else {
                true
            }
        },
    )
    if (asking) {
        DiscardChangesDialog(
            onDiscard = {
                asking = false
                discarding = true
                onDiscard()
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissed() }
            },
            onKeepEditing = { asking = false },
        )
    }
    return sheetState
}
