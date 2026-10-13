package com.tunenagi.player.ui.shell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunenagi.core.designsystem.ShellLayout
import com.tunenagi.player.ui.screens.HOME_SCREEN_TAG
import com.tunenagi.player.ui.screens.LIBRARY_SCREEN_TAG
import com.tunenagi.player.ui.screens.PLAYER_CLOSE_TAG
import com.tunenagi.player.ui.screens.PLAYER_SCREEN_TAG
import com.tunenagi.player.ui.screens.SEARCH_FIELD_TAG
import com.tunenagi.player.ui.screens.SEARCH_SCREEN_TAG
import com.tunenagi.player.ui.theme.TunenagiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's navigation contract from `SPEC/UX_DESIGN.md`: three destinations, the player above
 * them returning to its origin, a mini-player that does not exist without playback, and a wide
 * layout that swaps the bar for a rail.
 *
 * This file lives in `src/sharedTest`, so the same source runs twice: as an ordinary JVM test
 * under Robolectric (`:app:testDebugUnitTest`, no device needed) and as an instrumented test on
 * an attached device/emulator (`:app:connectedDebugAndroidTest`). `AndroidJUnit4` is the
 * delegating runner that makes that possible — it selects Robolectric on the JVM and the
 * instrumentation runner on Android. The subject is the real Compose tree and the real
 * navigation graph; nothing here is stubbed.
 */
@RunWith(AndroidJUnit4::class)
class TunenagiShellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setShell(
        playerState: MiniPlayerState = MiniPlayerState.None,
        layout: ShellLayout = ShellLayout.Compact
    ) {
        compose.setContent {
            TunenagiTheme {
                TunenagiShell(playerState = playerState, layout = layout)
            }
        }
    }

    @Test
    fun shellStartsOnHomeWithThreeTabs() {
        setShell()

        compose.onNodeWithTag(NAV_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        TunenagiTab.entries.forEach { tab ->
            compose.onNodeWithTag(tab.testTag).assertIsDisplayed()
        }
    }

    @Test
    fun tappingATabSwitchesContent() {
        setShell()

        compose.onNodeWithTag(TunenagiTab.Search.testTag).performClick()
        compose.onNodeWithTag(SEARCH_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()

        compose.onNodeWithTag(TunenagiTab.Library.testTag).performClick()
        compose.onNodeWithTag(LIBRARY_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HOME_SCREEN_TAG).assertDoesNotExist()
    }

    /** Failure path: with nothing playing there must be no mini-player reserving space. */
    @Test
    fun miniPlayerIsAbsentWhenNothingIsPlaying() {
        setShell()

        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
    }

    @Test
    fun miniPlayerOpensThePlayerAndCloseReturnsToItsTab() {
        setShell(playerState = MiniPlayerState.Track(title = "Slow Tide", artist = "Kaori Ito"))

        compose.onNodeWithTag(TunenagiTab.Library.testTag).performClick()
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertIsDisplayed().performClick()

        compose.onNodeWithTag(PLAYER_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PLAYER_CLOSE_TAG).performClick()

        compose.onNodeWithTag(LIBRARY_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PLAYER_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun miniPlayerAndNavigationStepAsideOnThePlayerRoute() {
        setShell(playerState = MiniPlayerState.Track(title = "Slow Tide", artist = "Kaori Ito"))

        compose.onNodeWithTag(MINI_PLAYER_TAG).performClick()
        compose.onNodeWithTag(PLAYER_SCREEN_TAG).assertIsDisplayed()

        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NAV_BAR_TAG).assertDoesNotExist()
    }

    /**
     * Regression for BUG-0001: the compact shell used to mount the mini-player twice (once in
     * the bottom bar and once in the content column), which reserved its height twice and left
     * two identical clickable nodes for a single piece of UI.
     */
    @Test
    fun theMiniPlayerIsMountedExactlyOnceInCompactLayout() {
        setShell(playerState = MiniPlayerState.Track(title = "Slow Tide", artist = "Kaori Ito"))

        compose.onAllNodesWithTag(MINI_PLAYER_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag(NAV_BAR_TAG).assertCountEquals(1)
    }

    @Test
    fun theMiniPlayerIsMountedExactlyOnceInWideLayout() {
        setShell(
            playerState = MiniPlayerState.Track(title = "Slow Tide", artist = "Kaori Ito"),
            layout = ShellLayout.Wide
        )

        compose.onAllNodesWithTag(MINI_PLAYER_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag(NAV_RAIL_TAG).assertCountEquals(1)
    }

    @Test
    fun wideLayoutUsesARailInsteadOfTheBottomBar() {
        setShell(layout = ShellLayout.Wide)

        compose.onNodeWithTag(NAV_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NAV_BAR_TAG).assertDoesNotExist()

        compose.onNodeWithTag(TunenagiTab.Library.testTag).performClick()
        compose.onNodeWithTag(LIBRARY_SCREEN_TAG).assertIsDisplayed()
    }
}
