package com.ping.messenger.feature.contacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.qr.rememberQrBitmap
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.SearchField
import com.ping.messenger.ui.components.SectionHeader

/**
 * Contacts, and finding people who are not yet contacts.
 *
 * The two are one screen rather than two, because "start a chat" is the same intent whether
 * the person is already saved or has to be looked up by username first.
 */
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onScanQr: () -> Unit,
    onShowMyQr: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactsEvent.OpenConversation -> onOpenConversation(event.conversationId)
                is ContactsEvent.Message -> snackbar.showSnackbar(event.text)
                is ContactsEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.QrCodeScanner, stringResource(R.string.contacts_scan_qr))
                    }
                    IconButton(onClick = onShowMyQr) {
                        Icon(Icons.Default.QrCode2, stringResource(R.string.contacts_my_qr))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.contacts_search_hint),
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            when {
                state.isLoading -> com.ping.messenger.ui.components.LoadingState()

                state.contacts.isEmpty() && state.query.isBlank() -> EmptyState(
                    icon = Icons.Outlined.PeopleOutline,
                    title = stringResource(R.string.contacts_empty_title),
                    body = stringResource(R.string.contacts_empty_body),
                    actionLabel = stringResource(R.string.contacts_my_qr),
                    onAction = onShowMyQr,
                )

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.filteredContacts.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.contacts_title)) }
                        items(state.filteredContacts, key = { it.id }) { user ->
                            PersonRow(
                                name = user.displayName,
                                subtitle = user.about.ifBlank { user.handle },
                                avatarUrl = user.avatarUrl,
                                seed = user.id,
                                isOnline = user.presence is com.ping.messenger.domain.model.Presence.Online,
                                onClick = { viewModel.openChat(user.id) },
                            )
                        }
                    }

                    if (state.isSearching) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }

                    if (state.discoveries.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.contacts_find_by_username)) }
                        items(state.discoveries, key = { "found-${it.id}" }) { user ->
                            PersonRow(
                                name = user.displayName,
                                subtitle = user.handle,
                                avatarUrl = user.avatarUrl,
                                seed = user.id,
                                onClick = { viewModel.openChat(user.id) },
                                trailing = {
                                    TextButton(onClick = { viewModel.addContact(user.id) }) {
                                        Icon(
                                            Icons.Default.PersonAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(stringResource(R.string.action_add))
                                    }
                                },
                            )
                        }
                    }

                    if (state.notFound && !state.isSearching && state.query.isNotBlank()) {
                        item {
                            Text(
                                text = stringResource(R.string.contacts_not_found, state.query),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedContactsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_blocked)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (state.blocked.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.contacts_blocked_empty),
                    body = stringResource(R.string.contacts_empty_body),
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.blocked, key = { it.id }) { user ->
                    PersonRow(
                        name = user.displayName,
                        subtitle = user.handle,
                        avatarUrl = user.avatarUrl,
                        seed = user.id,
                        trailing = {
                            TextButton(onClick = { viewModel.unblock(user.id) }) {
                                Text(stringResource(R.string.action_unblock))
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The user's own QR code.
 *
 * Encodes `ping://user/<username>` — a username, never a phone number. That is the whole point
 * of the feature: two people can start talking without either revealing a number.
 */
@Composable
fun MyQrCodeScreen(
    username: String,
    displayName: String,
    avatarUrl: String?,
    onBack: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = "ping://user/$username"
    val qrBitmap = rememberQrBitmap(payload, sizePx = 720)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_my_qr)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.QrCodeScanner, stringResource(R.string.contacts_scan_qr))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Avatar(name = displayName, photoUrl = avatarUrl, seed = username, size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "@$username",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .size(248.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.contacts_my_qr),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.contacts_qr_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
