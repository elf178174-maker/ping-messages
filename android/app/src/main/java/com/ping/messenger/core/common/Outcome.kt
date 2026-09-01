package com.ping.messenger.core.common

import kotlinx.coroutines.CancellationException

/**
 * The result of an operation that can fail in a way the user should hear about.
 *
 * Named [Outcome] rather than `Result` so it never shadows [kotlin.Result], and typed against
 * [AppError] rather than [Throwable] so a caller cannot forget that failures here are already
 * classified.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Success) action(value)
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Failure) action(error)
}

fun <T> Outcome<T>.getOrElse(fallback: T): T = getOrNull() ?: fallback

/**
 * Runs [block], converting any throw into [Outcome.Failure].
 *
 * Cancellation is re-thrown rather than captured, so a coroutine that is being torn down does
 * not report itself as a user-visible failure.
 */
inline fun <T> runCatchingApp(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Outcome.Failure(e.toAppError())
}

suspend inline fun <T> runCatchingAppSuspend(crossinline block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Outcome.Failure(e.toAppError())
}
