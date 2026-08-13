package com.dataproxy.util

import android.content.Context
import com.dataproxy.service.ProxyService
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single trusted Wi-Fi network Auto mode activates the proxy on, with its
 * own listen address/port (different trusted networks are plausibly
 * different subnets) and an optional last-bind-error surfaced when
 * activation on this entry fails.
 */
data class TrustedNetwork(
    val ssid: String,
    val address: String,
    val port: Int,
    val lastBindError: String? = null,
)

/**
 * Persists the Auto-mode trusted-network list as a JSON array in the same
 * [ProxyService.PREFS_NAME] file every other setting lives in. Uses
 * `org.json` (part of the Android SDK, not a new dependency) with named
 * object properties so a future field can be added without a migration
 * step — decoding always reads with `optString`/`optInt`, never a
 * required key.
 */
object TrustedNetworks {
    private const val KEY = "trusted_networks"
    private const val FIELD_SSID = "ssid"
    private const val FIELD_ADDRESS = "address"
    private const val FIELD_PORT = "port"
    private const val FIELD_ERROR = "lastBindError"

    private fun prefs(context: Context) =
        context.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<TrustedNetwork> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val ssid = obj.optString(FIELD_SSID, "")
            if (ssid.isEmpty()) return@mapNotNull null
            TrustedNetwork(
                ssid = ssid,
                address = obj.optString(FIELD_ADDRESS, "0.0.0.0"),
                port = obj.optInt(FIELD_PORT, ProxyService.DEFAULT_PORT),
                lastBindError = obj.optString(FIELD_ERROR, "").ifEmpty { null },
            )
        }
    }

    fun save(context: Context, networks: List<TrustedNetwork>) {
        val array = JSONArray()
        networks.forEach { n ->
            array.put(
                JSONObject().apply {
                    put(FIELD_SSID, n.ssid)
                    put(FIELD_ADDRESS, n.address)
                    put(FIELD_PORT, n.port)
                    if (n.lastBindError != null) put(FIELD_ERROR, n.lastBindError)
                },
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun add(context: Context, network: TrustedNetwork) {
        val current = list(context).filterNot { it.ssid == network.ssid }
        save(context, current + network)
    }

    fun remove(context: Context, ssid: String) {
        save(context, list(context).filterNot { it.ssid == ssid })
    }

    fun update(context: Context, network: TrustedNetwork) {
        val current = list(context).map { if (it.ssid == network.ssid) network else it }
        save(context, current)
    }

    /** Called when activation on [ssid] fails to bind; `null` clears a prior error. */
    fun setBindError(context: Context, ssid: String, message: String?) {
        val match = list(context).firstOrNull { it.ssid == ssid } ?: return
        update(context, match.copy(lastBindError = message))
    }

    fun find(context: Context, ssid: String): TrustedNetwork? =
        list(context).firstOrNull { it.ssid == ssid }
}
