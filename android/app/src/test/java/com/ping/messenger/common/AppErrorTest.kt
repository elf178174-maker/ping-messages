package com.ping.messenger.common

import com.google.common.truth.Truth.assertThat
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.flatMap
import com.ping.messenger.core.common.fromHttpCode
import com.ping.messenger.core.common.getOrElse
import com.ping.messenger.core.common.map
import com.ping.messenger.core.common.runCatchingApp
import com.ping.messenger.core.common.toAppError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Test

class AppErrorTest {

    @Test
    fun `http codes map to the right error type`() {
        assertThat(fromHttpCode(401)).isInstanceOf(AppError.Unauthorized::class.java)
        assertThat(fromHttpCode(403)).isInstanceOf(AppError.Forbidden::class.java)
        assertThat(fromHttpCode(404)).isInstanceOf(AppError.NotFound::class.java)
        assertThat(fromHttpCode(409)).isInstanceOf(AppError.Conflict::class.java)
        assertThat(fromHttpCode(422)).isInstanceOf(AppError.Validation::class.java)
        assertThat(fromHttpCode(429)).isInstanceOf(AppError.RateLimited::class.java)
        assertThat(fromHttpCode(503)).isInstanceOf(AppError.Server::class.java)
    }

    @Test
    fun `network exceptions map to recoverable errors`() {
        assertThat(UnknownHostException().toAppError()).isInstanceOf(AppError.NoNetwork::class.java)
        assertThat(SocketTimeoutException().toAppError()).isInstanceOf(AppError.Timeout::class.java)
        assertThat(IOException().toAppError()).isInstanceOf(AppError.NoNetwork::class.java)
    }

    @Test
    fun `cancellation is rethrown rather than captured`() {
        // Swallowing CancellationException breaks structured concurrency: a coroutine that
        // was told to stop would keep running and report a spurious user-visible failure.
        try {
            CancellationException("stop").toAppError()
            throw AssertionError("expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            assertThat(expected).hasMessageThat().isEqualTo("stop")
        }
    }

    @Test
    fun `retryable classification matches what the UI offers`() {
        assertThat(AppError.NoNetwork().isRetryable).isTrue()
        assertThat(AppError.Timeout().isRetryable).isTrue()
        assertThat(AppError.Server(500).isRetryable).isTrue()
        assertThat(AppError.Server(418).isRetryable).isFalse()
        assertThat(AppError.Forbidden().isRetryable).isFalse()
        assertThat(AppError.Unauthorized.isRetryable).isFalse()
    }

    @Test
    fun `transient errors are the ones worth queueing rather than failing`() {
        assertThat(AppError.NoNetwork().isTransient).isTrue()
        assertThat(AppError.Server(502).isTransient).isTrue()
        assertThat(AppError.Validation().isTransient).isFalse()
    }

    @Test
    fun `outcome maps and flat-maps only on success`() {
        val success: Outcome<Int> = Outcome.Success(2)
        assertThat(success.map { it * 3 }.getOrNull()).isEqualTo(6)
        assertThat(success.flatMap { Outcome.Success(it + 1) }.getOrNull()).isEqualTo(3)

        val failure: Outcome<Int> = Outcome.Failure(AppError.NotFound())
        assertThat(failure.map { it * 3 }.getOrNull()).isNull()
        assertThat(failure.getOrElse(9)).isEqualTo(9)
    }

    @Test
    fun `runCatchingApp converts a throw into a failure`() {
        val result = runCatchingApp { error("boom") }
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(AppError.Unknown::class.java)
    }
}
