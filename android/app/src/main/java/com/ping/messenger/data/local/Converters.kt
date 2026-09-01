package com.ping.messenger.data.local

import androidx.room.TypeConverter
import com.ping.messenger.data.local.entity.OutboxOperation
import com.ping.messenger.data.local.entity.ReceiptType
import com.ping.messenger.domain.model.CallDirection
import com.ping.messenger.domain.model.CallOutcome
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.TransferState

/**
 * Enums are persisted by name rather than ordinal.
 *
 * Ordinals are compact but brittle: reordering an enum silently rewrites the meaning of every
 * stored row. Names cost a few bytes and make the database readable in a SQLite browser, which
 * is worth far more when diagnosing a sync bug.
 *
 * Unknown names decode to a documented fallback so a row written by a newer build cannot crash
 * an older one.
 */
class Converters {

    @TypeConverter fun messageKindToString(value: MessageKind): String = value.name

    @TypeConverter fun stringToMessageKind(value: String): MessageKind =
        enumOrDefault(value, MessageKind.TEXT)

    @TypeConverter fun messageStatusToString(value: MessageStatus): String = value.name

    @TypeConverter fun stringToMessageStatus(value: String): MessageStatus =
        enumOrDefault(value, MessageStatus.SENT)

    @TypeConverter fun conversationTypeToString(value: ConversationType): String = value.name

    @TypeConverter fun stringToConversationType(value: String): ConversationType =
        enumOrDefault(value, ConversationType.DIRECT)

    @TypeConverter fun transferStateToString(value: TransferState): String = value.name

    @TypeConverter fun stringToTransferState(value: String): TransferState =
        enumOrDefault(value, TransferState.IDLE)

    @TypeConverter fun groupRoleToString(value: GroupRole): String = value.name

    @TypeConverter fun stringToGroupRole(value: String): GroupRole =
        enumOrDefault(value, GroupRole.MEMBER)

    @TypeConverter fun groupPermissionToString(value: GroupPermission): String = value.name

    @TypeConverter fun stringToGroupPermission(value: String): GroupPermission =
        enumOrDefault(value, GroupPermission.EVERYONE)

    @TypeConverter fun statusKindToString(value: StatusKind): String = value.name

    @TypeConverter fun stringToStatusKind(value: String): StatusKind =
        enumOrDefault(value, StatusKind.TEXT)

    @TypeConverter fun callDirectionToString(value: CallDirection): String = value.name

    @TypeConverter fun stringToCallDirection(value: String): CallDirection =
        enumOrDefault(value, CallDirection.INCOMING)

    @TypeConverter fun callOutcomeToString(value: CallOutcome): String = value.name

    @TypeConverter fun stringToCallOutcome(value: String): CallOutcome =
        enumOrDefault(value, CallOutcome.FAILED)

    @TypeConverter fun receiptTypeToString(value: ReceiptType): String = value.name

    @TypeConverter fun stringToReceiptType(value: String): ReceiptType =
        enumOrDefault(value, ReceiptType.DELIVERED)

    @TypeConverter fun outboxOperationToString(value: OutboxOperation): String = value.name

    @TypeConverter fun stringToOutboxOperation(value: String): OutboxOperation =
        enumOrDefault(value, OutboxOperation.SEND_MESSAGE)

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
