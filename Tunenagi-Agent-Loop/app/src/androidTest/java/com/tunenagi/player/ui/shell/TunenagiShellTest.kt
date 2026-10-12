package com.tunenagi.player.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

/**
 * Runs the real shell on a device/emulator. These are the behaviours `SPEC/UX_DESIGN.md`
 * fixes for navigation: three destinations, the player above them returning to its origin,
 * a mini-player that does not exist without playback, and a wide layout that swaps the bar
 * for a rail.
 */
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

    @Test
    fun wideLayoutUsesARailInsteadOfTheBottomBar() {
        setShell(layout = ShellLayout.Wide)

        compose.onNodeWithTag(NAV_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NAV_BAR_TAG).assertDoesNotExist()

        compose.onNodeWithTag(TunenagiTab.Library.testTag).performClick()
        compose.onNodeWithTag(LIBRARY_SCREEN_TAG).assertIsDisplayed()
    }
}
