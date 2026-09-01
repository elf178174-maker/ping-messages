package com.ping.messenger.data.remote.api

import com.ping.messenger.data.remote.dto.AuthResponse
import com.ping.messenger.data.remote.dto.BlockRequest
import com.ping.messenger.data.remote.dto.CallConfigDto
import com.ping.messenger.data.remote.dto.CallRecordDto
import com.ping.messenger.data.remote.dto.CallSessionDto
import com.ping.messenger.data.remote.dto.ChangePasswordRequest
import com.ping.messenger.data.remote.dto.ContactDiscoveryRequest
import com.ping.messenger.data.remote.dto.ContactDiscoveryResponse
import com.ping.messenger.data.remote.dto.ConversationDto
import com.ping.messenger.data.remote.dto.CreateConversationRequest
import com.ping.messenger.data.remote.dto.CreateGroupRequest
import com.ping.messenger.data.remote.dto.CreateStatusRequest
import com.ping.messenger.data.remote.dto.DeleteMessageRequest
import com.ping.messenger.data.remote.dto.DeviceDto
import com.ping.messenger.data.remote.dto.EditMessageRequest
import com.ping.messenger.data.remote.dto.ForgotPasswordRequest
import com.ping.messenger.data.remote.dto.GroupDto
import com.ping.messenger.data.remote.dto.GroupMembersRequest
import com.ping.messenger.data.remote.dto.InviteLinkDto
import com.ping.messenger.data.remote.dto.LoginRequest
import com.ping.messenger.data.remote.dto.MessageDto
import com.ping.messenger.data.remote.dto.MessageResponse
import com.ping.messenger.data.remote.dto.PageDto
import com.ping.messenger.data.remote.dto.PrivacySettingsDto
import com.ping.messenger.data.remote.dto.ReactRequest
import com.ping.messenger.data.remote.dto.ReadReceiptRequest
import com.ping.messenger.data.remote.dto.RefreshRequest
import com.ping.messenger.data.remote.dto.RefreshResponse
import com.ping.messenger.data.remote.dto.RegisterDeviceKeyRequest
import com.ping.messenger.data.remote.dto.RegisterRequest
import com.ping.messenger.data.remote.dto.ReportRequest
import com.ping.messenger.data.remote.dto.ResendCodeRequest
import com.ping.messenger.data.remote.dto.ResetPasswordRequest
import com.ping.messenger.data.remote.dto.SearchResponseDto
import com.ping.messenger.data.remote.dto.SendMessageRequest
import com.ping.messenger.data.remote.dto.SetRoleRequest
import com.ping.messenger.data.remote.dto.StartCallRequest
import com.ping.messenger.data.remote.dto.StatusPostDto
import com.ping.messenger.data.remote.dto.SyncResponseDto
import com.ping.messenger.data.remote.dto.TwoStepRequest
import com.ping.messenger.data.remote.dto.UpdateGroupRequest
import com.ping.messenger.data.remote.dto.UpdateProfileRequest
import com.ping.messenger.data.remote.dto.UploadTicketDto
import com.ping.messenger.data.remote.dto.UploadTicketRequest
import com.ping.messenger.data.remote.dto.UserDto
import com.ping.messenger.data.remote.dto.UsernameAvailabilityDto
import com.ping.messenger.data.remote.dto.VerifyEmailRequest
import com.ping.messenger.data.remote.dto.VotePollRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Authentication and account lifecycle.
 *
 * Split from [PingApi] because these calls run without (or before) a bearer token, and are
 * therefore served by a separate OkHttp client that has no auth interceptor — which is what
 * stops a 401 on `/auth/login` from triggering a pointless token refresh.
 */
interface AuthApi {

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("v1/auth/verify-email")
    suspend fun verifyEmail(@Body body: VerifyEmailRequest): AuthResponse

    @POST("v1/auth/resend-code")
    suspend fun resendCode(@Body body: ResendCodeRequest): MessageResponse

    @POST("v1/auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): MessageResponse

    @POST("v1/auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): MessageResponse

    @GET("v1/auth/username-available")
    suspend fun usernameAvailable(@Query("username") username: String): UsernameAvailabilityDto
}

/** Everything that requires a signed-in session. */
interface PingApi {

    // ---- Session ----------------------------------------------------------

    @POST("v1/auth/logout")
    suspend fun logout(): MessageResponse

    @POST("v1/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): MessageResponse

    @POST("v1/auth/two-step")
    suspend fun setTwoStep(@Body body: TwoStepRequest): MessageResponse

    // ---- Me ---------------------------------------------------------------

    @GET("v1/me")
    suspend fun me(): UserDto

    @PATCH("v1/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @DELETE("v1/me")
    suspend fun deleteAccount(): MessageResponse

    @GET("v1/me/privacy")
    suspend fun privacy(): PrivacySettingsDto

    @PUT("v1/me/privacy")
    suspend fun updatePrivacy(@Body body: PrivacySettingsDto): PrivacySettingsDto

    @GET("v1/me/devices")
    suspend fun devices(): List<DeviceDto>

    @DELETE("v1/me/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: String): MessageResponse

    @DELETE("v1/me/devices")
    suspend fun revokeOtherDevices(): MessageResponse

    @PUT("v1/me/device-key")
    suspend fun publishDeviceKey(@Body body: RegisterDeviceKeyRequest): MessageResponse

    // ---- Users and contacts ----------------------------------------------

    @GET("v1/users/{id}")
    suspend fun user(@Path("id") id: String): UserDto

    @GET("v1/users/by-username/{username}")
    suspend fun userByUsername(@Path("username") username: String): UserDto

    @GET("v1/users")
    suspend fun searchUsers(@Query("q") query: String, @Query("limit") limit: Int = 20): List<UserDto>

    @GET("v1/contacts")
    suspend fun contacts(): List<UserDto>

    @POST("v1/contacts/{id}")
    suspend fun addContact(@Path("id") userId: String): MessageResponse

    @DELETE("v1/contacts/{id}")
    suspend fun removeContact(@Path("id") userId: String): MessageResponse

    @POST("v1/contacts/discover")
    suspend fun discoverContacts(@Body body: ContactDiscoveryRequest): ContactDiscoveryResponse

    @GET("v1/blocks")
    suspend fun blockedUsers(): List<UserDto>

    @POST("v1/blocks")
    suspend fun block(@Body body: BlockRequest): MessageResponse

    @DELETE("v1/blocks/{id}")
    suspend fun unblock(@Path("id") userId: String): MessageResponse

    @POST("v1/reports")
    suspend fun report(@Body body: ReportRequest): MessageResponse

    // ---- Conversations ----------------------------------------------------

    @GET("v1/conversations")
    suspend fun conversations(@Query("since") since: Long? = null): List<ConversationDto>

    @GET("v1/conversations/{id}")
    suspend fun conversation(@Path("id") id: String): ConversationDto

    @POST("v1/conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): ConversationDto

    @DELETE("v1/conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): MessageResponse

    @PUT("v1/conversations/{id}/disappearing")
    suspend fun setDisappearing(
        @Path("id") id: String,
        @Query("durationMs") durationMs: Long?,
    ): MessageResponse

    @PUT("v1/conversations/{id}/pinned-message")
    suspend fun setPinnedMessage(
        @Path("id") id: String,
        @Query("messageId") messageId: String?,
    ): MessageResponse

    // ---- Messages ---------------------------------------------------------

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(
        @Path("id") conversationId: String,
        @Query("before") beforeSeq: Long? = null,
        @Query("after") afterSeq: Long? = null,
        @Query("limit") limit: Int = 50,
    ): PageDto<MessageDto>

    @POST("v1/messages")
    suspend fun sendMessage(@Body body: SendMessageRequest): MessageDto

    @PATCH("v1/messages/{id}")
    suspend fun editMessage(@Path("id") id: String, @Body body: EditMessageRequest): MessageDto

    @POST("v1/messages/{id}/delete")
    suspend fun deleteMessage(
        @Path("id") id: String,
        @Body body: DeleteMessageRequest,
    ): MessageResponse

    @POST("v1/messages/{id}/reactions")
    suspend fun react(@Path("id") id: String, @Body body: ReactRequest): MessageResponse

    @POST("v1/messages/{id}/star")
    suspend fun star(@Path("id") id: String, @Query("starred") starred: Boolean): MessageResponse

    @POST("v1/messages/{id}/vote")
    suspend fun votePoll(@Path("id") id: String, @Body body: VotePollRequest): MessageDto

    @POST("v1/receipts/read")
    suspend fun markRead(@Body body: ReadReceiptRequest): MessageResponse

    // ---- Groups -----------------------------------------------------------

    @POST("v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): GroupDto

    @GET("v1/groups/{id}")
    suspend fun group(@Path("id") id: String): GroupDto

    @PATCH("v1/groups/{id}")
    suspend fun updateGroup(@Path("id") id: String, @Body body: UpdateGroupRequest): GroupDto

    @POST("v1/groups/{id}/members")
    suspend fun addMembers(@Path("id") id: String, @Body body: GroupMembersRequest): GroupDto

    @DELETE("v1/groups/{id}/members/{userId}")
    suspend fun removeMember(@Path("id") id: String, @Path("userId") userId: String): GroupDto

    @PUT("v1/groups/{id}/members/{userId}/role")
    suspend fun setMemberRole(
        @Path("id") id: String,
        @Path("userId") userId: String,
        @Body body: SetRoleRequest,
    ): GroupDto

    @POST("v1/groups/{id}/leave")
    suspend fun leaveGroup(@Path("id") id: String): MessageResponse

    @GET("v1/groups/{id}/invite")
    suspend fun inviteLink(@Path("id") id: String): InviteLinkDto

    @POST("v1/groups/{id}/invite/reset")
    suspend fun resetInviteLink(@Path("id") id: String): InviteLinkDto

    @POST("v1/groups/join/{code}")
    suspend fun joinByInvite(@Path("code") code: String): GroupDto

    // ---- Media ------------------------------------------------------------

    @POST("v1/media/upload-ticket")
    suspend fun uploadTicket(@Body body: UploadTicketRequest): UploadTicketDto

    // ---- Status -----------------------------------------------------------

    @GET("v1/status")
    suspend fun statuses(): List<StatusPostDto>

    @POST("v1/status")
    suspend fun createStatus(@Body body: CreateStatusRequest): StatusPostDto

    @DELETE("v1/status/{id}")
    suspend fun deleteStatus(@Path("id") id: String): MessageResponse

    @POST("v1/status/{id}/view")
    suspend fun viewStatus(@Path("id") id: String): MessageResponse

    // ---- Calls ------------------------------------------------------------

    @GET("v1/calls/config")
    suspend fun callConfig(): CallConfigDto

    @POST("v1/calls")
    suspend fun startCall(@Body body: StartCallRequest): CallSessionDto

    @POST("v1/calls/{id}/end")
    suspend fun endCall(@Path("id") id: String, @Query("duration") durationSeconds: Long): MessageResponse

    @GET("v1/calls")
    suspend fun callHistory(@Query("limit") limit: Int = 100): List<CallRecordDto>

    @DELETE("v1/calls")
    suspend fun clearCallHistory(): MessageResponse

    // ---- Search and sync --------------------------------------------------

    @GET("v1/search")
    suspend fun search(@Query("q") query: String): SearchResponseDto

    /**
     * Incremental catch-up after being offline. [since] is the server clock value from the last
     * successful sync, not a local timestamp, so clock skew on the device cannot cause the
     * client to skip messages.
     */
    @GET("v1/sync")
    suspend fun sync(
        @Query("since") since: Long,
        @Query("cursor") cursor: String? = null,
    ): SyncResponseDto
}
