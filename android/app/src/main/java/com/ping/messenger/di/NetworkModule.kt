package com.ping.messenger.di

import com.ping.messenger.BuildConfig
import com.ping.messenger.core.network.AuthInterceptor
import com.ping.messenger.data.remote.api.AuthApi
import com.ping.messenger.data.remote.api.PingApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Lenient by design on the read side: `ignoreUnknownKeys` means a server that adds a field
     * does not break older clients, and `coerceInputValues` turns an unexpected null into the
     * declared default rather than an exception. `explicitNulls = false` keeps request bodies
     * small by omitting nulls entirely.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        classDiscriminator = "t"
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // Headers carry the bearer token and bodies carry message ciphertext, so release
        // builds log nothing at all.
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /** The client used for sign-in and refresh: no auth interceptor, so it cannot recurse. */
    @Provides
    @Singleton
    @PlainClient
    fun providePlainClient(logging: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedClient(
        authInterceptor: AuthInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Keeps the realtime socket alive through NATs that drop idle flows.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .build()

    /** The bare client the [com.ping.messenger.data.remote.ws.RealtimeClient] and media
     *  transfers use; it adds its own Authorization header per request. */
    @Provides
    @Singleton
    fun provideOkHttpClient(@AuthenticatedClient client: OkHttpClient): OkHttpClient = client

    @Provides
    @Singleton
    @Named("baseUrl")
    fun provideBaseUrl(): String = BuildConfig.API_BASE_URL.ensureTrailingSlash()

    @Provides
    @Singleton
    fun provideAuthApi(
        @PlainClient client: OkHttpClient,
        json: Json,
        @Named("baseUrl") baseUrl: String,
    ): AuthApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AuthApi::class.java)

    @Provides
    @Singleton
    fun providePingApi(
        @AuthenticatedClient client: OkHttpClient,
        json: Json,
        @Named("baseUrl") baseUrl: String,
    ): PingApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PingApi::class.java)
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

/** Derives the WebSocket URL from the HTTP base URL. */
fun httpToWebSocketUrl(baseUrl: String): String = baseUrl
    .replaceFirst("https://", "wss://")
    .replaceFirst("http://", "ws://")
    .trimEnd('/') + "/v1/realtime"
