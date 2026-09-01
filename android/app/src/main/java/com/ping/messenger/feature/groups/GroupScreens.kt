package com.ping.messenger.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.PingTextField
import com.ping.messenger.ui.components.SearchField
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.components.SingleChoiceDialog

/**
 * Creating a group.
 *
 * One screen rather than the two-step "pick people, then name it" flow: on a phone the member
 * list and the name field fit together, and seeing both at once makes it obvious what is being
 * created.
 */
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupEvent.Created -> onCreated(event.conversationId)
                is GroupEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                is GroupEvent.Message -> snackbar.showSnackbar(event.text)
                GroupEvent.Left -> Unit
            }
        }
    }

    val filtered = remember(state.contacts, query) {
        if (query.isBlank()) {
            state.contacts
        } else {
            state.contacts.filter {
                it.displayName.contains(query, true) || it.username.contains(query, true)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.group_new_title))
                        if (selected.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.group_member_count, selected.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            if (name.isNotBlank() && selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.create(name.trim(), description.trim(), selected.toList()) },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_create)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                PingTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = stringResource(R.string.group_name),
                )
                Spacer(Modifier.height(10.dp))
                PingTextField(
                    value = description,
                    onValueChange = { description = it.take(280) },
                    label = stringResource(R.string.group_description),
                    placeholder = stringResource(R.string.group_description_hint),
                    singleLine = false,
                    maxLines = 3,
                    imeAction = ImeAction.Done,
                )
            }

            SectionHeader(stringResource(R.string.group_add_members))
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.contacts_search_hint),
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(filtered, key = { it.id }) { user ->
                    val isSelected = user.id in selected
                    PersonRow(
                        name = user.displayName,
                        subtitle = user.handle,
                        avatarUrl = user.avatarUrl,
                        seed = user.id,
                        selected = isSelected,
                        onClick = {
                            selected = if (isSelected) selected - user.id else selected + user.id
                        },
                        trailing = {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                        },
                    )
                }
            }
        }
    }
}

/** Group info: identity, members, permissions, invite link, leave. */
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onAddMembers: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var leaveOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                GroupEvent.Left -> onLeft()
                is GroupEvent.Message -> snackbar.showSnackbar(event.text)
                is GroupEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                is GroupEvent.Created -> Unit
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_new_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val group = state.group
        if (group == null) {
            Box(Modifier.padding(padding).fillMaxSize()) { LoadingState() }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Avatar(
                name = group.name,
                photoUrl = group.avatarUrl,
                seed = group.id,
                size = 104.dp,
                isGroup = true,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.group_member_count, group.memberCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (group.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = group.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            SettingsDivider()

            if (group.canEditInfo) {
                SettingsRow(
                    title = stringResource(R.string.action_edit),
                    icon = Icons.Default.Groups,
                    onClick = { editOpen = true },
                )
            }
            SettingsRow(
                title = stringResource(R.string.group_members),
                value = group.memberCount.toString(),
                icon = Icons.Default.Groups,
                onClick = onOpenMembers,
            )
            if (group.canAddMembers) {
                SettingsRow(
                    title = stringResource(R.string.group_add_members),
                    icon = Icons.Default.Add,
                    onClick = onAddMembers,
                )
            }
            SettingsRow(
                title = stringResource(R.string.group_media_shared),
                onClick = onOpenMedia,
            )
            if (group.isAdmin) {
                SettingsRow(
                    title = stringResource(R.string.group_permissions),
                    icon = Icons.Default.Shield,
                    onClick = onOpenPermissions,
                )
                SettingsRow(
                    title = stringResource(R.string.group_invite_link),
                    icon = Icons.Default.Link,
                    onClick = onOpenInvite,
                )
            }

            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.group_leave),
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                destructive = true,
                onClick = { leaveOpen = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (leaveOpen) {
        ConfirmDialog(
            title = stringResource(R.string.group_leave),
            body = stringResource(R.string.group_leave_confirm),
            confirmLabel = stringResource(R.string.group_leave),
            destructive = true,
            onConfirm = { leaveOpen = false; viewModel.leave() },
            onDismiss = { leaveOpen = false },
        )
    }

    if (editOpen) {
        var draftName by remember { mutableStateOf(state.group?.name.orEmpty()) }
        var draftDescription by remember { mutableStateOf(state.group?.description.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text(stringResource(R.string.action_edit)) },
            text = {
                Column {
                    PingTextField(
                        value = draftName,
                        onValueChange = { draftName = it.take(64) },
                        label = stringResource(R.string.group_name),
                    )
                    Spacer(Modifier.height(10.dp))
                    PingTextField(
                        value = draftDescription,
                        onValueChange = { draftDescription = it.take(280) },
                        label = stringResource(R.string.group_description),
                        singleLine = false,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        editOpen = false
                        viewModel.updateInfo(draftName.trim(), draftDescription.trim())
                    },
                    enabled = draftName.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Member list, with admin controls for those who have them. */
@Composable
fun GroupMembersScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var actionTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_members)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val group = state.group ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(group.members, key = { it.userId }) { member ->
                PersonRow(
                    name = member.displayName.ifBlank { member.username },
                    subtitle = "@${member.username}",
                    avatarUrl = member.avatarUrl,
                    seed = member.userId,
                    badge = when (member.role) {
                        GroupRole.OWNER -> stringResource(R.string.group_created_by, "")
                            .trim()
                            .ifBlank { "Owner" }
                        GroupRole.ADMIN -> stringResource(R.string.group_admin_badge)
                        GroupRole.MEMBER -> null
                    },
                    onClick = {
                        if (group.isAdmin && member.role != GroupRole.OWNER) {
                            actionTarget = member.userId
                        } else {
                            onOpenProfile(member.userId)
                        }
                    },
                )
            }
        }
    }

    actionTarget?.let { userId ->
        val member = state.group?.members?.firstOrNull { it.userId == userId }
        com.ping.messenger.ui.components.PingBottomSheet(
            title = member?.displayName,
            actions = listOfNotNull(
                com.ping.messenger.ui.components.SheetAction(
                    label = stringResource(
                        if (member?.role == GroupRole.ADMIN) {
                            R.string.group_revoke_admin
                        } else {
                            R.string.group_make_admin
                        },
                    ),
                    icon = Icons.Default.Shield,
                ) {
                    viewModel.setRole(
                        userId,
                        if (member?.role == GroupRole.ADMIN) GroupRole.MEMBER else GroupRole.ADMIN,
                    )
                    actionTarget = null
                },
                com.ping.messenger.ui.components.SheetAction(
                    label = stringResource(R.string.group_remove_member),
                    icon = Icons.Default.PersonRemove,
                    destructive = true,
                ) {
                    viewModel.removeMember(userId)
                    actionTarget = null
                },
            ),
            onDismiss = { actionTarget = null },
        )
    }
}

/** Who may post, edit group info, and add members. */
@Composable
fun GroupPermissionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PermissionSlot?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_permissions)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val group = state.group ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize()) {
            PermissionSlot.entries.forEach { slot ->
                SettingsRow(
                    title = stringResource(slot.labelRes),
                    value = stringResource(slot.get(group).labelRes()),
                    onClick = { editing = slot },
                )
            }
        }
    }

    editing?.let { slot ->
        val group = state.group ?: return@let
        SingleChoiceDialog(
            title = stringResource(slot.labelRes),
            options = GroupPermission.entries,
            selected = slot.get(group),
            labelFor = { stringResource(it.labelRes()) },
            onSelect = { value ->
                viewModel.setPermissions(
                    send = if (slot == PermissionSlot.SEND) value else group.sendPermission,
                    editInfo = if (slot == PermissionSlot.EDIT_INFO) value else group.editInfoPermission,
                    addMembers = if (slot == PermissionSlot.ADD_MEMBERS) value else group.addMembersPermission,
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

enum class PermissionSlot(
    val labelRes: Int,
    val get: (com.ping.messenger.domain.model.Group) -> GroupPermission,
) {
    SEND(R.string.group_who_can_send, { it.sendPermission }),
    EDIT_INFO(R.string.group_who_can_edit, { it.editInfoPermission }),
    ADD_MEMBERS(R.string.group_who_can_add, { it.addMembersPermission }),
}

private fun GroupPermission.labelRes(): Int = when (this) {
    GroupPermission.EVERYONE -> R.string.group_perm_everyone
    GroupPermission.ADMINS_ONLY -> R.string.group_perm_admins
}

/** The shareable invite link, with a reset that invalidates every prior link. */
@Composable
fun GroupInviteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var resetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadInviteLink() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_invite_link)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
            Text(
                text = stringResource(R.string.group_invite_link_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            state.inviteLink?.let { link ->
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clipboard.setText(AnnotatedString(link)) }
                        .padding(vertical = 12.dp),
                )
            } ?: LoadingState(Modifier.height(80.dp))

            Spacer(Modifier.height(12.dp))
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.action_copy),
                onClick = {
                    state.inviteLink?.let { clipboard.setText(AnnotatedString(it)) }
                },
            )
            SettingsRow(
                title = stringResource(R.string.group_reset_link),
                destructive = true,
                onClick = { resetOpen = true },
            )
        }
    }

    if (resetOpen) {
        ConfirmDialog(
            title = stringResource(R.string.group_reset_link),
            body = "The current link stops working immediately. Anyone who already has it will " +
                "no longer be able to join.",
            destructive = true,
            onConfirm = { resetOpen = false; viewModel.resetInviteLink() },
            onDismiss = { resetOpen = false },
        )
    }
}
