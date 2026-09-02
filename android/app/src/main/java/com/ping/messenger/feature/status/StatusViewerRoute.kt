package com.ping.messenger.feature.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.ui.components.LoadingState

/**
 * Resolves a status author id from the route into the thread the viewer needs.
 *
 * The viewer takes a whole [com.ping.messenger.domain.model.StatusThread] because it steps
 * through the posts locally; navigation can only carry an id. This adapter is the seam, and it
 * closes itself if the thread is gone - a status that expired while the user was tapping
 * towards it should not open an empty screen.
 */
@Composable
fun StatusViewerRoute(
    authorId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thread = (state.recent + state.viewed + listOfNotNull(state.myThread))
        .firstOrNull { it.authorId == authorId }

    LaunchedEffect(thread, state.isLoading) {
        if (thread == null && !state.isLoading) onClose()
    }

    if (thread == null) {
        LoadingState(modifier)
        return
    }

    StatusViewerScreen(
        thread = thread,
        onClose = onClose,
        onSeen = viewModel::markSeen,
        onDelete = viewModel::delete,
        modifier = modifier,
    )
}
