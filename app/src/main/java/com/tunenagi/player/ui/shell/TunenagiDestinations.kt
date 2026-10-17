package com.tunenagi.player.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.tunenagi.player.R

/**
 * Routes the shell owns.
 *
 * Only the player is a standalone route: it opens as a full-screen destination above the
 * tabs and returns to whichever tab launched it, per `SPEC/UX_DESIGN.md`.
 */
object TunenagiRoutes {
    const val Player = "player"
}

/**
 * The three top-level destinations.
 *
 * This enum is the single definition of the tab contract — order, route, label and icon —
 * so the bottom bar and the wide-layout rail cannot disagree about which tabs exist.
 */
enum class TunenagiTab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Home("home", R.string.tab_home, Icons.Filled.Home),
    Search("search", R.string.tab_search, Icons.Filled.Search),
    Library("library", R.string.tab_library, Icons.AutoMirrored.Filled.List);

    /** Stable handle for UI tests in either layout. */
    val testTag: String get() = "tab-$route"
}
