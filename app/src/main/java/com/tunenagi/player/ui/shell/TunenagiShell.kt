package com.tunenagi.player.ui.shell

import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tunenagi.core.designsystem.ShellLayout
import com.tunenagi.core.designsystem.TunenagiMotion
import com.tunenagi.core.designsystem.TunenagiWindowSize
import com.tunenagi.player.ui.screens.HomeScreen
import com.tunenagi.player.ui.screens.LibraryScreen
import com.tunenagi.player.ui.screens.PlayerScreen
import com.tunenagi.player.ui.screens.SearchScreen

const val NAV_BAR_TAG = "nav-bar"
const val NAV_RAIL_TAG = "nav-rail"

/**
 * The app shell — owned.
 *
 * Three tabs (Home, Search, Library) with the player as a full-screen route above them,
 * a mini-player slot that sits above the navigation, and a scaffold that swaps the bottom
 * bar for a navigation rail once the window is wide enough.
 *
 * [layout] and [playerState] are parameters rather than internal state so previews and
 * instrumentation tests can exercise the wide layout and the mini-player without faking a
 * device configuration. Production callers take the defaults, which read the real window width;
 * the playback state and its command are passed in by the host, so this composable needs neither a
 * Hilt graph nor a service to render.
 */
@Composable
fun TunenagiShell(
    modifier: Modifier = Modifier,
    playerState: MiniPlayerState = MiniPlayerState.None,
    onTogglePlayPause: () -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    layout: ShellLayout = TunenagiWindowSize.layoutFor(LocalConfiguration.current.screenWidthDp),
    navController: NavHostController = rememberNavController()
) {
    val currentRoute by navController.currentBackStackEntryAsState()
    val onPlayerRoute = currentRoute?.destination?.route == TunenagiRoutes.Player
    val motionEnabled = rememberMotionEnabled()

    // The mini-player is part of the bottom chrome, not of the content: in the compact
    // layout it sits above the navigation bar inside the same slot, and in the wide layout,
    // which has no bottom bar, it closes the content column. Each layout therefore mounts
    // exactly one host. Mounting it in both places reserved its height twice and put two
    // identical clickable nodes in the semantics tree (found by TunenagiShellTest on the
    // JVM, filed as BUG-0001 and fixed here).
    val miniPlayerVisible = !onPlayerRoute
    val openPlayer = { navController.openPlayer() }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            // The player owns the whole screen; navigation and the mini-player both step
            // aside so nothing competes with the transport controls once they exist.
            if (miniPlayerVisible && layout == ShellLayout.Compact) {
                Column {
                    MiniPlayerHost(
                        state = playerState,
                        onExpand = openPlayer,
                        onTogglePlayPause = onTogglePlayPause,
                        onRetry = onRetryPlayback
                    )
                    TunenagiNavigationBar(navController)
                }
            }
        }
    ) { contentPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            if (miniPlayerVisible && layout == ShellLayout.Wide) {
                TunenagiNavigationRail(navController)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                ShellNavHost(
                    navController = navController,
                    motionEnabled = motionEnabled,
                    modifier = Modifier.weight(1f)
                )
                if (miniPlayerVisible && layout == ShellLayout.Wide) {
                    MiniPlayerHost(
                        state = playerState,
                        onExpand = openPlayer,
                        onTogglePlayPause = onTogglePlayPause,
                        onRetry = onRetryPlayback
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellNavHost(
    navController: NavHostController,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val durationMs = TunenagiMotion.navigationDurationMs(motionEnabled)
    val transition = tween<Float>(durationMillis = durationMs)

    NavHost(
        navController = navController,
        startDestination = TunenagiTab.Home.route,
        modifier = modifier,
        enterTransition = { fadeIn(transition) },
        exitTransition = { fadeOut(transition) },
        popEnterTransition = { fadeIn(transition) },
        popExitTransition = { fadeOut(transition) }
    ) {
        composable(TunenagiTab.Home.route) {
            HomeScreen(onOpenSearch = { navController.switchTab(TunenagiTab.Search) })
        }
        composable(TunenagiTab.Search.route) {
            SearchScreen()
        }
        composable(TunenagiTab.Library.route) {
            LibraryScreen(onOpenSearch = { navController.switchTab(TunenagiTab.Search) })
        }
        composable(TunenagiRoutes.Player) {
            PlayerScreen(onClose = { navController.popBackStack() })
        }
    }
}

@Composable
private fun TunenagiNavigationBar(navController: NavHostController, modifier: Modifier = Modifier) {
    val currentRoute by navController.currentBackStackEntryAsState()
    NavigationBar(modifier = modifier.testTag(NAV_BAR_TAG)) {
        TunenagiTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute?.destination?.route == tab.route,
                onClick = { navController.switchTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag(tab.testTag)
            )
        }
    }
}

@Composable
private fun TunenagiNavigationRail(navController: NavHostController, modifier: Modifier = Modifier) {
    val currentRoute by navController.currentBackStackEntryAsState()
    NavigationRail(modifier = modifier.fillMaxHeight().testTag(NAV_RAIL_TAG)) {
        TunenagiTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute?.destination?.route == tab.route,
                onClick = { navController.switchTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag(tab.testTag)
            )
        }
    }
}

/**
 * Navigating between tabs keeps a single instance per tab and restores its scroll/state,
 * which is what the "switch tabs quickly" flow in `SKILLS/compose-ux.md` depends on.
 */
internal fun NavHostController.switchTab(tab: TunenagiTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun NavHostController.openPlayer() {
    navigate(TunenagiRoutes.Player) { launchSingleTop = true }
}

/**
 * Whether platform animations are enabled.
 *
 * `SPEC/UX_DESIGN.md` requires reduced-motion to be honoured. Android exposes that as the
 * animator duration scale, so transitions collapse to zero duration instead of guessing
 * from a device model.
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
}
