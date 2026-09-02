package com.ping.messenger.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.ping.messenger.domain.repository.AuthState
import com.ping.messenger.feature.auth.ForgotPasswordScreen
import com.ping.messenger.feature.auth.SignInScreen
import com.ping.messenger.feature.auth.SignUpScreen
import com.ping.messenger.feature.auth.VerifyEmailScreen
import com.ping.messenger.feature.auth.WelcomeScreen
import com.ping.messenger.feature.calls.CallsScreen
import com.ping.messenger.feature.chat.ConversationMediaScreen
import com.ping.messenger.feature.chat.ConversationScreen
import com.ping.messenger.feature.chat.MediaViewerScreen
import com.ping.messenger.feature.chat.MessageInfoScreen
import com.ping.messenger.feature.chat.StarredMessagesScreen
import com.ping.messenger.feature.chats.ArchivedChatsScreen
import com.ping.messenger.feature.chats.ChatsScreen
import com.ping.messenger.feature.chats.FoldersScreen
import com.ping.messenger.feature.chats.ForwardScreen
import com.ping.messenger.feature.contacts.BlockedContactsScreen
import com.ping.messenger.feature.contacts.ContactsScreen
import com.ping.messenger.feature.contacts.MyQrCodeRoute
import com.ping.messenger.feature.contacts.ScanQrScreen
import com.ping.messenger.feature.groups.AddMembersScreen
import com.ping.messenger.feature.groups.GroupInfoScreen
import com.ping.messenger.feature.groups.GroupInviteScreen
import com.ping.messenger.feature.groups.GroupMembersScreen
import com.ping.messenger.feature.groups.GroupPermissionsScreen
import com.ping.messenger.feature.groups.NewGroupScreen
import com.ping.messenger.feature.profile.ContactProfileScreen
import com.ping.messenger.feature.profile.EditProfileScreen
import com.ping.messenger.feature.search.SearchScreen
import com.ping.messenger.feature.settings.AboutScreen
import com.ping.messenger.feature.settings.AdvancedSettingsScreen
import com.ping.messenger.feature.settings.AppearanceSettingsScreen
import com.ping.messenger.feature.settings.BackupSettingsScreen
import com.ping.messenger.feature.settings.ChatsSettingsScreen
import com.ping.messenger.feature.settings.LicensesScreen
import com.ping.messenger.feature.settings.NotificationsSettingsScreen
import com.ping.messenger.feature.settings.StorageSettingsScreen
import com.ping.messenger.feature.settings.DevicesScreen
import com.ping.messenger.feature.settings.PrivacySettingsScreen
import com.ping.messenger.feature.settings.SecuritySettingsScreen
import com.ping.messenger.feature.settings.SettingsScreen
import com.ping.messenger.feature.status.StatusComposerScreen
import com.ping.messenger.feature.status.StatusScreen
import com.ping.messenger.feature.status.StatusViewerRoute

/**
 * The app's navigation graph.
 *
 * Two top-level graphs — `auth` and `main` — swapped by [AuthState] rather than by navigation
 * calls. That is what makes a session expiring anywhere in the app land the user on sign-in
 * with an empty back stack, without every screen having to know about it.
 *
 * Transitions are horizontal slides for push/pop and a fade for the tab roots, which matches
 * the platform's own hierarchy cue: sideways means deeper, fade means sideways-at-the-same-level.
 */
@Composable
fun PingNavHost(
    authState: AuthState,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val startDestination = when (authState) {
        is AuthState.SignedIn -> Routes.MAIN_GRAPH
        else -> Routes.AUTH_GRAPH
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { slideInFromEnd() },
        exitTransition = { fadeOut(tween(90)) },
        popEnterTransition = { fadeIn(tween(90)) },
        popExitTransition = { slideOutToEnd() },
    ) {
        authGraph(navController)
        mainGraph(navController)
    }
}

private fun AnimatedContentTransitionScope<*>.slideInFromEnd() = slideIntoContainer(
    towards = AnimatedContentTransitionScope.SlideDirection.Start,
    animationSpec = tween(240),
) + fadeIn(tween(180))

private fun AnimatedContentTransitionScope<*>.slideOutToEnd() = slideOutOfContainer(
    towards = AnimatedContentTransitionScope.SlideDirection.End,
    animationSpec = tween(240),
) + fadeOut(tween(180))

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

private fun NavGraphBuilder.authGraph(navController: NavHostController) {
    navigation(startDestination = Routes.WELCOME, route = Routes.AUTH_GRAPH) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onSignIn = { navController.navigate(Routes.SIGN_IN) },
                onSignUp = { navController.navigate(Routes.SIGN_UP) },
            )
        }

        composable(Routes.SIGN_IN) {
            SignInScreen(
                onBack = navController::popBackStack,
                onSignedIn = { navController.toMainGraph() },
                onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) },
                onCreateAccount = {
                    navController.navigate(Routes.SIGN_UP) { popUpTo(Routes.WELCOME) }
                },
            )
        }

        composable(Routes.SIGN_UP) {
            SignUpScreen(
                onBack = navController::popBackStack,
                onNeedsVerification = { email -> navController.navigate(Routes.verifyEmail(email)) },
                onSignIn = {
                    navController.navigate(Routes.SIGN_IN) { popUpTo(Routes.WELCOME) }
                },
            )
        }

        composable(
            route = Routes.VERIFY_EMAIL,
            arguments = listOf(navArgument(Routes.ARG_EMAIL) { type = NavType.StringType }),
        ) { entry ->
            VerifyEmailScreen(
                email = entry.arguments?.getString(Routes.ARG_EMAIL).orEmpty(),
                onBack = navController::popBackStack,
                onVerified = { navController.toMainGraph() },
            )
        }

        composable(Routes.FORGOT_PASSWORD) {
            ForgotPasswordScreen(onBack = navController::popBackStack)
        }
    }
}

/** Enters the main graph and clears the auth stack so back does not return to sign-in. */
private fun NavHostController.toMainGraph() {
    navigate(Routes.MAIN_GRAPH) {
        popUpTo(Routes.AUTH_GRAPH) { inclusive = true }
        launchSingleTop = true
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

private fun NavGraphBuilder.mainGraph(navController: NavHostController) {
    navigation(startDestination = Routes.CHATS, route = Routes.MAIN_GRAPH) {
        composable(Routes.CHATS) {
            ChatsScreen(
                onOpenConversation = { navController.navigate(Routes.conversation(it)) },
                onNewChat = { navController.navigate(Routes.CONTACTS) },
                onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                onOpenArchive = { navController.navigate(Routes.ARCHIVED) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenFolders = { navController.navigate(Routes.FOLDERS) },
            )
        }

        composable(Routes.STATUS) {
            StatusScreen(
                onOpenViewer = { navController.navigate(Routes.statusViewer(it)) },
                onCompose = { navController.navigate(Routes.STATUS_COMPOSER) },
            )
        }

        composable(Routes.CALLS) {
            CallsScreen(
                onStartCall = { conversationId, video ->
                    navController.navigate(Routes.conversation(conversationId))
                },
                onOpenAdvancedSettings = { navController.navigate(Routes.SETTINGS_ADVANCED) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                onOpenPrivacy = { navController.navigate(Routes.SETTINGS_PRIVACY) },
                onOpenSecurity = { navController.navigate(Routes.SETTINGS_SECURITY) },
                onOpenDevices = { navController.navigate(Routes.SETTINGS_DEVICES) },
                onOpenChats = { navController.navigate(Routes.SETTINGS_CHATS) },
                onOpenNotifications = { navController.navigate(Routes.SETTINGS_NOTIFICATIONS) },
                onOpenStorage = { navController.navigate(Routes.SETTINGS_STORAGE) },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                onOpenAdvanced = { navController.navigate(Routes.SETTINGS_ADVANCED) },
                onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                onOpenStarred = { navController.navigate(Routes.STARRED) },
                onOpenContacts = { navController.navigate(Routes.CONTACTS) },
                onOpenQrCode = { navController.navigate(Routes.QR_CODE) },
                onSignedOut = {
                    navController.navigate(Routes.AUTH_GRAPH) {
                        popUpTo(Routes.MAIN_GRAPH) { inclusive = true }
                    }
                },
            )
        }

        // ---- Conversation ---------------------------------------------------

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_MESSAGE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            ConversationScreen(
                onBack = navController::popBackStack,
                onOpenInfo = { navController.navigate(Routes.conversationInfo(it)) },
                onOpenProfile = { navController.navigate(Routes.profile(it)) },
                onStartCall = { _, _ -> navController.navigate(Routes.CALLS) },
                onForward = { ids -> navController.navigate(Routes.forward(ids)) },
                onOpenMedia = { navController.navigate(Routes.mediaViewer(it)) },
                onMessageInfo = { navController.navigate(Routes.messageInfo(it)) },
            )
        }

        composable(
            route = Routes.CONVERSATION_INFO,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) { entry ->
            // Every sub-route is built from this entry's id. Navigating to the bare route
            // template would pass the literal "{conversationId}" as the argument, and each
            // sub-screen would then load a group that does not exist.
            val conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            GroupInfoScreen(
                onBack = navController::popBackStack,
                onOpenMembers = { navController.navigate(Routes.groupMembers(conversationId)) },
                onAddMembers = { navController.navigate(Routes.groupAddMembers(conversationId)) },
                onOpenPermissions = {
                    navController.navigate(Routes.groupPermissions(conversationId))
                },
                onOpenInvite = { navController.navigate(Routes.groupInvite(conversationId)) },
                onOpenMedia = { navController.navigate(Routes.conversationMedia(conversationId)) },
                onOpenProfile = { navController.navigate(Routes.profile(it)) },
                onLeft = { navController.popBackStack(Routes.CHATS, inclusive = false) },
            )
        }

        // ---- People ---------------------------------------------------------

        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = navController::popBackStack,
                onOpenConversation = { id ->
                    navController.navigate(Routes.conversation(id)) {
                        popUpTo(Routes.CONTACTS) { inclusive = true }
                    }
                },
                onOpenProfile = { navController.navigate(Routes.profile(it)) },
                onScanQr = { navController.navigate(Routes.SCAN_QR) },
                onShowMyQr = { navController.navigate(Routes.QR_CODE) },
            )
        }

        composable(Routes.BLOCKED) {
            BlockedContactsScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.PROFILE,
            arguments = listOf(navArgument(Routes.ARG_USER_ID) { type = NavType.StringType }),
        ) { entry ->
            ContactProfileScreen(
                userId = entry.arguments?.getString(Routes.ARG_USER_ID).orEmpty(),
                onBack = navController::popBackStack,
                onMessage = { navController.navigate(Routes.conversation(it)) },
                onCall = { _, _ -> navController.navigate(Routes.CALLS) },
                onOpenMedia = { navController.navigate(Routes.conversationMedia(it)) },
            )
        }

        composable(Routes.EDIT_PROFILE) {
            EditProfileScreen(onBack = navController::popBackStack)
        }

        // ---- Groups ---------------------------------------------------------

        composable(Routes.NEW_GROUP) {
            NewGroupScreen(
                onBack = navController::popBackStack,
                onCreated = { id ->
                    navController.navigate(Routes.conversation(id)) {
                        popUpTo(Routes.CHATS)
                    }
                },
            )
        }

        composable(
            route = Routes.GROUP_MEMBERS,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) {
            GroupMembersScreen(
                onBack = navController::popBackStack,
                onOpenProfile = { navController.navigate(Routes.profile(it)) },
            )
        }

        composable(
            route = Routes.GROUP_ADD_MEMBERS,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) {
            AddMembersScreen(
                onBack = navController::popBackStack,
                onAdded = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.GROUP_PERMISSIONS,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) {
            GroupPermissionsScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.GROUP_INVITE,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) {
            GroupInviteScreen(onBack = navController::popBackStack)
        }

        // ---- Search ---------------------------------------------------------

        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = navController::popBackStack,
                onOpenConversation = { id, messageId ->
                    navController.navigate(Routes.conversation(id, messageId))
                },
                onOpenProfile = { navController.navigate(Routes.profile(it)) },
            )
        }

        // ---- Settings sub-screens -------------------------------------------

        composable(Routes.SETTINGS_PRIVACY) {
            PrivacySettingsScreen(
                onBack = navController::popBackStack,
                onOpenBlocked = { navController.navigate(Routes.BLOCKED) },
            )
        }

        composable(Routes.SETTINGS_SECURITY) {
            SecuritySettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_DEVICES) {
            DevicesScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_CHATS) {
            ChatsSettingsScreen(
                onBack = navController::popBackStack,
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
            )
        }

        composable(Routes.SETTINGS_NOTIFICATIONS) {
            NotificationsSettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_STORAGE) {
            StorageSettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_BACKUP) {
            BackupSettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_ADVANCED) {
            AdvancedSettingsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS_ABOUT) {
            AboutScreen(
                onBack = navController::popBackStack,
                onOpenLicenses = { navController.navigate(Routes.SETTINGS_LICENSES) },
            )
        }

        composable(Routes.SETTINGS_LICENSES) {
            LicensesScreen(onBack = navController::popBackStack)
        }

        // ---- Chats: archive, folders, forwarding -----------------------------

        composable(Routes.ARCHIVED) {
            ArchivedChatsScreen(
                onBack = navController::popBackStack,
                onOpenConversation = { navController.navigate(Routes.conversation(it)) },
            )
        }

        composable(Routes.FOLDERS) {
            FoldersScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.FORWARD,
            arguments = listOf(navArgument(Routes.ARG_MESSAGE_IDS) { type = NavType.StringType }),
        ) { entry ->
            val ids = entry.arguments
                ?.getString(Routes.ARG_MESSAGE_IDS)
                .orEmpty()
                .split(",")
                .filter { it.isNotBlank() }
            ForwardScreen(
                messageIds = ids,
                onBack = navController::popBackStack,
                // The result is reported by popping back to where the forward started; the
                // conversation screen is what shows the confirmation.
                onForwarded = { navController.popBackStack() },
            )
        }

        // ---- Messages: starred, info, media -----------------------------------

        composable(Routes.STARRED) {
            StarredMessagesScreen(
                onBack = navController::popBackStack,
                onOpenMessage = { conversationId, messageId ->
                    navController.navigate(Routes.conversation(conversationId, messageId))
                },
            )
        }

        composable(
            route = Routes.MESSAGE_INFO,
            arguments = listOf(navArgument(Routes.ARG_MESSAGE_ID) { type = NavType.StringType }),
        ) {
            MessageInfoScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.CONVERSATION_MEDIA,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) {
            ConversationMediaScreen(
                onBack = navController::popBackStack,
                onOpenAttachment = { navController.navigate(Routes.mediaViewer(it)) },
            )
        }

        composable(
            route = Routes.MEDIA_VIEWER,
            arguments = listOf(navArgument(Routes.ARG_ATTACHMENT_ID) { type = NavType.StringType }),
        ) {
            MediaViewerScreen(onBack = navController::popBackStack)
        }

        // ---- Status ----------------------------------------------------------

        composable(Routes.STATUS_COMPOSER) {
            StatusComposerScreen(
                onBack = navController::popBackStack,
                onPosted = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.STATUS_VIEWER,
            arguments = listOf(navArgument(Routes.ARG_AUTHOR_ID) { type = NavType.StringType }),
        ) { entry ->
            StatusViewerRoute(
                authorId = entry.arguments?.getString(Routes.ARG_AUTHOR_ID).orEmpty(),
                onClose = navController::popBackStack,
            )
        }

        // ---- Contact codes ---------------------------------------------------

        composable(Routes.QR_CODE) {
            MyQrCodeRoute(
                onBack = navController::popBackStack,
                onScan = { navController.navigate(Routes.SCAN_QR) },
            )
        }

        composable(Routes.SCAN_QR) {
            ScanQrScreen(
                onBack = navController::popBackStack,
                onOpenConversation = { id ->
                    navController.navigate(Routes.conversation(id)) {
                        // Scanning is a means to an end: coming back from the conversation
                        // should not put the camera on screen again.
                        popUpTo(Routes.SCAN_QR) { inclusive = true }
                    }
                },
            )
        }
    }
}
