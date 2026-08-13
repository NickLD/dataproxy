package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the SSID of whichever Wi-Fi network is currently connected, for
 * Auto mode's trusted-network matching.
 *
 * Reading the real SSID (rather than Android's `<unknown ssid>` placeholder)
 * requires ACCESS_FINE_LOCATION, system Location services to be on, *and*
 * — since this class is read from a foreground service, not a visible
 * Activity — ACCESS_BACKGROUND_LOCATION, or every read while the app itself
 * is backgrounded (e.g. phone locked) comes back redacted even though the
 * foreground-only grant looks identical from inside the app. Confirmed live:
 * without it, Auto mode only ever promoted a trusted network within seconds
 * of the app being opened, never while just sitting in a pocket. All three
 * are the caller's responsibility to check/prompt for (see AntiKillScreen);
 * this class degrades to emitting `null` when any is missing rather than
 * crashing.
 *
 * Deliberately reads the SSID via a direct `WifiManager.getConnectionInfo()`
 * call, not `NetworkCapabilities.transportInfo` off the network callback.
 * Confirmed on real hardware (Pixel 10 Pro XL, Android 17 / API 37): a
 * `NetworkCallback`-delivered `WifiInfo` is redacted (`<unknown ssid>`,
 * zeroed BSSID/MAC) even with ACCESS_FINE_LOCATION granted and Location on
 * — the callback-delivery path evidently applies a different (likely
 * stricter) permission check than a direct synchronous call, though the
 * exact mechanism wasn't confirmed.
 * `WifiManager.getConnectionInfo()` is deprecated but still functions
 * correctly and isn't scheduled for removal.
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
            _ssid.value = extractSsid()
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

    private fun extractSsid(): String? {
        @Suppress("DEPRECATION")
        val raw = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
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
