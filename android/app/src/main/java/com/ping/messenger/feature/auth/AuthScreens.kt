package com.ping.messenger.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.PasswordField
import com.ping.messenger.ui.components.PingTextField
import com.ping.messenger.ui.theme.PingTheme

/**
 * The authentication screens.
 *
 * All of them share [AuthViewModel] and this file's [AuthScaffold], so the visual rhythm —
 * logo, heading, form, primary action, secondary link — is identical across the flow. Every
 * form scrolls and applies `imePadding`, which is what stops the keyboard from covering the
 * submit button on a short screen.
 */

@Composable
fun WelcomeScreen(
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_splash_logo),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(104.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.auth_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onSignUp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.auth_create_account), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.auth_have_account))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_terms_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AuthEvent.SignedIn -> onSignedIn()
                is AuthEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                else -> Unit
            }
        }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_sign_in),
        onBack = onBack,
        snackbar = snackbar,
        modifier = modifier,
    ) {
        PingTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            errorText = state.emailError,
            keyboardType = KeyboardType.Email,
            leadingIcon = { Icon(Icons.Default.MailOutline, null) },
        )
        Spacer(Modifier.height(14.dp))
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_password),
            errorText = state.passwordError,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::signIn,
        )

        if (state.requiresTwoStep) {
            Spacer(Modifier.height(14.dp))
            PingTextField(
                value = state.twoStepPin,
                onValueChange = viewModel::onTwoStepPinChange,
                label = stringResource(R.string.settings_two_step_pin),
                supportingText = stringResource(R.string.settings_two_step_summary),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Go,
                onImeAction = viewModel::signIn,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onForgotPassword) {
                Text(stringResource(R.string.auth_forgot_password))
            }
        }

        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = stringResource(R.string.auth_sign_in),
            enabled = state.canSubmitSignIn,
            loading = state.isSubmitting,
            onClick = viewModel::signIn,
        )

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_no_account))
        }
    }
}

@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onNeedsVerification: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.NeedsVerification -> onNeedsVerification(event.email)
                is AuthEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                else -> Unit
            }
        }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_create_account),
        onBack = onBack,
        snackbar = snackbar,
        modifier = modifier,
    ) {
        PingTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = stringResource(R.string.auth_display_name),
            errorText = state.displayNameError,
            leadingIcon = { Icon(Icons.Default.Badge, null) },
        )
        Spacer(Modifier.height(14.dp))

        PingTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = stringResource(R.string.auth_username),
            supportingText = stringResource(R.string.auth_username_hint),
            errorText = state.usernameError,
            successText = if (state.usernameStatus == UsernameStatus.AVAILABLE) {
                stringResource(R.string.profile_username_available)
            } else {
                null
            },
            leadingIcon = { Icon(Icons.Default.AlternateEmail, null) },
            trailingIcon = {
                when (state.usernameStatus) {
                    UsernameStatus.CHECKING -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    UsernameStatus.AVAILABLE -> Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = PingTheme.colors.success,
                    )
                    else -> Unit
                }
            },
        )
        Spacer(Modifier.height(14.dp))

        PingTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            errorText = state.emailError,
            keyboardType = KeyboardType.Email,
            leadingIcon = { Icon(Icons.Default.MailOutline, null) },
        )
        Spacer(Modifier.height(14.dp))

        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_password),
            errorText = state.passwordError,
            showStrength = true,
        )
        Spacer(Modifier.height(14.dp))

        PasswordField(
            value = state.confirmPassword,
            onValueChange = viewModel::onConfirmPasswordChange,
            label = stringResource(R.string.auth_confirm_password),
            errorText = state.confirmError,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::signUp,
        )

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = stringResource(R.string.auth_create_account),
            enabled = state.canSubmitSignUp,
            loading = state.isSubmitting,
            onClick = viewModel::signUp,
        )

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_have_account))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.auth_terms_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun VerifyEmailScreen(
    email: String,
    onBack: () -> Unit,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AuthEvent.SignedIn -> onVerified()
                is AuthEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                else -> Unit
            }
        }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_verify_email_title),
        onBack = onBack,
        snackbar = snackbar,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.auth_verify_email_body, email),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        PingTextField(
            value = state.verificationCode,
            onValueChange = viewModel::onCodeChange,
            label = stringResource(R.string.auth_verification_code),
            errorText = state.codeError,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Go,
            onImeAction = { viewModel.verifyEmail(email) },
        )

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = stringResource(R.string.action_continue),
            enabled = state.canSubmitCode,
            loading = state.isSubmitting,
            onClick = { viewModel.verifyEmail(email) },
        )

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { viewModel.resendCode(email) },
            enabled = state.resendCooldownSeconds == 0 && !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (state.resendCooldownSeconds > 0) {
                    stringResource(R.string.auth_resend_in, state.resendCooldownSeconds)
                } else {
                    stringResource(R.string.auth_resend_code)
                },
            )
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.ResetLinkSent ->
                    snackbar.showSnackbar(context.getString(R.string.auth_reset_sent))
                is AuthEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                else -> Unit
            }
        }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_reset_password),
        onBack = onBack,
        snackbar = snackbar,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.auth_reset_sent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PingTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            errorText = state.emailError,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::requestPasswordReset,
            leadingIcon = { Icon(Icons.Default.MailOutline, null) },
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = stringResource(R.string.auth_reset_password),
            enabled = state.email.isNotBlank() && !state.isSubmitting,
            loading = state.isSubmitting,
            onClick = viewModel::requestPasswordReset,
        )
    }
}

// ---- Shared pieces --------------------------------------------------------

@Composable
private fun AuthScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
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
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            content = content,
        )
    }
}

/**
 * The primary action button.
 *
 * While submitting it keeps its footprint and swaps the label for a spinner, rather than
 * shrinking — a button that changes size mid-tap is how double submissions happen.
 */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
