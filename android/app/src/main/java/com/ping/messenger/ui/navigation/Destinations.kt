package com.ping.messenger.ui.navigation

import android.net.Uri

/**
 * Every screen the app can navigate to, as typed route builders.
 *
 * Routes are built through these functions rather than string-concatenated at call sites, so an
 * argument can never be forgotten or mis-encoded — a group name containing a `/` used to be the
 * classic way to break a Compose navigation graph.
 */
object Routes {

    // Top-level graphs
    const val AUTH_GRAPH = "auth"
    const val MAIN_GRAPH = "main"

    // Auth
    const val WELCOME = "welcome"
    const val SIGN_IN = "sign_in"
    const val SIGN_UP = "sign_up"
    const val FORGOT_PASSWORD = "forgot_password"
    const val VERIFY_EMAIL = "verify_email/{email}"
    fun verifyEmail(email: String) = "verify_email/${email.encoded()}"

    // Tabs
    const val CHATS = "chats"
    const val STATUS = "status"
    const val CALLS = "calls"
    const val SETTINGS = "settings"

    // Chats
    const val ARCHIVED = "chats/archived"
    const val SEARCH = "search"
    const val NEW_CHAT = "chats/new"
    const val NEW_GROUP = "chats/new_group"
    const val FOLDERS = "chats/folders"

    const val CONVERSATION = "conversation/{conversationId}?messageId={messageId}"
    fun conversation(conversationId: String, focusMessageId: String? = null): String =
        "conversation/${conversationId.encoded()}?messageId=${focusMessageId.orEmpty().encoded()}"

    const val CONVERSATION_INFO = "conversation/{conversationId}/info"
    fun conversationInfo(conversationId: String) = "conversation/${conversationId.encoded()}/info"

    const val CONVERSATION_SEARCH = "conversation/{conversationId}/search"
    fun conversationSearch(conversationId: String) = "conversation/${conversationId.encoded()}/search"

    const val CONVERSATION_MEDIA = "conversation/{conversationId}/media"
    fun conversationMedia(conversationId: String) = "conversation/${conversationId.encoded()}/media"

    const val CONVERSATION_NOTES = "conversation/{conversationId}/notes"
    fun conversationNotes(conversationId: String) = "conversation/${conversationId.encoded()}/notes"

    const val WALLPAPER = "conversation/{conversationId}/wallpaper"
    fun wallpaper(conversationId: String) = "conversation/${conversationId.encoded()}/wallpaper"

    const val SCHEDULED = "conversation/{conversationId}/scheduled"
    fun scheduled(conversationId: String) = "conversation/${conversationId.encoded()}/scheduled"

    const val FORWARD = "forward/{messageIds}"
    fun forward(messageIds: List<String>) = "forward/${messageIds.joinToString(",").encoded()}"

    const val MESSAGE_INFO = "message/{messageId}/info"
    fun messageInfo(messageId: String) = "message/${messageId.encoded()}/info"

    // Groups
    const val GROUP_INFO = "group/{conversationId}"
    fun groupInfo(conversationId: String) = "group/${conversationId.encoded()}"

    const val GROUP_MEMBERS = "group/{conversationId}/members"
    fun groupMembers(conversationId: String) = "group/${conversationId.encoded()}/members"

    const val GROUP_ADD_MEMBERS = "group/{conversationId}/add"
    fun groupAddMembers(conversationId: String) = "group/${conversationId.encoded()}/add"

    const val GROUP_PERMISSIONS = "group/{conversationId}/permissions"
    fun groupPermissions(conversationId: String) = "group/${conversationId.encoded()}/permissions"

    const val GROUP_INVITE = "group/{conversationId}/invite"
    fun groupInvite(conversationId: String) = "group/${conversationId.encoded()}/invite"

    // People
    const val CONTACTS = "contacts"
    const val BLOCKED = "contacts/blocked"
    const val QR_CODE = "contacts/qr"
    const val SCAN_QR = "contacts/scan"

    const val PROFILE = "profile/{userId}"
    fun profile(userId: String) = "profile/${userId.encoded()}"

    const val MY_PROFILE = "profile/me"
    const val EDIT_PROFILE = "profile/me/edit"
    const val STARRED = "starred"

    // Status
    const val STATUS_COMPOSER = "status/compose"
    const val STATUS_VIEWER = "status/view/{authorId}"
    fun statusViewer(authorId: String) = "status/view/${authorId.encoded()}"

    // Media
    const val MEDIA_VIEWER = "media/{attachmentId}"
    fun mediaViewer(attachmentId: String) = "media/${attachmentId.encoded()}"

    // Settings
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_DEVICES = "settings/devices"
    const val SETTINGS_CHATS = "settings/chats"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_STORAGE = "settings/storage"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_LICENSES = "settings/licenses"

    // Arg keys
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_MESSAGE_ID = "messageId"
    const val ARG_MESSAGE_IDS = "messageIds"
    const val ARG_USER_ID = "userId"
    const val ARG_AUTHOR_ID = "authorId"
    const val ARG_ATTACHMENT_ID = "attachmentId"
    const val ARG_EMAIL = "email"
}

/**
 * Route arguments are URL-encoded.
 *
 * Ids are UUIDs today, but emails, usernames and folder names all reach routes too, and a `/`
 * or `?` in any of them would silently rewrite the destination.
 */
private fun String.encoded(): String = Uri.encode(this)
