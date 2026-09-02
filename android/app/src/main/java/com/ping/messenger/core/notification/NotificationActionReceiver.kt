package com.ping.messenger.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.ping.messenger.core.work.SyncScheduler
import com.ping.messenger.di.ApplicationScope
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.OutgoingMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles the notification action buttons.
 *
 * An inline reply from the shade goes through the same outbox path as a reply typed in the
 * app, so it works offline and shows the same delivery states. A separate "quick send" path
 * would be a second implementation of the hardest part of the app.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var messages: MessageRepository
    @Inject lateinit var conversations: ConversationRepository
    @Inject lateinit var notifier: MessageNotifier
    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return

        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MessageNotifier.KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()

                if (text.isEmpty()) return

                // The application scope, not a receiver-local one: onReceive returns
                // immediately and a scope tied to it would be cancelled mid-send.
                scope.launch {
                    messages.send(OutgoingMessage(conversationId = conversationId, text = text))
                    conversations.markRead(conversationId)
                    syncScheduler.requestSync()
                    notifier.cancelConversation(conversationId)
                }
            }

            ACTION_MARK_READ -> {
                scope.launch {
                    conversations.markRead(conversationId)
                    notifier.cancelConversation(conversationId)
                }
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.ping.messenger.action.REPLY"
        const val ACTION_MARK_READ = "com.ping.messenger.action.MARK_READ"
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}
