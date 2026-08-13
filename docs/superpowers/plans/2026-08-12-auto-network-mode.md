# Auto Network Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let DataProxy automatically activate on a user-managed list of trusted Wi-Fi networks (each with its own listen address/port) and automatically stand down when the phone leaves them, without exposing an unauthenticated proxy on untrusted networks or requiring a manual power-button tap.

**Architecture:** Extend the existing `ProxyService` foreground service (not a second component) with a lightweight `Idle` state that runs only a `WifiSsidWatcher`, promoting to the existing `Running` path when a trusted SSID matches and demoting back on departure. Trusted networks (SSID + address + port) persist as a JSON array in the existing `dataproxy_prefs` file.

**Tech Stack:** Kotlin, Jetpack Compose, `ConnectivityManager.NetworkCallback`, `org.json` (Android SDK, no new dependency). No test framework in this project — verification is `gradle :app:assembleDebug` per task plus a final on-device manual pass.

**Spec:** `docs/superpowers/specs/2026-08-12-auto-network-mode-design.md`

## Global Constraints

- Zero behavior change when Auto mode is off (spec Scope section).
- `ACCESS_FINE_LOCATION` is requested only when the user turns Auto mode on — never at install/launch (spec Scope, Permissions & UI placement).
- No new Gradle dependency — `org.json` ships in `android.jar` (spec: Trusted network storage).
- `minSdk = 26` (from `app/build.gradle.kts`) — `NetworkCapabilities.transportInfo` is API 29+; anything reading it needs an SDK-version branch with a pre-29 fallback.
- 5s debounce on leaving a trusted network is a roam-noise filter, not connection preservation (spec: SSID watcher).
- State naming is `Idle`, not "Standby" (spec: State machine).
- Never call `cm.bindProcessToNetwork(...)` anywhere in this codebase (`CLAUDE.md` architecture invariant) — not touched by this feature, but no new code path should introduce it.
- `fullCleanup()` remains the single idempotent teardown helper for cellular/server/wake-lock state — extend it only if a step below says so; do not fork a second teardown path for those three resources.

---

### Task 1: Trusted network data model & storage

**Files:**
- Create: `app/src/main/java/com/dataproxy/util/TrustedNetworks.kt`

**Interfaces:**
- Produces: `data class TrustedNetwork(val ssid: String, val address: String, val port: Int, val lastBindError: String? = null)` and `object TrustedNetworks` with `list(context: Context): List<TrustedNetwork>`, `add(context: Context, network: TrustedNetwork)`, `remove(context: Context, ssid: String)`, `update(context: Context, network: TrustedNetwork)`, `setBindError(context: Context, ssid: String, message: String?)`, `find(context: Context, ssid: String): TrustedNetwork?`. Every later task that touches trusted networks uses these exact names.

- [ ] **Step 1: Write the file**

```kotlin
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
```

- [ ] **Step 2: Build to verify it compiles**

Run: `gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15`
Expected: `BUILD SUCCESSFUL`. This file has no callers yet, so a clean compile is the only signal available at this step.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dataproxy/util/TrustedNetworks.kt
git commit -m "Add TrustedNetworks JSON-backed storage for auto network mode"
```

---

### Task 2: Wi-Fi SSID watcher

**Files:**
- Create: `app/src/main/java/com/dataproxy/network/WifiSsidWatcher.kt`

**Interfaces:**
- Produces: `class WifiSsidWatcher(context: Context)` with `val ssid: StateFlow<String?>`, `fun start()`, `fun stop()`. Modeled on the existing `CellularNetworkProvider` in the same package — read that file first for the exact `StateFlow`/`NetworkCallback`/`@Synchronized` idiom this must match.

- [ ] **Step 1: Write the file**

```kotlin
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
            _ssid.value = extractSsid(caps)
        }

        override fun onLost(network: Network) {
            _ssid.value = null
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
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
```

- [ ] **Step 2: Build to verify it compiles**

Run: `gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dataproxy/network/WifiSsidWatcher.kt
git commit -m "Add WifiSsidWatcher for auto network mode SSID matching"
```

---

### Task 3: Manifest permission + Auto-mode preference flag

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/dataproxy/util/AntiKillPreferences.kt`

**Interfaces:**
- Produces: `AntiKillPreferences.autoNetworkModeEnabled(context: Context): Boolean` and `AntiKillPreferences.setAutoNetworkModeEnabled(context: Context, enabled: Boolean)`, alongside the existing `autoStartOnBoot`/`setAutoStartOnBoot`. `ProxyService` (Task 4/5) and `MainViewModel` (Task 7) both call these by name.

- [ ] **Step 1: Add the manifest permission**

In `app/src/main/AndroidManifest.xml`, add alongside the existing `<uses-permission>` block (after `ACCESS_WIFI_STATE`):

```xml
    <!-- Runtime-requested only when the user enables Auto network mode
         (see AntiKillScreen) — required by Android to read the current
         Wi-Fi SSID for trusted-network matching. Declaring it here does
         not itself trigger a prompt. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

- [ ] **Step 2: Add the preference functions**

In `app/src/main/java/com/dataproxy/util/AntiKillPreferences.kt`, add a key constant and two functions alongside the existing `KEY_AUTOSTART` pair:

```kotlin
    private const val KEY_AUTO_NETWORK_MODE = "auto_network_mode"
```

```kotlin
    fun autoNetworkModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_NETWORK_MODE, false)

    fun setAutoNetworkModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_NETWORK_MODE, enabled).apply()
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/dataproxy/util/AntiKillPreferences.kt
git commit -m "Add ACCESS_FINE_LOCATION permission and auto-network-mode preference flag"
```

---

### Task 4: ProxyService state machine — Idle, ManuallyStopped, auto-activation

**Files:**
- Modify: `app/src/main/java/com/dataproxy/service/ProxyService.kt`

**Interfaces:**
- Consumes: `WifiSsidWatcher` (Task 2), `TrustedNetworks`/`TrustedNetwork` (Task 1), `AntiKillPreferences.autoNetworkModeEnabled` (Task 3).
- Produces: `State.Idle(val reason: String = "Waiting for a trusted network")`, `State.ManuallyStopped(val ssidAtStop: String?)`, `val wifiSsidState: StateFlow<String?>`, `const val ACTION_START_AUTO`, `const val ACTION_DISABLE_AUTO`, `fun startAutoIntent(ctx: Context): Intent`, `fun disableAutoIntent(ctx: Context): Intent`. Task 5 extends this same file's `onFatal`/notification code; Task 6 (`BootReceiver`) and Task 7 (`MainViewModel`) call `ACTION_START_AUTO`/`ACTION_DISABLE_AUTO` and `wifiSsidState` by these exact names.

This is the largest task in the plan — it changes the core state machine this codebase has hardened over several prior bugs (see `CLAUDE.md`). Read the whole current file before editing; every step below is a targeted change, not a rewrite.

- [ ] **Step 1: Add the two new `State` cases**

In `ProxyService.State`, after `data class Paused(...)`, add:

```kotlin
        /**
         * Auto mode is on, no trusted network currently matches. A
         * [WifiSsidWatcher] is running; nothing else is — no server, no
         * cellular request, no wake lock. Named `Idle`, not "Standby" —
         * reads as passively off, not armed-and-ready.
         */
        data class Idle(val reason: String = "Waiting for a trusted network") : State
        /**
         * The user tapped the power button off while Auto mode was
         * managing the proxy. The watcher keeps running but won't
         * self-promote again until [ssidAtStop] is left — a genuine SSID
         * transition, not just re-observing the same network.
         */
        data class ManuallyStopped(val ssidAtStop: String?) : State
```

- [ ] **Step 2: Add the watcher fields and `wifiSsidState`**

Near the existing `private val cellular by lazy { ... }`, add:

```kotlin
    private val wifiWatcher by lazy { WifiSsidWatcher(applicationContext) }
    private var wifiWatchJob: Job? = null
    private var autoDemoteJob: Job? = null
    private var activeSsid: String? = null
```

Near the existing `val cellularState: StateFlow<...> get() = cellular.state`, add:

```kotlin
    val wifiSsidState: StateFlow<String?> get() = wifiWatcher.ssid
```

- [ ] **Step 3: Add the auto-watch entry point**

Add a new public function, near `startProxy`:

```kotlin
    /**
     * Auto mode's entry point: brings the service up watching for a
     * trusted network, without touching cellular/the SOCKS server until a
     * match is found. Idempotent — a second call while already watching
     * or running is a no-op.
     */
    fun startAutoWatch() {
        if (_state.value !is State.Stopped) return
        wifiWatcher.start()
        _state.value = State.Idle()
        startForegroundNow()
        wifiWatchJob = scope.launch {
            wifiWatcher.ssid.collect { ssid -> onSsidChanged(ssid) }
        }
    }

    /** Full teardown, called when the user disables Auto mode entirely. */
    fun stopAutoWatch() {
        autoDemoteJob?.cancel(); autoDemoteJob = null
        wifiWatchJob?.cancel(); wifiWatchJob = null
        wifiWatcher.stop()
        activeSsid = null
        _state.value = State.Stopped
        fullCleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
```

- [ ] **Step 4: Add the SSID-change handler and promote/demote**

```kotlin
    private fun onSsidChanged(ssid: String?) {
        autoDemoteJob?.cancel(); autoDemoteJob = null
        val match = ssid?.let { TrustedNetworks.find(applicationContext, it) }
        when (val cur = _state.value) {
            is State.Idle -> if (match != null) promote(match)
            is State.Running, is State.Paused -> {
                if (match == null || match.ssid != activeSsid) {
                    autoDemoteJob = scope.launch {
                        delay(5_000L)
                        demoteToIdle()
                    }
                }
            }
            is State.ManuallyStopped -> {
                if (ssid != cur.ssidAtStop) {
                    _state.value = State.Idle()
                    if (match != null) promote(match)
                }
            }
            else -> Unit
        }
    }

    private fun promote(network: TrustedNetwork) {
        activeSsid = network.ssid
        startProxy(network.address, network.port, isAutoActivation = true)
    }

    private fun demoteToIdle() {
        if (_state.value !is State.Running && _state.value !is State.Paused) return
        fullCleanup()
        activeSsid = null
        _state.value = State.Idle()
        startForegroundNow()
    }
```

- [ ] **Step 5: Thread `isAutoActivation` through `startProxy` and its failure paths**

Change the signature and the two failure branches inside it:

```kotlin
    fun startProxy(bindAddress: String, port: Int, isAutoActivation: Boolean = false) {
        if (_state.value is State.Running || _state.value is State.Starting) return

        fullCleanup()

        _state.value = State.Starting(bindAddress, port)
        startForegroundNow()

        cellular.start()
        startJob = scope.launch {
            val net = cellular.awaitAvailable(15_000L)
            if (_state.value !is State.Starting) return@launch
            if (net == null) {
                if (isAutoActivation) {
                    fullCleanup()
                    activeSsid = null
                    _state.value = State.Idle()
                    startForegroundNow()
                    return@launch
                }
                _state.value = State.Error(
                    message = "Mobile data is unavailable. Turn it on to start the proxy.",
                    kind = State.ErrorKind.MobileDataUnavailable,
                )
                fullCleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            val srv = Socks5Server(
                bindAddress = bindAddress,
                port = port,
                cellular = cellular,
                registry = registry,
                onFatal = { e ->
                    if (isAutoActivation) {
                        TrustedNetworks.setBindError(applicationContext, bindAddress.let { activeSsid } ?: "", e.message ?: "Bind failed")
                        postBindFailureNotification(activeSsid, e.message ?: "Bind failed")
                        fullCleanup()
                        activeSsid = null
                        _state.value = State.Idle()
                        startForegroundNow()
                    } else {
                        _state.value = State.Error(
                            message = e.message ?: "Bind failed",
                            kind = State.ErrorKind.BindFailed,
                        )
                        fullCleanup()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                },
                authProvider = ::currentAuthConfig,
            )
            server = srv
            srv.start()
            if (srv.running) {
                _state.value = State.Running(bindAddress, port)
                acquireWakeLock()
                startSampling()
                startCellularWatch(bindAddress, port)
                updateNotification()
            }
        }
    }
```

Note: `TrustedNetworks.setBindError` and `postBindFailureNotification` are written in Task 5 — this task compiles once Task 5's stubs land; if executed strictly in order, leave a call site here and implement the two functions in Task 5 (do not stub them here, since "no placeholders" — Task 5 must land before this file builds standalone; the plan's task order already guarantees that).

- [ ] **Step 6: Manual stop respects Auto mode**

Replace `stopProxy()`:

```kotlin
    fun stopProxy() {
        if (AntiKillPreferences.autoNetworkModeEnabled(applicationContext) &&
            _state.value !is State.Stopped
        ) {
            val ssidAtStop = wifiWatcher.ssid.value
            fullCleanup()
            activeSsid = null
            _state.value = State.ManuallyStopped(ssidAtStop)
            startForegroundNow()
            return
        }
        _state.value = State.Stopped
        fullCleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
```

- [ ] **Step 7: Wire `onStartCommand` and add the two new actions**

In `onStartCommand`'s `when (intent?.action)`, add:

```kotlin
            ACTION_START_AUTO -> startAutoWatch()
            ACTION_DISABLE_AUTO -> stopAutoWatch()
```

In the companion object, add the actions and intent builders alongside the existing `startIntent`/`stopIntent`:

```kotlin
        const val ACTION_START_AUTO = "com.dataproxy.ACTION_START_AUTO"
        const val ACTION_DISABLE_AUTO = "com.dataproxy.ACTION_DISABLE_AUTO"
```

```kotlin
        fun startAutoIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_START_AUTO)

        fun disableAutoIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_DISABLE_AUTO)
```

- [ ] **Step 8: Simplify `startForegroundNow`/notification call sites to take no args**

`startForegroundNow`, `updateNotification`, and `buildNotification` currently take `addr`/`port` params threaded from call sites that, after this task, don't always have them (Idle/ManuallyStopped have none). Change all three to take no address/port parameters, deriving title text from `_state.value` directly:

```kotlin
    private fun startForegroundNow() {
        val notif = buildNotification(totals.value, rates.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
```

```kotlin
    private fun updateNotification(
        totals: ConnectionRegistry.Totals = this.totals.value,
        rates: SpeedSampler.Rates = this.rates.value,
    ) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(totals, rates))
    }
```

```kotlin
    private fun buildNotification(
        totals: ConnectionRegistry.Totals,
        rates: SpeedSampler.Rates,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, sub) = when (val s = _state.value) {
            is State.Running -> {
                val rateUnit = RateUnit.fromKey(
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RATE_UNIT, null)
                )
                "DataProxy · ${s.bindAddress}:${s.port}" to
                    "${totals.active} conn  ·  ↑${rateText(rates.upBps, rateUnit)}  ↓${rateText(rates.downBps, rateUnit)}"
            }
            is State.Paused -> "DataProxy · paused" to "Waiting for mobile data"
            is State.Idle -> "DataProxy · idle" to s.reason
            is State.ManuallyStopped -> "DataProxy · idle" to "Stopped — waiting for a network change"
            else -> "DataProxy" to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle(title)
            .setContentText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_power, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
```

Update every remaining call site in the file (`startCellularWatch`'s two `updateNotification(addr, port, totals.value, rates.value)` calls, and `startSampling`'s loop) to the no-arg forms: `updateNotification()`.

- [ ] **Step 9: Add the two new imports**

```kotlin
import com.dataproxy.network.WifiSsidWatcher
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.TrustedNetwork
import com.dataproxy.util.TrustedNetworks
import kotlinx.coroutines.delay
```

(`delay` may already be imported — check before duplicating.)

- [ ] **Step 10: Build to verify it compiles**

This will not fully compile standalone — `postBindFailureNotification` doesn't exist until Task 5. Run the build anyway and confirm the **only** error is the missing `postBindFailureNotification` reference (and possibly `TrustedNetworks.setBindError`'s call-site expression, which has an odd `bindAddress.let { activeSsid } ?: ""` — fix that in this step to simply `activeSsid ?: ""`, it was a leftover from drafting):

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: exactly one unresolved-reference error, for `postBindFailureNotification`.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/dataproxy/service/ProxyService.kt
git commit -m "Add Idle/ManuallyStopped states and auto-activation to ProxyService"
```

---

### Task 5: Bind-failure notification

**Files:**
- Modify: `app/src/main/java/com/dataproxy/service/ProxyService.kt`

**Interfaces:**
- Consumes: `TrustedNetworks.setBindError` (Task 1), the `activeSsid`/`onFatal` call site from Task 4 Step 5.
- Produces: `private fun postBindFailureNotification(ssid: String?, message: String)` — resolves the one unresolved reference left by Task 4.

- [ ] **Step 1: Add the one-shot notification function**

```kotlin
    /**
     * Auto mode's activation failed to bind. Distinct from the persistent
     * ongoing status notification — dismissible, one-shot — since a silent
     * fallback to Idle alone would create false confidence the backup WAN
     * is live when it isn't.
     */
    private fun postBindFailureNotification(ssid: String?, message: String) {
        val label = ssid ?: "trusted network"
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle("DataProxy couldn't start on \"$label\"")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(BIND_FAIL_NOTIF_ID, notif)
    }
```

- [ ] **Step 2: Fix the `setBindError` call site left in Task 4**

In `startProxy`'s `onFatal` lambda, the auto-activation branch should read:

```kotlin
                    if (isAutoActivation) {
                        TrustedNetworks.setBindError(applicationContext, activeSsid ?: "", e.message ?: "Bind failed")
                        postBindFailureNotification(activeSsid, e.message ?: "Bind failed")
```

- [ ] **Step 3: Add the notification ID constant**

Alongside the existing `private const val NOTIF_ID = 1001`:

```kotlin
        private const val BIND_FAIL_NOTIF_ID = 1002
```

- [ ] **Step 4: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dataproxy/service/ProxyService.kt
git commit -m "Surface auto-activation bind failures via a one-shot notification"
```

---

### Task 6: BootReceiver branches on Auto mode

**Files:**
- Modify: `app/src/main/java/com/dataproxy/service/BootReceiver.kt`

**Interfaces:**
- Consumes: `AntiKillPreferences.autoNetworkModeEnabled` (Task 3), `ProxyService.startAutoIntent` (Task 4).

- [ ] **Step 1: Replace `onReceive`'s body after the relevance/autostart checks**

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return
        if (!AntiKillPreferences.autoStartOnBoot(context)) return

        val serviceIntent = if (AntiKillPreferences.autoNetworkModeEnabled(context)) {
            // Auto mode owns its own per-network address/port — boot always
            // brings the service up watching, not immediately active.
            ProxyService.startAutoIntent(context)
        } else {
            val prefs = context.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)
            val addr = prefs.getString(ProxyService.PREF_BIND_ADDRESS, "0.0.0.0") ?: "0.0.0.0"
            val port = prefs.getInt(ProxyService.PREF_PORT, ProxyService.DEFAULT_PORT)
            ProxyService.startIntent(context, addr, port)
        }

        // startForegroundService is required (we're a background context here);
        // the service calls startForeground() synchronously in startProxy /
        // startAutoWatch.
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
```

- [ ] **Step 2: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dataproxy/service/BootReceiver.kt
git commit -m "Boot into Idle watch mode when auto network mode is enabled"
```

---

### Task 7: MainViewModel wiring

**Files:**
- Modify: `app/src/main/java/com/dataproxy/ui/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `AntiKillPreferences.autoNetworkModeEnabled`/`setAutoNetworkModeEnabled`/`setAutoStartOnBoot` (Task 3), `TrustedNetworks`/`TrustedNetwork` (Task 1), `ProxyService.wifiSsidState`/`startAutoIntent`/`disableAutoIntent` (Task 4).
- Produces: `val autoNetworkModeEnabled: StateFlow<Boolean>`, `val trustedNetworks: StateFlow<List<TrustedNetwork>>`, `val currentWifiSsid: StateFlow<String?>`, `fun setAutoNetworkModeEnabled(enabled: Boolean)`, `fun addTrustedNetwork(network: TrustedNetwork)`, `fun removeTrustedNetwork(ssid: String)`, `fun updateTrustedNetwork(network: TrustedNetwork)`, `fun refreshTrustedNetworks()`. Task 8 (`TrustedNetworksScreen`) and Task 9 (`AntiKillScreen`) call these exact names.

- [ ] **Step 1: Add the new state and mirror `wifiSsidState`**

Near the existing `_cellular`/`cellularState` mirror pair, add:

```kotlin
    private val _wifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()
```

In `mirror(service: ProxyService)`, add alongside the existing `collectors +=` lines:

```kotlin
        collectors += service.wifiSsidState.onEach { _wifiSsid.value = it }.launchIn(viewModelScope)
```

- [ ] **Step 2: Add Auto-mode and trusted-network state**

```kotlin
    private val _autoNetworkModeEnabled = MutableStateFlow(
        AntiKillPreferences.autoNetworkModeEnabled(app)
    )
    val autoNetworkModeEnabled: StateFlow<Boolean> = _autoNetworkModeEnabled.asStateFlow()

    private val _trustedNetworks = MutableStateFlow(TrustedNetworks.list(app))
    val trustedNetworks: StateFlow<List<TrustedNetwork>> = _trustedNetworks.asStateFlow()
```

- [ ] **Step 3: Add the mutating functions**

```kotlin
    fun refreshTrustedNetworks() {
        _trustedNetworks.value = TrustedNetworks.list(getApplication())
    }

    fun addTrustedNetwork(network: TrustedNetwork) {
        TrustedNetworks.add(getApplication(), network)
        refreshTrustedNetworks()
    }

    fun removeTrustedNetwork(ssid: String) {
        TrustedNetworks.remove(getApplication(), ssid)
        refreshTrustedNetworks()
    }

    fun updateTrustedNetwork(network: TrustedNetwork) {
        TrustedNetworks.update(getApplication(), network)
        refreshTrustedNetworks()
    }

    /**
     * Turning Auto mode on also enables boot-autostart (a watcher that
     * doesn't survive a reboot silently stops doing anything) and starts
     * the service watching immediately, rather than waiting for the next
     * reboot to have any effect. Turning it off fully stops the service.
     */
    fun setAutoNetworkModeEnabled(enabled: Boolean) {
        AntiKillPreferences.setAutoNetworkModeEnabled(getApplication(), enabled)
        _autoNetworkModeEnabled.value = enabled
        val ctx = getApplication<Application>()
        if (enabled) {
            AntiKillPreferences.setAutoStartOnBoot(ctx, true)
            _autoStartOnBoot.value = true
            runCatching {
                val intent = ProxyService.startAutoIntent(ctx)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        } else {
            ctx.startService(ProxyService.disableAutoIntent(ctx))
        }
    }
```

- [ ] **Step 4: Add the import**

```kotlin
import com.dataproxy.util.TrustedNetwork
import com.dataproxy.util.TrustedNetworks
```

- [ ] **Step 5: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/dataproxy/ui/viewmodel/MainViewModel.kt
git commit -m "Wire auto network mode and trusted-network CRUD into MainViewModel"
```

---

### Task 8: TrustedNetworksScreen

**Files:**
- Create: `app/src/main/java/com/dataproxy/ui/screens/TrustedNetworksScreen.kt`
- Modify: `app/src/main/java/com/dataproxy/ui/screens/ListenAddressScreen.kt` (visibility only, see Step 0)

**Interfaces:**
- Consumes: `MainViewModel.trustedNetworks`/`currentWifiSsid`/`addTrustedNetwork`/`removeTrustedNetwork`/`updateTrustedNetwork` (Task 7), `TrustedNetwork` (Task 1), `NetworkInterfaceLister` + the existing `AddressRow`/`PortField`/`TopBar`/`HintBanner` composables already defined in `ListenAddressScreen.kt` (same package, no import needed — Kotlin same-package visibility).
- Produces: `@Composable fun TrustedNetworksScreen(viewModel: MainViewModel, onBack: () -> Unit)` — Task 10 (`AppNav`) calls this by name.

**Pre-flight note:** `TopBar`/`HintBanner` in `ListenAddressScreen.kt` are already `internal` (cross-file visible within the module), but `AddressRow` and `PortField` in that same file are currently `private` — a top-level `private` declaration in Kotlin is file-private, not package-private, so `TrustedNetworksScreen.kt` cannot call them as-is. Step 0 widens just those two, matching the two that are already cross-file visible.

- [ ] **Step 0: Widen `AddressRow`/`PortField` visibility in `ListenAddressScreen.kt`**

Change `private fun AddressRow(` to `internal fun AddressRow(`, and `private fun PortField(` to `internal fun PortField(`. No other change to that file.

- [ ] **Step 1: Write the screen**

```kotlin
package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning as WarningColor
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.TrustedNetwork

@Composable
fun TrustedNetworksScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val networks by viewModel.trustedNetworks.collectAsStateWithLifecycle()
    val currentSsid by viewModel.currentWifiSsid.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TrustedNetwork?>(null) }
    var addingManual by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(title = "Trusted networks", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        if (currentSsid != null && networks.none { it.ssid == currentSsid }) {
            Button(
                onClick = {
                    editing = TrustedNetwork(
                        ssid = currentSsid!!,
                        address = "0.0.0.0",
                        port = ProxyService.DEFAULT_PORT,
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trust \"$currentSsid\"", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { addingManual = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add network manually")
        }
        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (networks.isEmpty()) {
                Text(
                    text = "No trusted networks yet",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            networks.forEach { network ->
                TrustedNetworkRow(
                    network = network,
                    isCurrent = network.ssid == currentSsid,
                    onEdit = { editing = network },
                    onDelete = { viewModel.removeTrustedNetwork(network.ssid) },
                )
            }
        }
    }

    val editTarget = editing ?: if (addingManual) TrustedNetwork("", "0.0.0.0", ProxyService.DEFAULT_PORT) else null
    if (editTarget != null) {
        TrustedNetworkEditDialog(
            initial = editTarget,
            allowSsidEdit = addingManual,
            onDismiss = { editing = null; addingManual = false },
            onSave = { network ->
                if (networks.any { it.ssid == network.ssid }) {
                    viewModel.updateTrustedNetwork(network)
                } else {
                    viewModel.addTrustedNetwork(network)
                }
                editing = null
                addingManual = false
            },
        )
    }
}

@Composable
private fun TrustedNetworkRow(
    network: TrustedNetwork,
    isCurrent: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Accent.copy(alpha = 0.08f) else SurfaceMid)
            .border(
                width = if (isCurrent) 1.dp else 0.dp,
                color = if (isCurrent) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (network.lastBindError != null) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = "Bind failed: ${network.lastBindError}",
                tint = WarningColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
            )
            Text(
                text = "${network.address}:${network.port}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TrustedNetworkEditDialog(
    initial: TrustedNetwork,
    allowSsidEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrustedNetwork) -> Unit,
) {
    var ssid by remember { mutableStateOf(initial.ssid) }
    var address by remember { mutableStateOf(initial.address) }
    var port by remember { mutableStateOf(initial.port) }
    val candidates = remember { NetworkInterfaceLister.list() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceMid,
        titleContentColor = TextPrimary,
        title = { Text(if (allowSsidEdit) "Add network" else "Edit \"${initial.ssid}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (allowSsidEdit) {
                    androidx.compose.material3.OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("Network name (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                candidates.forEach { cand ->
                    AddressRow(
                        candidate = cand,
                        selected = cand.address == address,
                        enabled = true,
                        onClick = { address = cand.address },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                PortField(port = port, enabled = true, onChange = { port = it })
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (ssid.isNotBlank()) onSave(TrustedNetwork(ssid.trim(), address, port, initial.lastBindError)) },
            ) { Text("Save", color = Accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}
```

- [ ] **Step 2: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: `BUILD SUCCESSFUL`. `AddressRow`/`PortField`/`TopBar` resolve via cross-file visibility from `ListenAddressScreen.kt` — Step 0 above is what makes `AddressRow`/`PortField` visible; `TopBar` was already `internal`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dataproxy/ui/screens/TrustedNetworksScreen.kt app/src/main/java/com/dataproxy/ui/screens/ListenAddressScreen.kt
git commit -m "Add TrustedNetworksScreen for managing auto-mode trusted networks"
```

---

### Task 9: AntiKillScreen — Auto network card

**Files:**
- Modify: `app/src/main/java/com/dataproxy/ui/screens/AntiKillScreen.kt`

**Interfaces:**
- Consumes: `MainViewModel.autoNetworkModeEnabled`/`setAutoNetworkModeEnabled` (Task 7).
- Produces: adds an `onOpenTrustedNetworks: () -> Unit` parameter to `AntiKillScreen` — Task 10 (`AppNav`) passes this in.

- [ ] **Step 1: Add the parameter and read the new state**

Change the function signature:

```kotlin
@Composable
fun AntiKillScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenTrustedNetworks: () -> Unit,
) {
```

After the existing `val autoStart by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()`, add:

```kotlin
    val autoNetworkMode by viewModel.autoNetworkModeEnabled.collectAsStateWithLifecycle()
    var locationGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val locationServicesOn = remember {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationGranted = granted
        if (granted) viewModel.setAutoNetworkModeEnabled(true)
    }
```

- [ ] **Step 2: Add the card, right after `AutoStartCard`**

```kotlin
            AutoStartCard(enabled = autoStart, onToggle = viewModel::setAutoStartOnBoot)
            AutoNetworkModeCard(
                enabled = autoNetworkMode,
                locationServicesOn = locationServicesOn,
                onToggle = { turningOn ->
                    if (!turningOn) {
                        viewModel.setAutoNetworkModeEnabled(false)
                    } else if (locationGranted) {
                        viewModel.setAutoNetworkModeEnabled(true)
                    } else {
                        locationLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                onManage = onOpenTrustedNetworks,
            )
```

- [ ] **Step 3: Write the `AutoNetworkModeCard` composable**

Add near `AutoStartCard`:

```kotlin
/**
 * Auto network mode: activates the proxy on a list of trusted Wi-Fi SSIDs
 * and stands it down on departure. Turning this on is the only trigger for
 * the ACCESS_FINE_LOCATION prompt — Android requires it to read the current
 * SSID, and this app never asks for it at install or launch.
 */
@Composable
private fun AutoNetworkModeCard(
    enabled: Boolean,
    locationServicesOn: Boolean,
    onToggle: (Boolean) -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = if (enabled) 0.16f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = if (enabled) Accent else TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Auto network mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (enabled)
                        "Activates on your trusted networks, stands down elsewhere. Also enables \"Start after reboot\"."
                    else
                        "Automatically start on trusted Wi-Fi networks and stop everywhere else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SurfaceLow,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceLow,
                    uncheckedBorderColor = OutlineStrong,
                ),
            )
        }
        if (enabled && !locationServicesOn) {
            Spacer(Modifier.height(10.dp))
            HintBanner("Location services are off — DataProxy can't read the current Wi-Fi network without them. Turn Location on in system settings.")
        }
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onManage,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage trusted networks")
            }
        }
    }
}
```

- [ ] **Step 4: Add the new imports**

```kotlin
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.rememberCoroutineScope
```

(Only add `rememberCoroutineScope` if actually referenced — this draft doesn't need it; skip it. Do add `androidx.compose.material.icons.rounded.Wifi` and confirm `LocalContext`/`context` is already available in scope — `AntiKillScreen` already declares `val context = LocalContext.current` near the top; reuse it, do not redeclare.)

- [ ] **Step 5: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: exactly one error — `AppNav.kt`'s existing `AntiKillScreen(viewModel = viewModel, onBack = ...)` call site is now missing the new required `onOpenTrustedNetworks` argument. That's Task 10.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/dataproxy/ui/screens/AntiKillScreen.kt
git commit -m "Add Auto network mode card to AntiKillScreen"
```

---

### Task 10: Wire the new screen into navigation

**Files:**
- Modify: `app/src/main/java/com/dataproxy/ui/screens/AppNav.kt`

**Interfaces:**
- Consumes: `TrustedNetworksScreen` (Task 8), `AntiKillScreen`'s new `onOpenTrustedNetworks` param (Task 9).

- [ ] **Step 1: Add the new tab**

```kotlin
enum class Tab { Home, ListenAddress, Devices, Auth, AntiKill, TrustedNetworks }
```

- [ ] **Step 2: Wire the screen and fix the `AntiKillScreen` call site**

```kotlin
                Tab.AntiKill -> AntiKillScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                    onOpenTrustedNetworks = { onTabChange(Tab.TrustedNetworks) },
                )
                Tab.TrustedNetworks -> TrustedNetworksScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.AntiKill) },
                )
```

- [ ] **Step 3: Build to verify it compiles**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/dataproxy/ui/screens/AppNav.kt
git commit -m "Wire TrustedNetworksScreen into app navigation"
```

---

### Task 11: On-device verification

**Files:** none (verification only — no source changes expected; fix forward in this task's own commit if a bug surfaces).

This task has no unit-test framework to lean on (none exists in this project). Work through the spec's Testing checklist directly on a real device with the debug build from this branch, against real distinct Wi-Fi networks in range.

- [ ] **Step 1: Build and install**

```bash
gradle :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Permission gating**

Open Anti-Kill screen. Confirm no location prompt has fired yet (check `adb shell dumpsys package com.dataproxy | grep ACCESS_FINE_LOCATION` shows it not granted). Toggle "Auto network mode" on — confirm the system permission dialog fires now, not before. Deny it — confirm the switch reverts off with the explanation shown. Toggle on again and grant it — confirm the switch stays on.

- [ ] **Step 3: Trusted network CRUD**

Open "Manage trusted networks". Tap "Trust current network" — confirm the address-candidate list matches what `ListenAddressScreen` shows for the same network. Save, confirm it appears in the list. Add a second network manually (a name you're not currently on) — confirm it defaults to `0.0.0.0`. Edit an entry's port while the proxy is actively running — confirm it's allowed (unlike `ListenAddressScreen`, which blocks edits while running). Delete an entry — confirm it disappears.

- [ ] **Step 4: Boot behavior**

With Auto mode on, reboot the device. After boot, confirm the notification reads "DataProxy · idle" / "Waiting for a trusted network" — not immediately "Running".

- [ ] **Step 5: Real network transitions**

Join a trusted network — confirm it promotes to Running within a few seconds, notification and address match that entry's stored config. Join a second trusted network with a different stored address — confirm it demotes then re-promotes with the new address. Leave Wi-Fi entirely (airplane-toggle Wi-Fi off) — confirm the notification returns to idle text after roughly 5 seconds, not instantly and not never.

- [ ] **Step 6: Debounce**

While on a trusted network with the proxy Running, toggle Wi-Fi off and back on within 2-3 seconds. Confirm the proxy does not flap — it should stay Running throughout (or, worst case, briefly touch Idle and immediately re-promote without a human noticing a stuck state).

- [ ] **Step 7: Manual override**

While Running on a trusted network, tap Stop from the notification or Home power button. Confirm it goes to idle text and does not immediately restart. Leave the network and rejoin (or join a different trusted network) — confirm it resumes auto-activating.

- [ ] **Step 8: Bind failure**

Edit a trusted entry's address to something invalid for the current network's subnet (e.g. an IP that doesn't exist on this interface) while not currently matched, then join that network. Confirm: a distinct dismissible notification appears (separate from the ongoing one), the entry shows a warning badge in `TrustedNetworksScreen`, and the ongoing notification/state falls back to idle rather than a blocking error.

- [ ] **Step 9: Fix forward**

If any check above fails, fix the specific file involved and re-run that check — do not proceed to sign-off until all eight checks above pass.

- [ ] **Step 10: Commit (only if fixes were needed)**

```bash
git add -A
git commit -m "Fix issues found in on-device auto network mode verification"
```

---

## Self-review notes (for the plan author, not a task)

- **Spec coverage:** State machine (Task 4), SSID watcher (Task 2), storage (Task 1), permissions & UI placement (Tasks 3, 9, 10), per-network listen config (Tasks 1, 8), bind-failure surfacing (Task 5), notification text (Task 4 Step 8), testing (Task 11) — all covered.
- **Scope trim, flagged explicitly:** the spec's bind-failure notification says tapping it "opens `TrustedNetworksScreen` scrolled to that entry." Task 5 implements tap-to-open-the-app (matching the existing ongoing notification's behavior) rather than a scrolled deep link — the per-entry warning badge in Task 8 already makes the failure discoverable once inside the app, and the deep-link plumbing (an `onNewIntent` + pending-nav-target channel through `MainActivity`) is disproportionate additional surface for a secondary polish detail. Worth a follow-up task later if it's wanted, not blocking here.
