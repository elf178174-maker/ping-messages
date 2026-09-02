package com.ping.messenger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The local database, exercised against a real SQLite instance.
 *
 * These are the assertions that would break if the schema or a query drifted, rather than
 * assertions that a setter sets: that a receipt cannot move a message backwards through its
 * delivery states, that the full-text index is maintained by the write path rather than by
 * whoever remembers to call it, that the chat list ordering matches its covering index, and
 * that a cascade actually cascades.
 */
@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    private lateinit var database: PingDatabase
    private lateinit var messages: MessageDao
    private lateinit var conversations: ConversationDao

    @Before
    fun setUp() {
        database = Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                PingDatabase::class.java,
            )
            // In-memory, single-threaded, so a suspend DAO call in runTest does not deadlock
            // against Room's own executor.
            .allowMainThreadQueries()
            .build()
        messages = database.messageDao()
        conversations = database.conversationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun givenConversation(
        id: String = "c1",
        pinned: Boolean = false,
        archived: Boolean = false,
        lastActivityAt: Long = 0,
    ) = ConversationEntity(
        id = id,
        type = ConversationType.DIRECT,
        title = "Ada",
        isPinned = pinned,
        isArchived = archived,
        lastActivityAt = lastActivityAt,
    ).also { conversations.upsert(it) }

    private fun message(
        id: String,
        conversationId: String = "c1",
        text: String = "hello",
        createdAt: Long = 1_000,
        status: MessageStatus = MessageStatus.SENT,
        outgoing: Boolean = true,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = "u1",
        text = text,
        createdAt = createdAt,
        status = status,
        isOutgoing = outgoing,
    )

    @Test
    fun `saving a message makes it findable and searchable`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", text = "the eagle has landed"))

        assertThat(messages.findRaw("c1", "m1")).isNotNull()
        // The write path maintains the FTS index; nothing else has to remember to.
        val hits = messages.searchAll("\"eagle\"*")
        assertThat(hits.map { it.messageId }).containsExactly("m1")
    }

    @Test
    fun `search is scoped when a conversation is given`() = runTest {
        givenConversation("c1")
        givenConversation("c2")
        messages.saveMessage(message("m1", conversationId = "c1", text = "shared secret"))
        messages.saveMessage(message("m2", conversationId = "c2", text = "shared secret"))

        assertThat(messages.searchAll("\"secret\"*").map { it.messageId })
            .containsExactly("m1", "m2")
        assertThat(messages.searchInConversation("c2", "\"secret\"*").map { it.messageId })
            .containsExactly("m2")
    }

    @Test
    fun `a delivered receipt cannot downgrade a read message`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", status = MessageStatus.SENT))

        messages.advanceStatus("m1", MessageStatus.READ)
        // Receipts arrive out of order more often than people expect, and a DELIVERED landing
        // after a READ must be a no-op rather than a visible regression in the tick.
        messages.advanceStatus("m1", MessageStatus.DELIVERED)

        assertThat(messages.findRaw("c1", "m1")?.status).isEqualTo(MessageStatus.READ)
    }

    @Test
    fun `advancing forwards still works`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", status = MessageStatus.PENDING))

        messages.advanceStatus("m1", MessageStatus.SENT)
        messages.advanceStatus("m1", MessageStatus.DELIVERED)

        assertThat(messages.findRaw("c1", "m1")?.status).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun `unread counts only incoming messages that are not read`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", outgoing = false, status = MessageStatus.DELIVERED))
        messages.saveMessage(message("m2", outgoing = false, status = MessageStatus.READ))
        messages.saveMessage(message("m3", outgoing = true, status = MessageStatus.SENT))

        assertThat(messages.unreadCount("c1")).isEqualTo(1)
    }

    @Test
    fun `marking incoming read clears the unread count`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", outgoing = false, status = MessageStatus.DELIVERED))

        messages.markIncomingRead("c1")

        assertThat(messages.unreadCount("c1")).isEqualTo(0)
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1"))

        conversations.delete("c1")

        assertThat(messages.findRaw("c1", "m1")).isNull()
        // And the index goes with it, so a deleted message cannot come back as a search hit.
        assertThat(messages.searchAll("\"hello\"*")).isEmpty()
    }

    @Test
    fun `the chat list is pinned-first then most recent`() = runTest {
        givenConversation("old", lastActivityAt = 100)
        givenConversation("new", lastActivityAt = 300)
        givenConversation("pinned", pinned = true, lastActivityAt = 200)
        givenConversation("archived", archived = true, lastActivityAt = 400)

        val ids = conversations
            .observeConversations(archived = false)
            .first()
            .map { it.conversation.id }

        assertThat(ids).containsExactly("pinned", "new", "old").inOrder()
    }

    @Test
    fun `position of a message is its index in the transcript ordering`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", createdAt = 100))
        messages.saveMessage(message("m2", createdAt = 200))
        messages.saveMessage(message("m3", createdAt = 300))

        // Newest first, so the newest message is at index 0.
        assertThat(messages.positionOf("c1", "m3", 300)).isEqualTo(0)
        assertThat(messages.positionOf("c1", "m1", 100)).isEqualTo(2)
    }

    @Test
    fun `sequence bounds come back from the rows actually held`() = runTest {
        givenConversation()
        messages.saveMessage(message("m1", createdAt = 100).copy(serverSeq = 7))
        messages.saveMessage(message("m2", createdAt = 200).copy(serverSeq = 12))

        assertThat(messages.oldestServerSeq("c1")).isEqualTo(7)
        assertThat(messages.latestServerSeq("c1")).isEqualTo(12)
    }
}
