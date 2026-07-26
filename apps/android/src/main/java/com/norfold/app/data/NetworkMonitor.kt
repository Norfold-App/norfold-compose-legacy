package com.norfold.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun currentlyOnline(): Boolean = connectivityManager.activeNetwork
        ?.let(connectivityManager::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    val isOnline: Flow<Boolean> = callbackFlow {
        trySend(currentlyOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = trySend(currentlyOnline()).let { Unit }
            override fun onLost(network: Network) = trySend(currentlyOnline()).let { Unit }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(currentlyOnline())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
