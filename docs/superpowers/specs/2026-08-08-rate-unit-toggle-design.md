# Design: Mbps/MB-per-second toggle for displayed data rates

## Problem

DataProxy currently displays live upload/download rates only as byte-based
units (`B/s`/`KB/s`/`MB/s`/`GB/s`), both in the in-app Speed card and in the
persistent foreground notification. Cellular and network speeds are
conventionally reported in bits per second (Mbps), so users comparing
DataProxy's numbers against a carrier's advertised speed, a speed-test app,
or a router's throughput display currently have to mentally convert. This
adds a user-togglable unit for rate display: bytes/second (existing) or
Mbps (new).

## Scope

- Applies to **rate** displays only (Speed card, notification `↑X ↓Y`).
- Does **not** apply to cumulative byte totals (Traffic usage card, Devices
  screen per-device totals) — those remain byte-based; "megabits
  transferred" isn't a meaningful way to express a cumulative total the way
  it is for a rate.

## Data model

A new `RateUnit` enum in `com.dataproxy.util`, mirroring the existing
`ThemeMode` pattern (`app/src/main/java/com/dataproxy/ui/theme/ThemeMode.kt`)
exactly:

```kotlin
enum class RateUnit(val key: String) {
    BytesPerSecond("bytes"),
    Mbps("mbps");

    companion object {
        fun fromKey(key: String?): RateUnit =
            entries.firstOrNull { it.key == key } ?: BytesPerSecond
    }
}
```

Default is `BytesPerSecond` — preserves current behavior for existing users
until they discover the toggle.

## Formatting

Extend `ByteFormatter` (`app/src/main/java/com/dataproxy/util/ByteFormatter.kt`)
with:

```kotlin
fun rate(bps: Long, unit: RateUnit): Pair<String, String>
```

- `RateUnit.BytesPerSecond`: existing binary (÷1024) ladder,
  `B/s → KB/s → MB/s → GB/s`, unchanged from current behavior.
- `RateUnit.Mbps`: convert to bits (`bps * 8.0`), then walk a **decimal**
  (÷1000) ladder, `bps → Kbps → Mbps → Gbps` — standard networking
  convention (matches how carriers/ISPs/speed-test tools report speed;
  deliberately *not* the same ÷1024 base as the byte ladder, so the two
  modes are not simple scalar multiples of each other).
- Same precision rule as the existing formatters: 0 decimals at ≥100, 1
  decimal at ≥10, 2 decimals below 10.

This function replaces the two existing near-duplicate implementations:
- `ProxyService.humanRate()` (private, used for the notification subtext)
- `SpeedometerCard.formatRate()` (private, used by `SpeedTile`)

Both are deleted; both call sites call `ByteFormatter.rate(bps, unit)`
instead. The existing (currently unused) `ByteFormatter.rate(bps: Long)`
single-arg overload is replaced by this two-arg version — nothing else
calls it today, so no migration needed. `ByteFormatter.bytes()` (cumulative
totals) is untouched.

## State & persistence

`MainViewModel` gets a new preference pair, copied from the existing
`_themeMode` / `cycleThemeMode()`:

```kotlin
private val _rateUnit = MutableStateFlow(
    RateUnit.fromKey(prefs.getString(KEY_RATE_UNIT, null))
)
val rateUnit: StateFlow<RateUnit> = _rateUnit.asStateFlow()

fun cycleRateUnit() {
    val next = if (_rateUnit.value == RateUnit.BytesPerSecond) RateUnit.Mbps else RateUnit.BytesPerSecond
    _rateUnit.value = next
    prefs.edit().putString(KEY_RATE_UNIT, next.key).apply()
}
```

Same `dataproxy_prefs` `SharedPreferences` file as every other setting
(`ProxyService.PREFS_NAME`), new key `"rate_unit"` (constant
`KEY_RATE_UNIT`, declared alongside the existing `KEY_THEME_MODE` in
`MainViewModel`'s companion object).

## UI wiring

- `HomeScreen` reads `viewModel.rateUnit` (same `collectAsStateWithLifecycle()`
  pattern as `themeMode`) and passes `rateUnit` + a `onCycleRateUnit: () ->
  Unit` callback down to `SpeedometerCard`.
- `SpeedometerCard` passes both through to each `SpeedTile`. The unit-label
  `Text` (currently static, e.g. `"B/s"`) becomes `clickable {
  onCycleRateUnit() }`. Both tiles (download/upload) read the same
  `RateUnit` and the same callback — there's one global setting, not one
  per tile, so tapping either tile's label toggles both together.
- No new screen, no new nav tile, no layout change — matches the existing
  "tap an icon to cycle a preference" pattern already used for the theme
  toggle in the home header.

## Notification

`ProxyService` doesn't hold UI/ViewModel state and shouldn't start now —
it already reads live preferences on demand (`currentAuthConfig()` reads
`SharedPreferences` fresh on every new connection). `buildNotification()`
(called every 1s from the existing sampling loop, and on every state
transition) does the same: reads `RateUnit` fresh from `SharedPreferences`
each call, no caching, no restart required for a toggle made while the
proxy is running to take effect on the next notification update (≤1s
later).

## Error handling

None needed beyond what `ByteFormatter.humanise()` already does (`n < 0`
returns `"—"`). Unit lookup falls back to `BytesPerSecond` on an unrecognized
or missing preference key, same fallback pattern as `ThemeMode.fromKey()`.

## Testing

No test framework exists in this project currently (no test source dir).
Consistent with how the concurrency fix was validated: build, install,
manually verify both units render correctly at a range of magnitudes
(sub-1, double-digit, triple-digit, unit-rollover boundaries), verify the
setting persists across an app restart, and verify the notification's rate
matches the in-app card's unit at all times.

## Out of scope / explicitly not doing

- No per-tile (upload vs. download) independent unit — one global setting.
- No settings/preferences screen — this is the only toggle-by-tap
  preference besides theme; a dedicated settings screen isn't warranted by
  one more boolean.
- No change to cumulative byte totals (Traffic usage card, Devices screen).
