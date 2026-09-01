package com.ping.messenger.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.ping.messenger.R

/** The four tab roots. */
enum class PingTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CHATS(Routes.CHATS, R.string.nav_chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    STATUS(Routes.STATUS, R.string.nav_status, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    CALLS(Routes.CALLS, R.string.nav_calls, Icons.Filled.Call, Icons.Outlined.Call),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * The bottom navigation bar.
 *
 * Tab switching uses `saveState`/`restoreState` with `popUpTo(startDestination)`, which is what
 * gives each tab its own remembered scroll position while keeping the system back button
 * predictable: back from any tab returns to Chats, then exits.
 */
@Composable
fun PingBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?,
    unreadCount: Int,
    unseenStatusCount: Int,
    missedCallCount: Int,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        PingTab.entries.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            val badgeCount = when (tab) {
                PingTab.CHATS -> unreadCount
                PingTab.STATUS -> unseenStatusCount
                PingTab.CALLS -> missedCallCount
                PingTab.SETTINGS -> 0
            }

            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) return@NavigationBarItem
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    BadgedBox(
                        badge = {
                            if (badgeCount > 0) {
                                Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** True when the bottom bar should be visible: only on the four tab roots. */
fun NavDestination?.isTabRoot(): Boolean =
    this?.route in PingTab.entries.map { it.route }
