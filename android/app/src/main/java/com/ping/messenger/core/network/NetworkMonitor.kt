package com.ping.messenger.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** What kind of connection the device currently has, which drives auto-download decisions. */
enum class ConnectionType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class NetworkState(
    val isOnline: Boolean = false,
    val type: ConnectionType = ConnectionType.NONE,
    /** True when the OS reports the connection as metered (or the user is roaming). */
    val isMetered: Boolean = false,
    /** True when the network is connected but has no verified internet access (captive portal). */
    val isCaptive: Boolean = false,
) {
    val isUnmeteredOnline: Boolean get() = isOnline && !isMetered
}

/**
 * Observes connectivity.
 *
 * The distinction that matters for this app is not "connected" but "validated": Android will
 * happily report a captive-portal Wi-Fi as connected, and treating that as online is what
 * produces the classic "messages spin forever on hotel Wi-Fi" bug. NET_CAPABILITY_VALIDATED is
 * what separates the two.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    val state: Flow<NetworkState> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(NetworkState())
            awaitClose { }
            return@callbackFlow
        }

        fun publish() = trySend(currentState())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish().let { }
            override fun onLost(network: Network) = publish().let { }
            override fun onUnavailable() = publish().let { }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = publish().let { }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { manager.registerNetworkCallback(request, callback) }
        publish()

        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.conflate().distinctUntilChanged()

    /** A synchronous read, for the places where suspending would be awkward (WorkManager). */
    fun currentState(): NetworkState {
        val manager = connectivityManager ?: return NetworkState()
        val network = manager.activeNetwork ?: return NetworkState()
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkState()

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.OTHER
        }

        return NetworkState(
            isOnline = hasInternet && validated,
            type = type,
            isMetered = !notMetered,
            isCaptive = hasInternet && !validated,
        )
    }

    val isOnline: Boolean get() = currentState().isOnline
}
