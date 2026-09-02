package com.ping.messenger.core.common

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves string resources where there is no composition to resolve them in.
 *
 * View-models produce user-visible confirmations ("Blocked", "Members added") and had been
 * building them from literals, which puts a slice of the app's copy outside `strings.xml` where
 * it cannot be translated or reviewed with the rest.
 *
 * A provider rather than an injected [Context] because this is the only thing a view-model
 * legitimately needs a context for, and narrowing it to one method makes that visible: nothing
 * here can reach for a window, a database, or the filesystem.
 */
@Singleton
class StringProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    operator fun get(@StringRes id: Int): String = context.getString(id)

    operator fun get(@StringRes id: Int, vararg formatArgs: Any): String =
        context.getString(id, *formatArgs)
}
