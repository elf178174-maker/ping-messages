package com.ping.messenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.ui.theme.PingTheme

/**
 * A search input that behaves the way people expect a search box to: it takes focus when it
 * appears, offers a clear button only once there is something to clear, and closes the keyboard
 * on submit.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_hint),
    autoFocus: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                onSubmit?.invoke()
            },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * The standard form field. Validation errors are rendered as supporting text and announced,
 * rather than as a toast that vanishes before it is read.
 */
@Composable
fun PingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    successText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            isError = errorText != null,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { keyboard?.hide(); onImeAction?.invoke() },
                onNext = { onImeAction?.invoke() },
                onGo = { keyboard?.hide(); onImeAction?.invoke() },
            ),
            supportingText = when {
                errorText != null -> {
                    { Text(errorText, color = MaterialTheme.colorScheme.error) }
                }
                successText != null -> {
                    { Text(successText, color = PingTheme.colors.success) }
                }
                supportingText != null -> {
                    { Text(supportingText) }
                }
                else -> null
            },
        )
    }
}

/** A password field with a reveal toggle. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    showStrength: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }

    Column(modifier) {
        PingTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            errorText = errorText,
            supportingText = supportingText,
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            onImeAction = onImeAction,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
            },
        )

        if (showStrength && value.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PasswordStrengthBar(value)
        }
    }
}

/**
 * A four-step password strength meter.
 *
 * Scored on length and character variety only. It is a nudge, not a gate — the actual minimum
 * is enforced in [com.ping.messenger.feature.auth.AuthValidation], and server-side too.
 */
@Composable
fun PasswordStrengthBar(password: String, modifier: Modifier = Modifier) {
    val score = remember(password) { passwordStrength(password) }
    val (label, color) = when (score) {
        0, 1 -> stringResource(R.string.auth_strength_weak) to PingTheme.colors.danger
        2 -> stringResource(R.string.auth_strength_fair) to PingTheme.colors.warning
        3 -> stringResource(R.string.auth_strength_good) to PingTheme.colors.success
        else -> stringResource(R.string.auth_strength_strong) to PingTheme.colors.success
    }
    val announcement = stringResource(R.string.auth_password_strength, label)

    Column(modifier.fillMaxWidth().semantics { contentDescription = announcement }) {
        LinearProgressIndicator(
            progress = { (score + 1) / 4f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = announcement,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 0..3. Pure function, unit-tested in AuthValidationTest. */
fun passwordStrength(password: String): Int {
    if (password.length < 8) return 0
    var score = 0
    if (password.length >= 12) score++
    if (password.length >= 16) score++
    val classes = listOf(
        password.any { it.isLowerCase() },
        password.any { it.isUpperCase() },
        password.any { it.isDigit() },
        password.any { !it.isLetterOrDigit() },
    ).count { it }
    if (classes >= 2) score++
    if (classes >= 3) score++
    return score.coerceIn(0, 3)
}
