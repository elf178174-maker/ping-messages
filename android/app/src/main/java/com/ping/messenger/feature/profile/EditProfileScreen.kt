package com.ping.messenger.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.feature.auth.AuthValidation
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.PingTextField

/**
 * Editing your own profile.
 *
 * The username field validates locally before submitting, because a rejected username after a
 * round trip is a much worse experience than an inline hint while typing. The phone-number row
 * is intentionally read-only and explains that the number is never shown to anyone.
 */
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.pickAvatar(it.toString()) } }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditProfileEvent.Saved -> {
                    snackbar.showSnackbar(context.getString(R.string.profile_saved))
                    onBack()
                }
                is EditProfileEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Box {
                Avatar(
                    name = state.displayName,
                    photoUrl = state.avatarPath ?: state.avatarUrl,
                    seed = state.username,
                    size = 108.dp,
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.profile_change_photo),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            PingTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = stringResource(R.string.auth_display_name),
                errorText = state.displayNameError,
            )
            Spacer(Modifier.height(14.dp))
            PingTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = stringResource(R.string.auth_username),
                supportingText = stringResource(R.string.auth_username_hint),
                errorText = state.usernameError,
            )
            Spacer(Modifier.height(14.dp))
            PingTextField(
                value = state.about,
                onValueChange = viewModel::onAboutChange,
                label = stringResource(R.string.profile_about),
                placeholder = stringResource(R.string.profile_about_hint),
                supportingText = "${state.about.length}/${AuthValidation.MAX_ABOUT_LENGTH}",
                singleLine = false,
                maxLines = 3,
                imeAction = ImeAction.Done,
                onImeAction = viewModel::save,
            )

            Spacer(Modifier.height(14.dp))
            PingTextField(
                value = state.phoneNumber.ifBlank { stringResource(R.string.profile_phone_hidden) },
                onValueChange = { },
                label = stringResource(R.string.profile_phone),
                supportingText = stringResource(R.string.profile_phone_hidden),
                enabled = false,
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
