package com.ping.messenger.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.StatusThread
import com.ping.messenger.domain.repository.StatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val myThread: StatusThread? = null,
    val recent: List<StatusThread> = emptyList(),
    val viewed: List<StatusThread> = emptyList(),
    val isLoading: Boolean = true,
    val isPosting: Boolean = false,
    val error: AppError? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && myThread == null && recent.isEmpty() && viewed.isEmpty()
}

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val status: StatusRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private data class LocalState(
        val loaded: Boolean = false,
        val posting: Boolean = false,
        val error: AppError? = null,
    )

    /**
     * Threads are split into unseen ("Recent") and already-viewed, which is the split every
     * stories UI uses: it keeps the list the user still has something to look at at the top,
     * and lets the rest fall away without disappearing entirely.
     */
    val uiState: StateFlow<StatusUiState> = combine(
        status.observeThreads(),
        status.observeMyThread(),
        local,
    ) { threads, mine, localState ->
        val others = threads.filterNot { it.isMine }
        StatusUiState(
            myThread = mine,
            recent = others.filter { it.hasUnseen },
            viewed = others.filterNot { it.hasUnseen },
            isLoading = !localState.loaded,
            isPosting = localState.posting,
            error = localState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    init {
        refresh()
        // Expired posts are pruned on open as well as by the periodic worker, so a status is
        // never briefly visible past its 24 hours just because the worker has not run.
        viewModelScope.launch { status.purgeExpired() }
    }

    fun refresh() = viewModelScope.launch {
        when (val result = status.refresh()) {
            is Outcome.Success -> local.update { it.copy(loaded = true, error = null) }
            is Outcome.Failure -> local.update { it.copy(loaded = true, error = result.error) }
        }
    }

    fun postText(text: String, backgroundColor: Long) = post(StatusKind.TEXT, text, null, backgroundColor)

    fun postMedia(localPath: String, caption: String, isVideo: Boolean) =
        post(if (isVideo) StatusKind.VIDEO else StatusKind.IMAGE, caption, localPath, null)

    private fun post(kind: StatusKind, text: String, path: String?, colour: Long?) =
        viewModelScope.launch {
            local.update { it.copy(posting = true) }
            when (val result = status.post(kind, text, path, colour)) {
                is Outcome.Success -> _events.emit("Status posted")
                is Outcome.Failure -> local.update { it.copy(error = result.error) }
            }
            local.update { it.copy(posting = false) }
        }

    fun markSeen(statusId: String) = viewModelScope.launch { status.markSeen(statusId) }

    fun delete(statusId: String) = viewModelScope.launch {
        when (val result = status.delete(statusId)) {
            is Outcome.Success -> _events.emit("Status deleted")
            is Outcome.Failure -> local.update { it.copy(error = result.error) }
        }
    }

    fun reply(statusId: String, authorId: String, text: String) = viewModelScope.launch {
        when (val result = status.replyTo(statusId, authorId, text)) {
            is Outcome.Success -> _events.emit("Reply sent")
            is Outcome.Failure -> local.update { it.copy(error = result.error) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}
