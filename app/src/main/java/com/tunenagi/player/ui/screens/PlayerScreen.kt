package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.core.model.ProviderId
import com.tunenagi.player.R
import com.tunenagi.player.sourceinspector.Notice
import com.tunenagi.player.sourceinspector.SourceInspectorState
import com.tunenagi.player.sourceinspector.SourceRow
import com.tunenagi.player.sourceinspector.SourceStatus
import com.tunenagi.player.ui.playback.SourceInspectorUi
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val PLAYER_SCREEN_TAG = "player-screen"
const val PLAYER_CLOSE_TAG = "player-close"
const val PLAYER_SOURCE_SECTION_TAG = "source-inspector"
const val PLAYER_SOURCE_TITLE_TAG = "source-inspector-title"
const val PLAYER_SOURCE_FEEDBACK_TAG = "source-inspector-feedback"
const val PLAYER_SOURCE_NOTICE_TAG = "source-inspector-notice"
const val PLAYER_SOURCE_FAILURE_TAG = "source-inspector-failure"
const val PLAYER_SOURCE_NONE_TAG = "source-inspector-none"

/** Row-scoped tags: a track's sources share a screen, so the id is part of the tag. */
fun sourceRowTag(sourceId: String) = "source-row-$sourceId"

fun sourceUseTag(sourceId: String) = "source-use-$sourceId"

fun sourceUnlockTag(sourceId: String) = "source-unlock-$sourceId"

fun sourcePreferredTag(sourceId: String) = "source-preferred-$sourceId"

fun sourceLockedTag(sourceId: String) = "source-locked-$sourceId"

/**
 * The player host route — the source inspector lives here (TUN-0307).
 *
 * The route is a pure function of [inspector]: with nothing loaded it keeps the honest "nothing is
 * playing" state, and with a track under the player it shows every stored source with its identity,
 * its typed status and the format *the source itself* reported (null stays "not shown", never a
 * guess from an extension). The one source playback would actually use is called out when it is
 * unavailable, so the rows cannot silently disagree with what the player is doing.
 */
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    inspector: SourceInspectorUi = SourceInspectorUi.None
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(PLAYER_SCREEN_TAG)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TunenagiSpacing.SmDp.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(PLAYER_CLOSE_TAG)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_close)
                )
            }
            Text(text = stringResource(R.string.player_title))
        }
        when (val state = inspector.state) {
            SourceInspectorState.NoTrack -> TunenagiEmptyState(
                title = stringResource(R.string.player_empty_title),
                message = stringResource(R.string.player_empty_message),
                modifier = Modifier.weight(1f)
            )

            SourceInspectorState.Loading -> TunenagiEmptyState(
                title = stringResource(R.string.source_loading),
                message = stringResource(R.string.source_loading_message),
                modifier = Modifier.weight(1f)
            )

            SourceInspectorState.NotALibraryTrack -> TunenagiEmptyState(
                title = stringResource(R.string.source_not_library_track),
                message = stringResource(R.string.source_not_library_track_message),
                modifier = Modifier.weight(1f)
            )

            is SourceInspectorState.Loaded -> SourceInspectorSection(
                state = state,
                onChoose = inspector.onChoose,
                onUnlock = inspector.onUnlock,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SourceInspectorSection(
    state: SourceInspectorState.Loaded,
    onChoose: (String) -> Unit,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TunenagiSpacing.MdDp.dp, vertical = TunenagiSpacing.SmDp.dp)
            .testTag(PLAYER_SOURCE_SECTION_TAG)
    ) {
        Text(
            text = stringResource(R.string.source_inspector_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(PLAYER_SOURCE_TITLE_TAG)
        )
        state.unavailableFeedback?.let { feedback ->
            Text(
                text = stringResource(R.string.source_feedback, statusText(feedback.status)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = TunenagiSpacing.SmDp.dp)
                    .testTag(PLAYER_SOURCE_FEEDBACK_TAG)
            )
        }
        state.notice?.let { notice ->
            Text(
                text = when (notice) {
                    is Notice.Chosen -> stringResource(R.string.source_notice_chosen, providerName(notice.providerId))
                    is Notice.Unlocked ->
                        stringResource(R.string.source_notice_unlocked, providerName(notice.providerId))
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = TunenagiSpacing.SmDp.dp)
                    .testTag(PLAYER_SOURCE_NOTICE_TAG)
            )
        }
        state.failure?.let {
            Text(
                text = stringResource(R.string.source_action_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = TunenagiSpacing.SmDp.dp)
                    .testTag(PLAYER_SOURCE_FAILURE_TAG)
            )
        }
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.source_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = TunenagiSpacing.MdDp.dp)
                    .testTag(PLAYER_SOURCE_NONE_TAG)
            )
        }
        state.rows.forEach { row -> SourceRowItem(row, onChoose, onUnlock) }
    }
}

@Composable
private fun SourceRowItem(
    row: SourceRow,
    onChoose: (String) -> Unit,
    onUnlock: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TunenagiSpacing.MdDp.dp)
            .testTag(sourceRowTag(row.sourceId))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = providerName(row.providerId),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (row.isPreferred) {
                Text(
                    text = stringResource(R.string.source_badge_preferred),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(sourcePreferredTag(row.sourceId))
                )
            }
            if (row.isLocked) {
                Text(
                    text = stringResource(R.string.source_badge_locked),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(start = TunenagiSpacing.SmDp.dp)
                        .testTag(sourceLockedTag(row.sourceId))
                )
            }
        }
        Text(text = statusText(row.status), style = MaterialTheme.typography.bodyMedium)
        row.format?.let { format ->
            Text(
                // The source's own content type. Never derived from an extension: claiming
                // a format the source did not report is the lossless-by-extension lie.
                text = stringResource(R.string.source_format, format),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (row.isLocked) {
            TextButton(
                onClick = { onUnlock(row.sourceId) },
                modifier = Modifier.testTag(sourceUnlockTag(row.sourceId))
            ) {
                Text(text = stringResource(R.string.source_unlock))
            }
        } else {
            Button(
                onClick = { onChoose(row.sourceId) },
                modifier = Modifier.testTag(sourceUseTag(row.sourceId))
            ) {
                Text(text = stringResource(R.string.source_use))
            }
        }
    }
}

/** Exhaustive, so a new [SourceStatus] cannot ship without deciding how it reads. */
@Composable
private fun statusText(status: SourceStatus): String = when (status) {
    SourceStatus.Playable -> stringResource(R.string.source_status_playable)
    SourceStatus.Restricted -> stringResource(R.string.source_status_restricted)
    SourceStatus.FileMissing -> stringResource(R.string.source_status_file_missing)
    SourceStatus.PermissionNeeded -> stringResource(R.string.source_status_permission)
    SourceStatus.Unavailable -> stringResource(R.string.source_status_unavailable)
    SourceStatus.NotChecked -> stringResource(R.string.source_status_unchecked)
}

/**
 * A built-in provider is named by the product; a plugin is named by the host its id carries,
 * which is the only name declarative manifest v1 has for it.
 */
@Composable
private fun providerName(providerId: String): String = when (providerId) {
    ProviderId.Local.value -> stringResource(R.string.source_provider_device)
    ProviderId.Audius.value -> stringResource(R.string.source_provider_audius)
    else -> providerId.removePrefix("external.")
}
