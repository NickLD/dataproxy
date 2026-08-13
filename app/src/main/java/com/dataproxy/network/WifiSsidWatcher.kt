package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the SSID of whichever Wi-Fi network is currently connected, for
 * Auto mode's trusted-network matching.
 *
 * Reading the real SSID (rather than Android's `<unknown ssid>` placeholder)
 * requires ACCESS_FINE_LOCATION *and* system Location services to be on —
 * both are the caller's responsibility to check/prompt for; this class
 * degrades to emitting `null` when either is missing rather than crashing.
 *
 * `NetworkCapabilities.transportInfo` (the modern way to read `WifiInfo` off
 * a callback) is API 29+; `minSdk` here is 26, so API 26-28 falls back to
 * the older `WifiManager.getConnectionInfo()` poll-on-change path instead.
 */
class WifiSsidWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _ssid = MutableStateFlow<String?>(null)
    val ssid: StateFlow<String?> = _ssid.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            currentNetwork = network
            _ssid.value = extractSsid(caps)
        }

        override fun onLost(network: Network) {
            if (currentNetwork == network) {
                currentNetwork = null
                _ssid.value = null
            }
        }
    }

    private var registered = false

    @Volatile
    private var currentNetwork: Network? = null

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        currentNetwork = null
        _ssid.value = null
    }

    private fun extractSsid(caps: NetworkCapabilities): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            runCatching { info?.ssid }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
        }
        if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null
        // WifiInfo.getSSID() double-quotes the SSID when it's valid UTF-8
        // text — the common case for a human-chosen network name.
        return raw.removeSurrounding("\"")
    }

    companion object {
        private const val TAG = "WifiSsidWatcher"
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
