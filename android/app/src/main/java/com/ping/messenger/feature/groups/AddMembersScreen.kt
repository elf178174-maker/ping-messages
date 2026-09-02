package com.ping.messenger.feature.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.SearchField

/**
 * Adds contacts to an existing group.
 *
 * People already in the group are filtered out rather than shown greyed out: a list of
 * unselectable rows is noise, and the member list is one screen away for anyone who wants it.
 * Whether the current user may add anyone at all is the group's `canAddMembers` permission,
 * which the server enforces again on the request.
 */
@Composable
fun AddMembersScreen(
    onBack: () -> Unit,
    onAdded: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupEvent.Message -> onAdded(event.text)
                else -> Unit
            }
        }
    }

    val existing = state.group?.members?.map { it.userId }?.toSet().orEmpty()
    val candidates = state.contacts
        .filterNot { it.id in existing }
        .filter { query.isBlank() || it.matches(query) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selected.isEmpty()) {
                            stringResource(R.string.add_members_title)
                        } else {
                            stringResource(R.string.add_members_selected, selected.size)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.addMembers(selected.toList()) },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.contacts_search_hint),
            )
            if (candidates.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.PersonAddAlt,
                    title = stringResource(R.string.contacts_empty_title),
                    body = stringResource(R.string.contacts_empty_body),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(candidates, key = { it.id }) { user ->
                        PersonRow(
                            name = user.displayName,
                            subtitle = "@" + user.username,
                            avatarUrl = user.avatarUrl,
                            seed = user.id,
                            selected = user.id in selected,
                            onClick = {
                                selected = if (user.id in selected) {
                                    selected - user.id
                                } else {
                                    selected + user.id
                                }
                            },
                            trailing = {
                                Checkbox(checked = user.id in selected, onCheckedChange = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun com.ping.messenger.domain.model.User.matches(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) ||
        username.contains(query, ignoreCase = true)
