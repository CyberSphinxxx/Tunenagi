package com.tunenagi.player.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing

const val EMPTY_STATE_TAG = "empty-state"

/**
 * The shared empty state: what is missing, why, and — when an action genuinely exists —
 * how to move forward.
 *
 * [actionLabel] and [onAction] are paired on purpose. `SPEC/UX_DESIGN.md` forbids
 * ornamental buttons, so a state that has nothing to do simply renders no button instead
 * of a disabled one.
 */
@Composable
fun TunenagiEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(TunenagiSpacing.XlDp.dp)
            .testTag(EMPTY_STATE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TunenagiSpacing.SmDp.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(TunenagiSpacing.LgDp.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = TunenagiSpacing.MinTouchTargetDp.dp)
            ) {
                Text(text = actionLabel)
            }
        }
    }
}
