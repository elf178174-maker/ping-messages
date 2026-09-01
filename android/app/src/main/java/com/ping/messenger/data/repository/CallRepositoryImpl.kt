package com.ping.messenger.data.repository

import com.ping.messenger.BuildConfig
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.data.local.dao.CallDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.StartCallRequest
import com.ping.messenger.domain.model.CallOutcome
import com.ping.messenger.domain.model.CallRecord
import com.ping.messenger.domain.repository.CallAvailability
import com.ping.messenger.domain.repository.CallRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val callDao: CallDao,
    private val userDao: UserDao,
    private val preferences: AppPreferences,
    private val mapper: EntityMapper,
) : CallRepository {

    override fun observeHistory(): Flow<List<CallRecord>> =
        callDao.observeHistory().map { rows ->
            with(mapper) { rows.map { it.record.toDomain(it.peer) } }
        }

    override fun observeMissedCount(): Flow<Int> =
        callDao.observeMissedSince(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))

    /**
     * Whether calling can work at all.
     *
     * WebRTC needs at least a STUN server to discover a route, and a TURN server for the
     * roughly 10-20% of networks where a direct path cannot be negotiated. Rather than
     * presenting a call button that silently fails, the UI asks this first and explains what is
     * missing. The server's own config wins; a build-time default and a user override are the
     * fallbacks.
     */
    override suspend fun availability(): CallAvailability {
        val override = preferences.advanced.first().iceServersOverride
        if (override.isNotBlank()) {
            return CallAvailability.Available(override.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }

        val fromServer = runCatching { api.callConfig() }.getOrNull()
        if (fromServer != null && fromServer.enabled && fromServer.iceServers.isNotEmpty()) {
            return CallAvailability.Available(fromServer.iceServers.flatMap { it.urls })
        }

        val compiled = BuildConfig.STUN_SERVERS
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (compiled.isEmpty()) CallAvailability.NotConfigured else CallAvailability.Available(compiled)
    }

    override suspend fun start(
        conversationId: String,
        isVideo: Boolean,
        calleeIds: List<String>,
    ): Outcome<String> = runCatchingAppSuspend {
        api.startCall(StartCallRequest(conversationId, isVideo, calleeIds)).callId
    }

    override suspend fun end(callId: String, durationSeconds: Long) {
        runCatching { api.endCall(callId, durationSeconds) }
        callDao.finish(
            callId,
            if (durationSeconds > 0) CallOutcome.COMPLETED else CallOutcome.MISSED,
            durationSeconds,
        )
    }

    override suspend fun clearHistory(): Outcome<Unit> = runCatchingAppSuspend {
        callDao.clearAll()
        api.clearCallHistory()
        Unit
    }

    override suspend fun refresh(): Outcome<Unit> = runCatchingAppSuspend {
        api.callHistory().forEach { dto -> with(mapper) { callDao.upsert(dto.toEntity()) } }
    }
}
