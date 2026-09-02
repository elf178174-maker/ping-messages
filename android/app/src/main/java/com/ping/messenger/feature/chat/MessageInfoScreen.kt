package com.ping.messenger.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.feature.settings.rememberTimeFormatter
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MessageInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    messages: MessageRepository,
) : ViewModel() {

    private val messageId: String = savedStateHandle[Routes.ARG_MESSAGE_ID] ?: ""

    val message: StateFlow<Message?> = messages
        .observeMessage(messageId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Delivery detail for one message.
 *
 * Receipts are per-person and only exist where both sides have read receipts on, so the screen
 * says why a list is empty instead of showing an unexplained blank.
 */
@Composable
fun MessageInfoScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MessageInfoViewModel = hiltViewModel(),
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val formatter = rememberTimeFormatter()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.message_info_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val current = message
        if (current == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.message_info_title))
            SettingsRow(
                title = stringResource(statusLabelFor(current.status)),
                summary = formatter.listTimestamp(current.createdAt),
                icon = when (current.status) {
                    MessageStatus.PENDING, MessageStatus.SENDING -> Icons.Default.Schedule
                    MessageStatus.SENT -> Icons.Default.Send
                    MessageStatus.DELIVERED -> Icons.Default.DoneAll
                    MessageStatus.READ -> Icons.Default.Visibility
                    MessageStatus.FAILED -> Icons.Default.ErrorOutline
                },
            )
            if (current.isEdited) {
                SettingsRow(
                    title = stringResource(R.string.message_info_edited),
                    summary = current.editedAt?.let { formatter.listTimestamp(it) },
                )
            }
            current.attachments.firstOrNull()?.let { attachment ->
                SettingsRow(
                    title = stringResource(R.string.message_info_size),
                    summary = formatBytes(attachment.sizeBytes),
                )
            }
            SettingsRow(
                title = stringResource(R.string.message_info_encryption),
                summary = stringResource(
                    if (current.isEncrypted) {
                        R.string.message_info_encrypted
                    } else {
                        R.string.message_info_not_encrypted
                    },
                ),
                icon = if (current.isEncrypted) Icons.Default.Lock else Icons.Default.LockOpen,
            )

            SettingsDivider()
            ReceiptSection(
                titleRes = R.string.message_info_read,
                receipts = current.readBy.map { it.userName to it.at },
                formatter = { formatter.listTimestamp(it) },
            )
            ReceiptSection(
                titleRes = R.string.message_info_delivered,
                receipts = current.deliveredTo.map { it.userName to it.at },
                formatter = { formatter.listTimestamp(it) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReceiptSection(
    titleRes: Int,
    receipts: List<Pair<String, Long>>,
    formatter: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(stringResource(titleRes))
        if (receipts.isEmpty()) {
            Text(
                text = stringResource(R.string.message_info_no_receipts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else {
            receipts.forEach { (name, at) ->
                PersonRow(name = name, subtitle = formatter(at))
            }
        }
    }
}

private fun statusLabelFor(status: MessageStatus): Int = when (status) {
    MessageStatus.PENDING, MessageStatus.SENDING -> R.string.message_info_pending
    MessageStatus.SENT -> R.string.message_info_sent
    MessageStatus.DELIVERED -> R.string.message_info_delivered
    MessageStatus.READ -> R.string.message_info_read
    MessageStatus.FAILED -> R.string.message_info_failed
}
