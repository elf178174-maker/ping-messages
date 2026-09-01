package com.ping.messenger

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ping.messenger.domain.repository.AuthState
import com.ping.messenger.ui.navigation.PingBottomBar
import com.ping.messenger.ui.navigation.PingNavHost
import com.ping.messenger.ui.navigation.isTabRoot
import com.ping.messenger.ui.theme.PingTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity.
 *
 * Everything after the splash frame is Compose. The activity's own job is small and specific:
 * hold the splash until the session state is known, apply the screenshot-blocking flag, and
 * host the navigation graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )

        // Holding the splash until the session is resolved avoids a visible flash of the
        // sign-in screen for a user who is in fact already signed in. `keepSplash` is flipped
        // from the composition as soon as the first non-Loading state arrives.
        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }

        setContent {
            val viewModel: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.isResolving) { keepSplash = state.isResolving }

            // FLAG_SECURE has to be applied to the window, so it lives here rather than in a
            // composable. It hides the app in the recents switcher as well as blocking
            // screenshots, which is the pair of behaviours users expect from this setting.
            LaunchedEffect(state.blockScreenshots) {
                if (state.blockScreenshots) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            PingTheme(
                themeMode = state.themeMode,
                dynamicColor = state.dynamicColor,
                highContrast = state.highContrast,
                reduceMotion = state.reduceMotion,
            ) {
                ScaledDensity(state.fontScale) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) {
                        PingApp(authState = state.authState)
                    }
                }
            }
        }
    }

    private companion object {
        const val TRANSPARENT = 0
    }
}

/**
 * Applies the user's in-app text-size preference on top of the system font scale.
 *
 * Done by overriding [LocalDensity] rather than by scaling every text style, so it applies
 * uniformly — including to text inside third-party composables.
 */
@Composable
private fun ScaledDensity(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(
            density = density.density,
            fontScale = density.fontScale * scale,
        ),
        content = content,
    )
}

@Composable
private fun PingApp(authState: AuthState) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val badges by rememberBadgeCounts()

    Scaffold(
        bottomBar = {
            if (authState is AuthState.SignedIn && destination.isTabRoot()) {
                PingBottomBar(
                    navController = navController,
                    currentDestination = destination,
                    unreadCount = badges.unread,
                    unseenStatusCount = badges.unseenStatus,
                    missedCallCount = badges.missedCalls,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            PingNavHost(authState = authState, navController = navController)
        }
    }
}

/** Tab badge counts, read from the badge view-model. */
@Composable
private fun rememberBadgeCounts(): androidx.compose.runtime.State<BadgeCounts> {
    val viewModel: BadgeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    return viewModel.counts.collectAsStateWithLifecycle()
}
