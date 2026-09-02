package com.ping.messenger.feature.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ping.messenger.core.call.CallManager
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.call.CallState
import com.ping.messenger.R
import com.ping.messenger.core.notification.CallNotifier
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.ui.theme.PingTheme
import com.ping.messenger.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The full-screen call surface.
 *
 * A separate activity from [com.ping.messenger.MainActivity] because it has to be able to
 * appear over the lock screen for an incoming call, which requires window flags and a launch
 * mode that would be wrong for the rest of the app.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callManager: CallManager
    @Inject lateinit var callNotifier: CallNotifier
    @Inject lateinit var conversations: ConversationRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val micGranted = granted[Manifest.permission.RECORD_AUDIO] == true
        if (!micGranted) {
            // A call without a microphone is not a call; there is nothing useful to fall
            // back to, so the screen closes rather than showing a broken call.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the display for an incoming call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // The screen must not sleep during a call.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        requestPermissionsIfNeeded(isVideo)
        handleAction(intent, callId, isVideo)

        setContent {
            PingTheme(themeMode = ThemeMode.DARK) {
                val state by callManager.state.collectAsState()
                val controls by callManager.controls.collectAsState()

                CallScreen(
                    state = state,
                    controls = controls,
                    eglContext = callManager.eglContext,
                    localTrack = callManager.localTrack,
                    remoteTrack = callManager.remoteTrack,
                    onAnswer = { lifecycleScope.launch { callManager.answer() } },
                    onDecline = {
                        callManager.decline()
                        finish()
                    },
                    onHangUp = {
                        callManager.hangUp()
                        finish()
                    },
                    onToggleMic = callManager::toggleMicrophone,
                    onToggleCamera = callManager::toggleCamera,
                    onSwitchCamera = callManager::switchCamera,
                    onToggleSpeaker = callManager::toggleSpeaker,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun handleAction(intent: Intent, callId: String?, isVideo: Boolean) {
        when (intent.action) {
            ACTION_OUTGOING -> placeCall(
                conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                peerId = intent.getStringExtra(EXTRA_PEER_ID),
                isVideo = isVideo,
            )
            ACTION_ANSWER -> lifecycleScope.launch { callManager.answer() }
            ACTION_DECLINE -> {
                callManager.decline()
                callId?.let(callNotifier::cancel)
                finish()
            }
            ACTION_HANG_UP -> {
                callManager.hangUp()
                callId?.let(callNotifier::cancel)
                finish()
            }
            else -> callId?.let(callNotifier::cancel)
        }
    }

    /**
     * Places an outgoing call, from either a conversation or a person.
     *
     * Both entry points exist because both are real: the chat and the call history have a
     * conversation id, while a contact's profile has a user id and may not have a chat with
     * them yet. The missing half is resolved here rather than in three different callers.
     *
     * A group conversation has no single peer, so a group call is refused with a reason on the
     * call screen rather than started against an empty callee list.
     */
    private fun placeCall(conversationId: String?, peerId: String?, isVideo: Boolean) {
        lifecycleScope.launch {
            val resolved = when {
                !conversationId.isNullOrBlank() -> {
                    val conversation = conversations.observeConversation(conversationId).first()
                    conversationId to conversation?.otherUserId
                }

                !peerId.isNullOrBlank() -> {
                    // Opening the chat is what allocates the conversation, and it is what the
                    // user would have done next anyway.
                    when (val opened = conversations.openDirectChat(peerId)) {
                        is Outcome.Success -> opened.value to peerId
                        is Outcome.Failure -> {
                            callManager.failWith(
                                opened.error.message ?: getString(R.string.error_generic),
                            )
                            return@launch
                        }
                    }
                }

                else -> {
                    finish()
                    return@launch
                }
            }

            val (id, peer) = resolved
            if (peer == null) {
                callManager.failWith(getString(R.string.calls_group_unsupported))
                return@launch
            }
            callManager.startCall(id, peer, isVideo)
        }
    }

    private fun requestPermissionsIfNeeded(isVideo: Boolean) {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (isVideo && !hasPermission(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        // Only reset once the call is actually over; a configuration change must not tear
        // down a live call.
        if (callManager.state.value is CallState.Ended || callManager.state.value is CallState.Failed) {
            callManager.reset()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_IS_VIDEO = "isVideo"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_PEER_ID = "peerId"

        const val ACTION_INCOMING = "com.ping.messenger.call.INCOMING"
        const val ACTION_ANSWER = "com.ping.messenger.call.ANSWER"
        const val ACTION_DECLINE = "com.ping.messenger.call.DECLINE"
        const val ACTION_HANG_UP = "com.ping.messenger.call.HANG_UP"
        const val ACTION_OPEN = "com.ping.messenger.call.OPEN"
        const val ACTION_OUTGOING = "com.ping.messenger.call.OUTGOING"

        /** Starts a call from a conversation - the chat screen and the call history. */
        fun outgoing(context: Context, conversationId: String, isVideo: Boolean): Intent =
            Intent(context, CallActivity::class.java)
                .setAction(ACTION_OUTGOING)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_IS_VIDEO, isVideo)

        /** Starts a call from a person - a contact profile, where there may be no chat yet. */
        fun outgoingToUser(context: Context, userId: String, isVideo: Boolean): Intent =
            Intent(context, CallActivity::class.java)
                .setAction(ACTION_OUTGOING)
                .putExtra(EXTRA_PEER_ID, userId)
                .putExtra(EXTRA_IS_VIDEO, isVideo)
    }
}
