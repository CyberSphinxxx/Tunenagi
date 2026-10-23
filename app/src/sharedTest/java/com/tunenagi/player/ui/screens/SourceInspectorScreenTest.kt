package com.tunenagi.player.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunenagi.player.sourceinspector.ActionFailure
import com.tunenagi.player.sourceinspector.Notice
import com.tunenagi.player.sourceinspector.SourceInspectorState
import com.tunenagi.player.sourceinspector.SourceRow
import com.tunenagi.player.sourceinspector.SourceStatus
import com.tunenagi.player.ui.playback.SourceInspectorUi
import com.tunenagi.player.ui.theme.TunenagiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's source inspector as a user meets it — owned (TUN-0307).
 *
 * This file lives in `src/sharedTest`, so the same source runs twice: as an ordinary JVM test
 * under Robolectric (`:app:testDebugUnitTest`, no device needed) and as an instrumented test on an
 * attached device (`:app:connectedDebugAndroidTest`). What is asserted is the task's acceptance in
 * words on screen: identity, typed status, the format the source actually reported, the preferred
 * and locked badges, the unavailable-variant feedback, and that the two actions send back exactly
 * the source id they were rendered for. Every string here is the real resource text.
 */
@RunWith(AndroidJUnit4::class)
class SourceInspectorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(inspector: SourceInspectorUi) {
        compose.setContent {
            TunenagiTheme {
                PlayerScreen(onClose = {}, inspector = inspector)
            }
        }
    }

    private fun row(
        sourceId: String,
        providerId: String,
        status: SourceStatus,
        format: String?,
        preferred: Boolean = false,
        locked: Boolean = false
    ) = SourceRow(
        sourceId = sourceId,
        providerId = providerId,
        status = status,
        format = format,
        isPreferred = preferred,
        isLocked = locked
    )

    @Test
    fun nothingLoadedKeepsTheNothingPlayingState() {
        setScreen(SourceInspectorUi.None)

        compose.onNodeWithText("Nothing is playing").assertIsDisplayed()
    }

    @Test
    fun everySourceShowsItsIdentityStatusAndTheFormatItReported() {
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(
                    rows = listOf(
                        row("s1", "builtin.audius", SourceStatus.Playable, "audio/mpeg", preferred = true),
                        row("s2", "builtin.local", SourceStatus.FileMissing, format = null)
                    )
                ),
                onChoose = {},
                onUnlock = {}
            )
        )

        compose.onNodeWithText("Audius").assertIsDisplayed()
        compose.onNodeWithText("This device").assertIsDisplayed()
        compose.onNodeWithText("Ready to play").assertIsDisplayed()
        compose.onNodeWithText("Format: audio/mpeg").assertIsDisplayed()
        compose.onNodeWithText("File missing from this device").assertIsDisplayed()
        compose.onNodeWithTag(sourcePreferredTag("s1")).assertIsDisplayed()
        // A format the source never reported is not rendered, not guessed.
        compose.onNodeWithText("Format: null").assertDoesNotExist()
    }

    @Test
    fun anUnavailablePlaybackChoiceIsCalledOutWithItsReason() {
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(
                    rows = listOf(row("s1", "builtin.audius", SourceStatus.Restricted, "audio/mpeg", locked = true)),
                    unavailableFeedback = com.tunenagi.player.sourceinspector.UnavailableFeedback(
                        sourceId = "s1",
                        status = SourceStatus.Restricted
                    )
                ),
                onChoose = {},
                onUnlock = {}
            )
        )

        compose.onNodeWithTag(PLAYER_SOURCE_FEEDBACK_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            "Playback would use an unavailable source: Not allowed by the creator. Choose another below."
        ).assertIsDisplayed()
    }

    @Test
    fun usingASourceSendsBackExactlyTheIdItWasRenderedFor() {
        var chosen: String? = null
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(
                    rows = listOf(row("source-42", "builtin.audius", SourceStatus.Playable, null))
                ),
                onChoose = { chosen = it },
                onUnlock = {}
            )
        )

        compose.onNodeWithTag(sourceUseTag("source-42")).performClick()

        assertEquals("source-42", chosen)
    }

    @Test
    fun aLockedSourceOffersUnlockAndNotUseAgain() {
        var unlocked: String? = null
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(
                    rows = listOf(
                        row("s1", "builtin.audius", SourceStatus.Playable, "audio/mpeg", preferred = true, locked = true)
                    )
                ),
                onChoose = {},
                onUnlock = { unlocked = it }
            )
        )

        compose.onNodeWithTag(sourceLockedTag("s1")).assertIsDisplayed()
        compose.onNodeWithTag(sourceUseTag("s1")).assertDoesNotExist()
        compose.onNodeWithTag(sourceUnlockTag("s1")).performClick()

        assertEquals("s1", unlocked)
    }

    @Test
    fun aTrackWithNoStoredSourcesSaysSoInsteadOfShowingNothing() {
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(rows = emptyList()),
                onChoose = {},
                onUnlock = {}
            )
        )

        compose.onNodeWithTag(PLAYER_SOURCE_NONE_TAG).assertIsDisplayed()
        compose.onNodeWithText("No sources are stored for this track.").assertIsDisplayed()
    }

    @Test
    fun aConfirmedChoiceAndARefusalBothReadAsSentences() {
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.Loaded(
                    rows = emptyList(),
                    notice = Notice.Chosen("builtin.audius"),
                    failure = ActionFailure.SourceNotListed
                ),
                onChoose = {},
                onUnlock = {}
            )
        )

        compose.onNodeWithTag(PLAYER_SOURCE_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Switched playback to Audius.").assertIsDisplayed()
        compose.onNodeWithTag(PLAYER_SOURCE_FAILURE_TAG).assertIsDisplayed()
        compose.onNodeWithText("That source could not be chosen.").assertIsDisplayed()
    }

    @Test
    fun aMediaIdThatIsNotALibraryTrackExplainsItself() {
        setScreen(
            SourceInspectorUi(
                state = SourceInspectorState.NotALibraryTrack,
                onChoose = {},
                onUnlock = {}
            )
        )

        compose.onNodeWithText("Source details are only shown for a track from your library.")
            .assertIsDisplayed()
    }
}
