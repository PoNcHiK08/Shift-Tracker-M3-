package com.vkurylo.shifttracker.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R

const val INFO_DIALOG_TAG = "info-dialog"

/**
 * An ⓘ that opens [text] in a dialog titled [title]: where a long explanation would otherwise sit on screen under a
 * heading or a field. The icon keeps the 48 dp touch target and is named for TalkBack ("More about …").
 */
@Composable
fun InfoButton(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.more_info, title), tint = tint, modifier = Modifier.size(iconSize))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.testTag(INFO_DIALOG_TAG),
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
