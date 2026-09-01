package com.ping.messenger.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.CallRecord
import com.ping.messenger.domain.repository.CallAvailability
import com.ping.messenger.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallsUiState(
    val calls: List<CallRecord> = emptyList(),
    val availability: CallAvailability = CallAvailability.NotConfigured,
    val isLoading: Boolean = true,
    val error: AppError? = null,
) {
    val callsEnabled: Boolean get() = availability is CallAvailability.Available
}

@HiltViewModel
class CallsViewModel @Inject constructor(
    private val calls: CallRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val loaded: Boolean = false,
        val availability: CallAvailability = CallAvailability.NotConfigured,
        val error: AppError? = null,
    )

    val uiState: StateFlow<CallsUiState> =
        combine(calls.observeHistory(), local) { history, localState ->
            CallsUiState(
                calls = history,
                availability = localState.availability,
                isLoading = !localState.loaded,
                error = localState.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallsUiState())

    init {
        viewModelScope.launch {
            // Availability is resolved once on open rather than per row, since it depends on
            // server config plus user overrides and does not change while the screen is up.
            val availability = calls.availability()
            calls.refresh()
            local.update { it.copy(loaded = true, availability = availability) }
        }
    }

    fun clearHistory() = viewModelScope.launch {
        when (val result = calls.clearHistory()) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> local.update { it.copy(error = result.error) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}
