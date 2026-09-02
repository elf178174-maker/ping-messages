package com.ping.messenger.feature.contacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.feature.profile.EditProfileViewModel

/**
 * Supplies the signed-in user's own details to the QR screen.
 *
 * The code encodes a username, never a phone number: that is the whole point of Ping's contact
 * codes, and it means a screenshot of one reveals nothing a person has not already chosen to
 * publish.
 */
@Composable
fun MyQrCodeRoute(
    onBack: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MyQrCodeScreen(
        username = state.username,
        displayName = state.displayName,
        avatarUrl = state.avatarUrl,
        onBack = onBack,
        onScan = onScan,
        modifier = modifier,
    )
}
