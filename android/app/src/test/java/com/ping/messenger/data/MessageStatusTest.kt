package com.ping.messenger.data

import com.google.common.truth.Truth.assertThat
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessagePreview
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.model.Poll
import com.ping.messenger.domain.model.PollOption
import com.ping.messenger.domain.model.Reaction
import com.ping.messenger.domain.model.User
import org.junit.Test

/**
 * Behaviour that lives on the domain models themselves. These are the rules the UI relies on,
 * so they are worth pinning even though the code is small.
 */
class MessageStatusTest {

    @Test
    fun `status order lets a later receipt win and an earlier one be ignored`() {
        // The DAO advances status with a CASE expression in this exact order; if the enum is
        // reordered without updating that SQL, a DELIVERED receipt arriving after READ would
        // silently downgrade the tick.
        val order = MessageStatus.entries.map { it.name }
        assertThat(order).containsExactly(
            "PENDING", "SENDING", "SENT", "DELIVERED", "READ", "FAILED",
        ).inOrder()
    }

    @Test
    fun `in-flight and terminal states are classified correctly`() {
        assertThat(MessageStatus.PENDING.isInFlight).isTrue()
        assertThat(MessageStatus.SENDING.isInFlight).isTrue()
        assertThat(MessageStatus.SENT.isInFlight).isFalse()
        assertThat(MessageStatus.FAILED.isTerminalFailure).isTrue()
        assertThat(MessageStatus.READ.isTerminalFailure).isFalse()
    }

    @Test
    fun `preview text prefers the body then the poll question then the file name`() {
        val text = message(text = "hello")
        assertThat(text.previewText).isEqualTo("hello")

        val poll = message(
            text = "",
            kind = MessageKind.POLL,
            poll = Poll(id = "p", question = "Lunch?", options = emptyList()),
        )
        assertThat(poll.previewText).isEqualTo("Lunch?")

        val file = message(
            text = "",
            kind = MessageKind.DOCUMENT,
            attachments = listOf(attachment("report.pdf")),
        )
        assertThat(file.previewText).isEqualTo("report.pdf")
    }

    @Test
    fun `a deleted message has no preview text`() {
        assertThat(message(text = "secret", isDeleted = true).previewText).isEmpty()
    }

    @Test
    fun `reaction count reflects the number of distinct users`() {
        val reaction = Reaction(emoji = "👍", userIds = listOf("a", "b", "c"), reactedByMe = true)
        assertThat(reaction.count).isEqualTo(3)
    }

    @Test
    fun `poll tallies votes across options and reports my selections`() {
        val poll = Poll(
            id = "p",
            question = "Lunch?",
            options = listOf(
                PollOption(id = "1", text = "Pizza", voterIds = listOf("a", "b"), votedByMe = true),
                PollOption(id = "2", text = "Salad", voterIds = listOf("c")),
            ),
        )
        assertThat(poll.totalVotes).isEqualTo(3)
        assertThat(poll.myVotes).containsExactly("1")
    }

    @Test
    fun `conversation reports unread when marked unread even with a zero count`() {
        val conversation = conversation(unreadCount = 0, markedUnread = true)
        assertThat(conversation.hasUnread).isTrue()

        assertThat(conversation(unreadCount = 3).hasUnread).isTrue()
        assertThat(conversation().hasUnread).isFalse()
    }

    @Test
    fun `user initials fall back to the username when the display name is blank`() {
        assertThat(User(id = "1", username = "ada", displayName = "Ada Lovelace").initials)
            .isEqualTo("AL")
        assertThat(User(id = "1", username = "ada", displayName = "").initials).isEqualTo("A")
    }

    @Test
    fun `handle is the username with an at sign`() {
        assertThat(User(id = "1", username = "ada", displayName = "Ada").handle).isEqualTo("@ada")
    }

    @Test
    fun `attachment aspect ratio guards against a zero height`() {
        assertThat(attachment("a.jpg").copy(width = 200, height = 100).aspectRatio).isEqualTo(2f)
        assertThat(attachment("a.jpg").copy(width = 200, height = 0).aspectRatio).isEqualTo(1f)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun message(
        text: String = "",
        kind: MessageKind = MessageKind.TEXT,
        isDeleted: Boolean = false,
        attachments: List<Attachment> = emptyList(),
        poll: Poll? = null,
    ) = Message(
        id = "m1",
        conversationId = "c1",
        senderId = "u1",
        kind = kind,
        text = text,
        createdAt = 1_700_000_000_000,
        isDeleted = isDeleted,
        attachments = attachments,
        poll = poll,
    )

    private fun attachment(fileName: String) = Attachment(
        id = "a1",
        messageId = "m1",
        kind = MessageKind.DOCUMENT,
        fileName = fileName,
    )

    private fun conversation(unreadCount: Int = 0, markedUnread: Boolean = false) = Conversation(
        id = "c1",
        type = ConversationType.DIRECT,
        title = "Ada",
        unreadCount = unreadCount,
        markedUnread = markedUnread,
        lastMessage = MessagePreview(
            id = "m1",
            senderId = "u1",
            senderName = "Ada",
            text = "hi",
            kind = MessageKind.TEXT,
            timestamp = 1_700_000_000_000,
            status = MessageStatus.READ,
            isOutgoing = false,
        ),
    )
}
