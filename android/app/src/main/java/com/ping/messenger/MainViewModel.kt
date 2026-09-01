package com.ping.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.network.SessionExpiryNotifier
import com.ping.messenger.data.remote.ws.RealtimeClient
import com.ping.messenger.di.httpToWebSocketUrl
import com.ping.messenger.domain.repository.AuthRepository
import com.ping.messenger.domain.repository.AuthState
import com.ping.messenger.domain.repository.CallRepository
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.StatusRepository
import com.ping.messenger.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val authState: AuthState = AuthState.Loading,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val fontScale: Float = 1f,
    val blockScreenshots: Boolean = false,
) {
    /** True while the splash should stay up: the session is not yet known. */
    val isResolving: Boolean get() = authState is AuthState.Loading
}

/**
 * Process-level state: which session is active, and the appearance settings that wrap the whole
 * UI. Also owns the realtime connection's lifecycle, since the socket should be up exactly when
 * a user is signed in.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    preferences: AppPreferences,
    private val realtimeClient: RealtimeClient,
    private val sessionExpiry: SessionExpiryNotifier,
    @Named("baseUrl") private val baseUrl: String,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        authRepository.authState,
        preferences.appearance,
        preferences.security,
    ) { auth, appearance, security ->
        MainUiState(
            authState = auth,
            themeMode = appearance.themeMode,
            dynamicColor = appearance.dynamicColor,
            highContrast = appearance.highContrast,
            reduceMotion = appearance.reduceMotion,
            fontScale = appearance.fontScale,
            blockScreenshots = security.blockScreenshots,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, not WhileSubscribed: the splash reads this before anything subscribes, and
        // the socket lifecycle below must not stop when the UI briefly detaches.
        started = SharingStarted.Eagerly,
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                if (state is AuthState.SignedIn) {
                    realtimeClient.connect(httpToWebSocketUrl(baseUrl))
                } else {
                    realtimeClient.disconnect()
                }
            }
        }
    }
}

data class BadgeCounts(
    val unread: Int = 0,
    val unseenStatus: Int = 0,
    val missedCalls: Int = 0,
)

/** Feeds the tab badges. Kept separate so the whole app does not recompose when a count moves. */
@HiltViewModel
class BadgeViewModel @Inject constructor(
    conversations: ConversationRepository,
    status: StatusRepository,
    calls: CallRepository,
) : ViewModel() {

    val counts: StateFlow<BadgeCounts> = combine(
        conversations.observeTotalUnread(),
        status.observeUnseenCount(),
        calls.observeMissedCount(),
    ) { unread, unseen, missed ->
        BadgeCounts(unread = unread, unseenStatus = unseen, missedCalls = missed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BadgeCounts())
}
