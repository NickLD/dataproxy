package com.dataproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dataproxy.MainActivity
import com.dataproxy.R
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.network.WifiSsidWatcher
import com.dataproxy.proxy.AuthConfig
import com.dataproxy.proxy.ConnectionRegistry
import com.dataproxy.proxy.Socks5Server
import com.dataproxy.proxy.SpeedSampler
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.ByteFormatter
import com.dataproxy.util.RateUnit
import com.dataproxy.util.TrustedNetwork
import com.dataproxy.util.TrustedNetworks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Long-lived host for the SOCKS5 listener.
 *
 * Foreground because:
 * 1. We need to keep TCP accept loops + worker threads alive when the UI
 *    goes away.
 * 2. We hold a cellular [android.net.Network] reference — that gets revoked
 *    if the process drops out of the foreground importance bucket.
 *
 * UI binds with [LocalBinder] for state and start/stop control; everything
 * survives unbind because we [startForeground].
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cellular by lazy { CellularNetworkProvider(applicationContext) }
    private val wifiWatcher by lazy { WifiSsidWatcher(applicationContext) }
    private val registry = ConnectionRegistry()
    private val sampler = SpeedSampler()

    private var server: Socks5Server? = null
    private var startJob: Job? = null
    private var publishJob: Job? = null
    private var cellularWatchJob: Job? = null
    private var wifiWatchJob: Job? = null
    private var autoDemoteJob: Job? = null
    private var activeSsid: String? = null
    private val ssidTransitionMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()
    val devices: StateFlow<List<ConnectionRegistry.DeviceSummary>> = registry.devices
    val totals: StateFlow<ConnectionRegistry.Totals> = registry.totals
    val rates: StateFlow<SpeedSampler.Rates> = sampler.rates
    // Computed: defers cellular's by-lazy init until first read (post-onCreate).
    val cellularState: StateFlow<CellularNetworkProvider.State> get() = cellular.state
    val wifiSsidState: StateFlow<String?> get() = wifiWatcher.ssid

    sealed interface State {
        data object Stopped : State
        data class Starting(val bindAddress: String, val port: Int) : State
        data class Running(val bindAddress: String, val port: Int) : State
        /** Listener still bound; cellular link is gone, so outbound connects fail. */
        data class Paused(val bindAddress: String, val port: Int, val reason: String) : State
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
        data class Error(val message: String, val kind: ErrorKind = ErrorKind.Generic) : State

        enum class ErrorKind { Generic, MobileDataUnavailable, BindFailed }
    }

    inner class LocalBinder : Binder() {
        val service: ProxyService get() = this@ProxyService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val addr = intent.getStringExtra(EXTRA_BIND_ADDRESS) ?: "0.0.0.0"
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startProxy(addr, port)
            }
            ACTION_STOP -> {
                stopProxy()
                if (_state.value is State.Stopped) stopSelf()
            }
            ACTION_START_AUTO -> startAutoWatch()
            ACTION_DISABLE_AUTO -> stopAutoWatch()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDemoteJob?.cancel()
        wifiWatchJob?.cancel()
        wifiWatcher.stop()
        fullCleanup()
        scope.coroutineContext[Job]?.cancel()
    }

    // ----------------------------------------------------------------- control

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

    private suspend fun onSsidChanged(ssid: String?) {
        ssidTransitionMutex.withLock {
            autoDemoteJob?.cancel(); autoDemoteJob = null
            val match = ssid?.let { TrustedNetworks.find(applicationContext, it) }
            when (val cur = _state.value) {
                is State.Idle -> if (match != null) promote(match)
                is State.Starting, is State.Running, is State.Paused -> {
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
    }

    private fun promote(network: TrustedNetwork) {
        activeSsid = network.ssid
        startProxy(network.address, network.port, isAutoActivation = true)
    }

    private suspend fun demoteToIdle() {
        ssidTransitionMutex.withLock {
            if (_state.value !is State.Running && _state.value !is State.Paused && _state.value !is State.Starting) return@withLock
            fullCleanup()
            activeSsid = null
            _state.value = State.Idle()
            startForegroundNow()
            // The SSID may have already settled on a different trusted network by
            // the time this debounced check runs (e.g. roaming directly from one
            // trusted network to another) — re-check and promote immediately
            // instead of waiting for a further, distinct SSID emission that may
            // never come (StateFlow is conflated).
            promoteIfCurrentSsidMatches()
        }
    }

    private fun promoteIfCurrentSsidMatches() {
        val currentSsid = wifiWatcher.ssid.value
        val match = currentSsid?.let { TrustedNetworks.find(applicationContext, it) }
        if (match != null) promote(match)
    }

    /**
     * Called after the trusted-network list changes (add/edit) so a
     * newly-trusted network that's already the current connection promotes
     * immediately, instead of waiting for some future distinct SSID
     * emission that may never come (the phone doesn't need to actually
     * change networks for "trust the one I'm on right now" to work).
     */
    suspend fun recheckTrustedNetworks() {
        ssidTransitionMutex.withLock {
            if (_state.value is State.Idle) {
                promoteIfCurrentSsidMatches()
            }
        }
    }

    fun startProxy(bindAddress: String, port: Int, isAutoActivation: Boolean = false) {
        if (_state.value is State.Running || _state.value is State.Starting) return

        // Clear-state + kill: wipe everything from any previous cycle before
        // we touch cellular again. Idempotent on a clean slate.
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
                        TrustedNetworks.setBindError(applicationContext, activeSsid ?: "", e.message ?: "Bind failed")
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

    /**
     * Cancel every coroutine, close the server, unregister the cellular
     * callback, drop the process network binding, and reset counters. Called
     * on both start (to wipe leftover state) and stop. Idempotent.
     */
    private fun fullCleanup() {
        startJob?.cancel(); startJob = null
        publishJob?.cancel(); publishJob = null
        cellularWatchJob?.cancel(); cellularWatchJob = null
        server?.stop(); server = null
        cellular.stop()
        registry.reset()
        sampler.reset()
        releaseWakeLock()
    }

    /**
     * Listen for cellular drops while the proxy is up.
     * Drop → Paused (listener stays, new connects fail until data is back).
     * Recovery → back to Running.
     */
    private fun startCellularWatch(addr: String, port: Int) {
        cellularWatchJob?.cancel()
        cellularWatchJob = scope.launch {
            cellular.state.collect { cs ->
                val cur = _state.value
                when (cs) {
                    is CellularNetworkProvider.State.Available -> {
                        if (cur is State.Paused) {
                            _state.value = State.Running(addr, port)
                            updateNotification()
                        }
                    }
                    is CellularNetworkProvider.State.Lost,
                    is CellularNetworkProvider.State.Unavailable -> {
                        if (cur is State.Running) {
                            _state.value = State.Paused(
                                addr, port,
                                "Waiting for mobile data",
                            )
                            updateNotification()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------- foreground

    private fun startForegroundNow() {
        val notif = buildNotification(totals.value, rates.value)
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse (not dataSync): dataSync foreground services can't be
            // started from a BOOT_COMPLETED receiver on Android 15+, which would
            // break auto-start-on-boot. specialUse is exempt from that rule and
            // works for both UI-initiated and boot-initiated starts. DataProxy
            // ships via GitHub, not Play, so the Play specialUse review gate
            // (which doesn't affect runtime) is a non-issue. See BootReceiver.
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startSampling() {
        publishJob = scope.launch {
            while (true) {
                val (up, down) = registry.snapshotBytes()
                sampler.sample(up, down)
                registry.publish()
                when (_state.value) {
                    is State.Running -> updateNotification()
                    is State.Paused -> updateNotification()
                    else -> Unit
                }
                delay(1000L)
            }
        }
    }

    private fun updateNotification(
        totals: ConnectionRegistry.Totals = this.totals.value,
        rates: SpeedSampler.Rates = this.rates.value,
    ) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(totals, rates))
    }

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
        val notif = NotificationCompat.Builder(this, BIND_FAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle("DataProxy couldn't start on \"$label\"")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        getSystemService(NotificationManager::class.java).notify(BIND_FAIL_NOTIF_ID, notif)
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "DataProxy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing proxy status"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            mgr.createNotificationChannel(ch)
        }
        if (mgr.getNotificationChannel(BIND_FAIL_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                BIND_FAIL_CHANNEL_ID,
                "DataProxy alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Auto-mode activation failures"
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun rateText(bytesPerSec: Long, unit: RateUnit): String {
        val (number, label) = ByteFormatter.rate(bytesPerSec, unit)
        return "$number$label"
    }

    /**
     * Read auth settings live from the same shared prefs the UI writes to.
     * Each new SOCKS5 connection re-reads, so toggling auth on the Auth
     * screen takes effect without restarting the proxy.
     */
    private fun currentAuthConfig(): AuthConfig {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return AuthConfig(
            enabled = prefs.getBoolean(PREF_AUTH_ENABLED, false),
            username = prefs.getString(PREF_AUTH_USERNAME, "") ?: "",
            password = prefs.getString(PREF_AUTH_PASSWORD, "") ?: "",
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DataProxy::ProxyWakeLock",
        ).apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.dataproxy.ACTION_START"
        const val ACTION_STOP = "com.dataproxy.ACTION_STOP"
        const val ACTION_START_AUTO = "com.dataproxy.ACTION_START_AUTO"
        const val ACTION_DISABLE_AUTO = "com.dataproxy.ACTION_DISABLE_AUTO"
        const val EXTRA_BIND_ADDRESS = "extra.bindAddress"
        const val EXTRA_PORT = "extra.port"
        const val DEFAULT_PORT = 1080

        private const val CHANNEL_ID = "dataproxy.status"
        private const val BIND_FAIL_CHANNEL_ID = "dataproxy.alerts"
        private const val NOTIF_ID = 1001
        private const val BIND_FAIL_NOTIF_ID = 1002

        // Mirrored by [com.dataproxy.ui.viewmodel.MainViewModel] — must match.
        const val PREFS_NAME = "dataproxy_prefs"
        const val PREF_AUTH_ENABLED = "auth_enabled"
        const val PREF_AUTH_USERNAME = "auth_username"
        const val PREF_AUTH_PASSWORD = "auth_password"
        // Last-chosen listen address + port. Written by MainViewModel; read by
        // BootReceiver so an auto-start uses the same endpoint as the UI.
        const val PREF_BIND_ADDRESS = "bind_address"
        const val PREF_PORT = "port"
        // Written by MainViewModel; read here fresh on every notification
        // rebuild so a toggle made while the proxy is running takes effect
        // on the next update without a restart.
        const val PREF_RATE_UNIT = "rate_unit"

        fun startIntent(ctx: Context, addr: String, port: Int) =
            Intent(ctx, ProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BIND_ADDRESS, addr)
                .putExtra(EXTRA_PORT, port)

        fun stopIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)

        fun startAutoIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_START_AUTO)

        fun disableAutoIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_DISABLE_AUTO)
    }
}
