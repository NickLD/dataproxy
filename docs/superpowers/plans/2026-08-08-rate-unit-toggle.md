# Rate Unit Toggle (Mbps / MB-per-second) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user tap the unit label on the Speed card to toggle live upload/download rate display between the existing binary MB/s ladder and standard decimal Mbps, with the choice persisted and reflected in the foreground notification too.

**Architecture:** A new `RateUnit` enum (mirrors the existing `ThemeMode` pattern) plus one consolidated `ByteFormatter.rate(bps, unit)` function replace three existing near-duplicate rate-formatting implementations. `MainViewModel` owns the persisted preference (same `SharedPreferences` file as every other setting) and exposes a `cycleRateUnit()` action; `SpeedometerCard`'s unit labels become tappable. `ProxyService` reads the same preference key fresh each time it rebuilds its notification — no new plumbing between service and ViewModel, matching how auth config is already read live.

**Tech Stack:** Kotlin, Jetpack Compose, `SharedPreferences` — no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-08-rate-unit-toggle-design.md`

## Global Constraints

- No new dependencies — plain Kotlin/Android SDK only.
- Default unit is `RateUnit.BytesPerSecond` — existing users see no behavior change until they tap the toggle.
- Scope is rate displays only (Speed card, notification). Cumulative byte totals (Traffic usage card, Devices screen) are untouched.
- No new screen or nav tile. Tap-to-cycle on the existing Speed card only.
- No comments explaining *what* code does — only ones explaining non-obvious *why*, matching this codebase's existing style.
- This project has no unit test framework (no `app/src/test` source set). Verification is: does it compile (via this fork's CI, `.github/workflows/build.yml`, already present on this branch), and — for the final task — manual on-device behavior check.
- One deviation from the design doc, made during planning for consistency with the existing codebase: the persisted preference key is declared as `ProxyService.PREF_RATE_UNIT` (a shared constant, like `PREF_BIND_ADDRESS`/`PREF_PORT`), not as a `MainViewModel`-private constant like `KEY_THEME_MODE`. Unlike theme, this preference is read by both `MainViewModel` (write) and `ProxyService` (read, for the notification), so it needs to live where both can reference it without duplicating the string literal — exactly the existing pattern `PREF_BIND_ADDRESS`/`PREF_PORT` already use for the same reason.

## CI build-verification procedure (reused by every task below)

This fork already has a working CI workflow on this branch. After each task's code change:

```bash
cd "C:\Users\NICKLD\claudedataproxy\dataproxy"
git add -A
git commit -m "<task commit message>"
git push origin rate-unit-toggle
gh workflow run "Build debug APK" --repo NickLD/dataproxy --ref rate-unit-toggle
```

Then poll (the run ID is printed as a URL by the previous command — extract the numeric ID from it):

```bash
gh run list --repo NickLD/dataproxy --branch rate-unit-toggle --limit 1 --json databaseId,status,conclusion
```

Poll every ~15s until `status` is `completed`. Expected: `conclusion: "success"`. If it fails, fetch logs with `gh run view <id> --repo NickLD/dataproxy --log-failed` and fix before moving to the next task.

---

### Task 1: `RateUnit` enum

**Files:**
- Create: `app/src/main/java/com/dataproxy/util/RateUnit.kt`

**Interfaces:**
- Produces: `RateUnit` enum with values `BytesPerSecond`, `Mbps`, each with a `.key: String` property; `RateUnit.fromKey(key: String?): RateUnit` companion function defaulting to `BytesPerSecond` on null/unrecognized input.

- [ ] **Step 1: Create the enum**

```kotlin
package com.dataproxy.util

/**
 * User preference for how live transfer rates are displayed.
 *
 * [BytesPerSecond] is the historical binary (÷1024) B/s ladder.
 * [Mbps] is the decimal (÷1000) bits-per-second ladder networking tools
 * conventionally use for speed. The two are not simple scalar multiples of
 * each other. Cycled by tapping the unit label on the Speed card.
 */
enum class RateUnit(val key: String) {
    BytesPerSecond("bytes"),
    Mbps("mbps");

    companion object {
        fun fromKey(key: String?): RateUnit =
            entries.firstOrNull { it.key == key } ?: BytesPerSecond
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run the CI build-verification procedure above with commit message:
```
Add RateUnit preference enum
```
Expected: CI run concludes `success`.

---

### Task 2: `ByteFormatter.rate(bps, unit)`

**Files:**
- Modify: `app/src/main/java/com/dataproxy/util/ByteFormatter.kt`

**Interfaces:**
- Consumes: `RateUnit` (from Task 1).
- Produces: `ByteFormatter.rate(bps: Long, unit: RateUnit): Pair<String, String>` — first element is the formatted number (no unit suffix), second is the unit label (e.g. `"MB/s"` or `"Mbps"`). Replaces the previous single-argument `ByteFormatter.rate(bps: Long): String`, which nothing else in the codebase calls (verified — only `ByteFormatter.bytes()` and `ByteFormatter.elapsed()` have other callers).

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.dataproxy.util

object ByteFormatter {

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")
    private val bytesPerSecUnits = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    private val mbpsUnits = arrayOf("bps", "Kbps", "Mbps", "Gbps")

    fun bytes(n: Long): String = humanise(n, sizeUnits)

    fun rate(bps: Long, unit: RateUnit): Pair<String, String> = when (unit) {
        RateUnit.BytesPerSecond -> humanisePair(bps.toDouble(), 1024.0, bytesPerSecUnits)
        RateUnit.Mbps -> humanisePair(bps.toDouble() * 8.0, 1000.0, mbpsUnits)
    }

    private fun humanise(n: Long, units: Array<String>): String {
        if (n < 0) return "—"
        var v = n.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return when {
            i == 0 -> "$n ${units[i]}"
            v >= 100 -> "%.0f %s".format(v, units[i])
            v >= 10 -> "%.1f %s".format(v, units[i])
            else -> "%.2f %s".format(v, units[i])
        }
    }

    private fun humanisePair(value: Double, base: Double, units: Array<String>): Pair<String, String> {
        if (value <= 0) return "0" to units[0]
        var v = value
        var i = 0
        while (v >= base && i < units.lastIndex) { v /= base; i++ }
        val number = when {
            i == 0 -> v.toLong().toString()
            v >= 100 -> "%.0f".format(v)
            v >= 10 -> "%.1f".format(v)
            else -> "%.2f".format(v)
        }
        return number to units[i]
    }

    fun elapsed(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val secs = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
        return when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m ${secs % 60}s"
            else -> "%dh %dm".format(secs / 3600, (secs % 3600) / 60)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run the CI build-verification procedure with commit message:
```
Add unit-aware rate formatting to ByteFormatter
```
Expected: CI run concludes `success`. (Nothing calls the new `rate()` overload yet — that's Tasks 4 and 5 — so this is purely a compile check.)

---

### Task 3: Wire the preference into `MainViewModel` and `ProxyService`

**Files:**
- Modify: `app/src/main/java/com/dataproxy/service/ProxyService.kt:353-372` (companion object)
- Modify: `app/src/main/java/com/dataproxy/ui/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `RateUnit` (Task 1).
- Produces: `ProxyService.PREF_RATE_UNIT: String` constant (`"rate_unit"`). `MainViewModel.rateUnit: StateFlow<RateUnit>` and `MainViewModel.cycleRateUnit(): Unit`.

- [ ] **Step 1: Add the shared preference key to `ProxyService`**

In the companion object, add `PREF_RATE_UNIT` next to the existing `PREF_BIND_ADDRESS`/`PREF_PORT` (around line 369-371 currently reading):

```kotlin
        // Last-chosen listen address + port. Written by MainViewModel; read by
        // BootReceiver so an auto-start uses the same endpoint as the UI.
        const val PREF_BIND_ADDRESS = "bind_address"
        const val PREF_PORT = "port"
```

change to:

```kotlin
        // Last-chosen listen address + port. Written by MainViewModel; read by
        // BootReceiver so an auto-start uses the same endpoint as the UI.
        const val PREF_BIND_ADDRESS = "bind_address"
        const val PREF_PORT = "port"
        // Written by MainViewModel; read here fresh on every notification
        // rebuild so a toggle made while the proxy is running takes effect
        // on the next update without a restart.
        const val PREF_RATE_UNIT = "rate_unit"
```

- [ ] **Step 2: Add the import to `MainViewModel.kt`**

Add alongside the existing `com.dataproxy.ui.theme.ThemeMode` import:

```kotlin
import com.dataproxy.util.RateUnit
```

- [ ] **Step 3: Add the state, next to `_themeMode`/`themeMode` (currently lines 78-81)**

```kotlin
    private val _themeMode = MutableStateFlow(
        ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _rateUnit = MutableStateFlow(
        RateUnit.fromKey(prefs.getString(ProxyService.PREF_RATE_UNIT, null))
    )
    val rateUnit: StateFlow<RateUnit> = _rateUnit.asStateFlow()
```

- [ ] **Step 4: Add the cycle function, next to `cycleThemeMode()` (currently lines 163-171)**

```kotlin
    fun cycleThemeMode() {
        val next = when (_themeMode.value) {
            ThemeMode.System -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.Dark
            ThemeMode.Dark -> ThemeMode.System
        }
        _themeMode.value = next
        prefs.edit().putString(KEY_THEME_MODE, next.key).apply()
    }

    fun cycleRateUnit() {
        val next = if (_rateUnit.value == RateUnit.BytesPerSecond) RateUnit.Mbps else RateUnit.BytesPerSecond
        _rateUnit.value = next
        prefs.edit().putString(ProxyService.PREF_RATE_UNIT, next.key).apply()
    }
```

- [ ] **Step 5: Verify it compiles**

Run the CI build-verification procedure with commit message:
```
Add rate unit preference to MainViewModel and ProxyService
```
Expected: CI run concludes `success`. (Nothing reads `rateUnit`/calls `cycleRateUnit()` yet — that's Tasks 4 and 5.)

---

### Task 4: Tappable unit label in `SpeedometerCard`, wired from `HomeScreen`

**Files:**
- Modify: `app/src/main/java/com/dataproxy/ui/components/SpeedometerCard.kt`
- Modify: `app/src/main/java/com/dataproxy/ui/screens/HomeScreen.kt:78, 128`

**Interfaces:**
- Consumes: `RateUnit` (Task 1), `ByteFormatter.rate(bps, unit)` (Task 2), `MainViewModel.rateUnit`/`cycleRateUnit()` (Task 3).
- Produces: `SpeedometerCard(upBps, downBps, rateUnit, onCycleRateUnit)` — two new required parameters on the existing composable.

This task must land as one commit — changing `SpeedometerCard`'s signature and updating its only call site together, or the project won't compile in between.

- [ ] **Step 1: Update `SpeedometerCard.kt`'s imports**

Add:
```kotlin
import androidx.compose.foundation.clickable
import com.dataproxy.util.ByteFormatter
import com.dataproxy.util.RateUnit
```

- [ ] **Step 2: Add `rateUnit`/`onCycleRateUnit` params to `SpeedometerCard` and pass them through**

Current (lines 42-73):

```kotlin
@Composable
fun SpeedometerCard(
    upBps: Long,
    downBps: Long,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Proxy speed",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedTile(
                label = "Download",
                icon = Icons.Rounded.ArrowDownward,
                bps = downBps,
                color = Info,
                modifier = Modifier.weight(1f),
            )
            SpeedTile(
                label = "Upload",
                icon = Icons.Rounded.ArrowUpward,
                bps = upBps,
                color = Accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
```

Replace with:

```kotlin
@Composable
fun SpeedometerCard(
    upBps: Long,
    downBps: Long,
    rateUnit: RateUnit,
    onCycleRateUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Proxy speed",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedTile(
                label = "Download",
                icon = Icons.Rounded.ArrowDownward,
                bps = downBps,
                color = Info,
                rateUnit = rateUnit,
                onUnitClick = onCycleRateUnit,
                modifier = Modifier.weight(1f),
            )
            SpeedTile(
                label = "Upload",
                icon = Icons.Rounded.ArrowUpward,
                bps = upBps,
                color = Accent,
                rateUnit = rateUnit,
                onUnitClick = onCycleRateUnit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
```

- [ ] **Step 3: Update `SpeedTile` to take `rateUnit`/`onUnitClick`, use `ByteFormatter.rate`, and make the unit label clickable**

Current (lines 75-126):

```kotlin
@Composable
private fun SpeedTile(
    label: String,
    icon: ImageVector,
    bps: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val displayValue = remember(bps) { formatRate(bps) }
    val animated by animateFloatAsState(
        targetValue = normaliseBps(bps),
        animationSpec = tween(600),
        label = "speed-bar",
    )
    val outlineSoftColor = OutlineSoft
    Column(
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = displayValue.first,
                color = TextPrimary,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = displayValue.second,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                val baseY = size.height / 2
                drawLine(
                    color = outlineSoftColor,
                    start = Offset(0f, baseY),
                    end = Offset(size.width, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(0f, baseY),
                    end = Offset(size.width * animated, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
```

Replace with (only the `rateUnit`/`onUnitClick` params, the `displayValue` line, and the unit `Text`'s modifier change — rest is unchanged):

```kotlin
@Composable
private fun SpeedTile(
    label: String,
    icon: ImageVector,
    bps: Long,
    color: Color,
    rateUnit: RateUnit,
    onUnitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayValue = remember(bps, rateUnit) { ByteFormatter.rate(bps, rateUnit) }
    val animated by animateFloatAsState(
        targetValue = normaliseBps(bps),
        animationSpec = tween(600),
        label = "speed-bar",
    )
    val outlineSoftColor = OutlineSoft
    Column(
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = displayValue.first,
                color = TextPrimary,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = displayValue.second,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clickable(onClick = onUnitClick),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                val baseY = size.height / 2
                drawLine(
                    color = outlineSoftColor,
                    start = Offset(0f, baseY),
                    end = Offset(size.width, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(0f, baseY),
                    end = Offset(size.width * animated, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
```

Note: `Modifier.clickable(onClick = ...)` uses the default ripple/indication — do not use `material3.ripple`, it doesn't exist in this project's Compose BOM (documented in `CLAUDE.md`).

- [ ] **Step 4: Delete the now-unused private `formatRate` function**

Delete (currently lines 160-173):

```kotlin
private fun formatRate(bps: Long): Pair<String, String> {
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    if (bps <= 0) return "0" to units[0]
    var v = bps.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val number = when {
        i == 0 -> bps.toString()
        v >= 100 -> "%.0f".format(v)
        v >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
    return number to units[i]
}
```

- [ ] **Step 5: Wire `HomeScreen.kt`**

Add the state read after the existing `rates` line (currently line 78, `val rates by viewModel.rates.collectAsStateWithLifecycle()`):

```kotlin
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val rateUnit by viewModel.rateUnit.collectAsStateWithLifecycle()
```

Update the `SpeedometerCard` call site (currently line 128):

```kotlin
        SpeedometerCard(upBps = rates.upBps, downBps = rates.downBps)
```

replace with:

```kotlin
        SpeedometerCard(
            upBps = rates.upBps,
            downBps = rates.downBps,
            rateUnit = rateUnit,
            onCycleRateUnit = { viewModel.cycleRateUnit() },
        )
```

- [ ] **Step 6: Verify it compiles**

Run the CI build-verification procedure with commit message:
```
Make Speed card unit label tappable to cycle rate display unit
```
Expected: CI run concludes `success`.

---

### Task 5: Notification uses the same preference

**Files:**
- Modify: `app/src/main/java/com/dataproxy/service/ProxyService.kt`

**Interfaces:**
- Consumes: `RateUnit`/`RateUnit.fromKey` (Task 1), `ByteFormatter.rate(bps, unit)` (Task 2), `ProxyService.PREF_RATE_UNIT` (Task 3, already added to this file).

- [ ] **Step 1: Add imports**

```kotlin
import com.dataproxy.util.ByteFormatter
import com.dataproxy.util.RateUnit
```

- [ ] **Step 2: Replace the `sub` line in `buildNotification` and remove `humanRate`**

Current (`buildNotification`, currently around lines 280-283):

```kotlin
        val isPaused = _state.value is State.Paused
        val title = if (isPaused) "DataProxy · paused" else "DataProxy · $addr:$port"
        val sub = if (isPaused) "Waiting for mobile data"
        else "${totals.active} conn  ·  ↑${humanRate(rates.upBps)}  ↓${humanRate(rates.downBps)}"
```

Replace with:

```kotlin
        val isPaused = _state.value is State.Paused
        val title = if (isPaused) "DataProxy · paused" else "DataProxy · $addr:$port"
        val rateUnit = RateUnit.fromKey(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RATE_UNIT, null)
        )
        val sub = if (isPaused) "Waiting for mobile data"
        else "${totals.active} conn  ·  ↑${rateText(rates.upBps, rateUnit)}  ↓${rateText(rates.downBps, rateUnit)}"
```

Delete the now-unused `humanRate` function (currently lines 317-323):

```kotlin
    private fun humanRate(bps: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bps.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${bps}${units[i]}" else "%.1f%s".format(v, units[i])
    }
```

Replace it with:

```kotlin
    private fun rateText(bps: Long, unit: RateUnit): String {
        val (number, label) = ByteFormatter.rate(bps, unit)
        return "$number$label"
    }
```

(Same call site, same no-space format as the original — `"1.2MB/s"` for bytes mode, `"12.4Mbps"` for Mbps mode.)

- [ ] **Step 3: Verify it compiles**

Run the CI build-verification procedure with commit message:
```
Read rate unit preference in the notification
```
Expected: CI run concludes `success`.

---

### Task 6: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Download the built APK from the Task 5 CI run**

Get the most recent successful run's ID for this branch:

```bash
gh run list --repo NickLD/dataproxy --branch rate-unit-toggle --limit 1 --json databaseId,conclusion
```

This prints a JSON array with one object; use its `databaseId` value as `<run-id>` below.

```bash
SCRATCH="C:\Users\NICKLD\AppData\Local\Temp\claude\C--Users-NICKLD-claudedataproxy\79f218bd-adb1-4f48-8308-099b56f04da4\scratchpad"
mkdir -p "$SCRATCH/rate-unit-apk"
gh run download <run-id> --repo NickLD/dataproxy -n dataproxy-debug-apk -D "$SCRATCH/rate-unit-apk"
```

- [ ] **Step 2: Install over the existing debug build**

```bash
ADB="$SCRATCH/platform-tools/adb.exe"
"$ADB" uninstall com.dataproxy.debug
"$ADB" install "$SCRATCH/rate-unit-apk/app-debug.apk"
"$ADB" shell dumpsys deviceidle whitelist +com.dataproxy.debug
"$ADB" shell pm grant com.dataproxy.debug android.permission.POST_NOTIFICATIONS
```

- [ ] **Step 3: Wake the device and launch the app**

The phone must be physically unlocked already (fingerprint/PIN can't be driven via adb) — if `mWakefulness` below isn't `Awake`, ask the user to unlock it first.

```bash
"$ADB" shell dumpsys power | grep mWakefulness=
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am start -n com.dataproxy.debug/com.dataproxy.MainActivity
sleep 2
"$ADB" exec-out screencap -p > "$SCRATCH/verify1.png"
```

Read `$SCRATCH/verify1.png`. This is a fresh install (Task 6 Step 2 uninstalled first), so it should show "PROXY OFFLINE" at the default `0.0.0.0:1080` — the exact port doesn't matter for this test, nothing production-routes to it.

- [ ] **Step 4: Start the proxy**

Tap the power button (center of the screen, screen resolution 1080×2404 on this device):

```bash
"$ADB" shell input tap 449 494
sleep 2
"$ADB" exec-out screencap -p > "$SCRATCH/verify2.png"
```

Read `$SCRATCH/verify2.png`. On a fresh install this shows the "Keep DataProxy alive" permissions dialog — tap "Done" to proceed without granting anything (not needed for this quick local test):

```bash
"$ADB" shell input tap 797 1913
sleep 2
"$ADB" exec-out screencap -p > "$SCRATCH/verify3.png"
```

Read `$SCRATCH/verify3.png` and confirm it now shows "ONLINE · 0.0.0.0:1080".

- [ ] **Step 5: Generate traffic through the proxy**

```bash
"$ADB" forward tcp:11080 tcp:1080
curl --socks5-hostname 127.0.0.1:11080 -s -o /dev/null --max-time 30 \
  -w '%{http_code} %{time_total}s\n' \
  "https://speed.cloudflare.com/__down?bytes=20000000"
```

Run this 2-3 times back to back (or in parallel with `&`/`wait`) so the Speed card has a moment of non-zero, easily-readable up/down rates to screenshot in the next steps.

- [ ] **Step 6: Verify the default state**

```bash
"$ADB" exec-out screencap -p > "$SCRATCH/verify4.png"
```

Read `$SCRATCH/verify4.png`. Confirm both Speed tiles show a byte-based unit (`B/s`/`KB/s`/`MB/s`) — unchanged default from before this feature.

- [ ] **Step 7: Verify tapping toggles the unit**

Tap the download tile's unit label (approximate location: right of the large number, lower-right area of the left Speed tile — take a screenshot first if unsure of exact coordinates on this run's layout) and screenshot again:

```bash
"$ADB" shell input tap 260 970
sleep 1
"$ADB" exec-out screencap -p > "$SCRATCH/verify5.png"
```

Read `$SCRATCH/verify5.png`. Confirm **both** tiles (download and upload) now show `bps`/`Kbps`/`Mbps`, and that the numbers are the expected ~8× the byte-based value scaled to the decimal ladder (e.g. ~500 KB/s ≈ 4 Mbps). If the tap missed the label, re-read the screenshot to find the label's actual position and retry.

- [ ] **Step 8: Verify the notification matches**

```bash
"$ADB" shell cmd statusbar expand-notifications
sleep 1
"$ADB" exec-out screencap -p > "$SCRATCH/verify6.png"
```

Read `$SCRATCH/verify6.png`. Confirm the DataProxy notification's `↑`/`↓` values are in the same unit (Mbps) currently selected in-app.

- [ ] **Step 9: Verify persistence**

```bash
"$ADB" shell cmd statusbar collapse
"$ADB" shell am force-stop com.dataproxy.debug
"$ADB" shell am start -n com.dataproxy.debug/com.dataproxy.MainActivity
sleep 2
"$ADB" exec-out screencap -p > "$SCRATCH/verify7.png"
```

Read `$SCRATCH/verify7.png`. Confirm the Mbps unit is still in effect without needing to re-tap (the app was never uninstalled between steps, only force-stopped, so `SharedPreferences` persisted).

- [ ] **Step 10: Verify toggling back**

```bash
"$ADB" shell input tap 260 970
sleep 1
"$ADB" exec-out screencap -p > "$SCRATCH/verify8.png"
```

Read `$SCRATCH/verify8.png`. Confirm it returns to the byte-based ladder on both tiles. Optionally re-check the notification (Step 8's commands) to confirm it also switched back.
