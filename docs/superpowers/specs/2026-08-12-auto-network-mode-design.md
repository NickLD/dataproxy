# Design: Auto network mode — SSID-based proxy activation

## Problem

DataProxy currently only starts and stops when a user manually taps the
power button. For someone using it as an always-available cellular backup
WAN (e.g. behind a router that fails over to it), this means remembering to
turn it on at home and, just as importantly, remembering to turn it off
before leaving — an unauthenticated SOCKS5 listener left running on an
untrusted Wi-Fi network (a coffee shop, a guest network) is a live exposure
of the phone's cellular data to anyone else on that network segment. This
adds an opt-in **Auto mode**: a list of trusted Wi-Fi networks, each with
its own listen address/port, that the proxy activates on and automatically
stands down from when the phone leaves.

## Scope

- Applies only when **Auto mode is explicitly enabled** (default off). With
  it off, behavior is byte-for-byte identical to today — manual start/stop
  only, no new permission prompts, no new background presence.
- A user-managed list of trusted networks (SSID + listen address + port
  per entry), not a single network.
- The `ACCESS_FINE_LOCATION` permission Android requires to read the
  current SSID is requested **only** when the user turns Auto mode on, via
  the same per-item "Allow" pattern already used for notifications/battery
  optimization in `PermissionsDialog` — never at install or launch.
- Out of scope: BIND command support, cellular-network-based triggers,
  geofencing, a WorkManager polling fallback, a separate watcher process
  (see "Approach" below — folded into the existing `ProxyService`
  instead), per-network auth credentials (auth stays a single global
  setting, unaffected by which trusted network is active).

## Approach

Extend the existing `ProxyService` (already a persistent foreground
service with a documented lifecycle in `CLAUDE.md`) with a lightweight
"watching, not proxying" state, rather than introducing a second
coordinating component. Android has not permitted manifest-declared
connectivity broadcasts since API 24, so *something* has to already be
alive to react to a network change — reusing the one component this app
already keeps alive avoids inventing a second lifecycle to get right. A
separate watcher service that starts/stops `ProxyService` was considered
and rejected: this codebase already paid down one nasty multi-lifecycle
race (the documented "rapid stop→start hang" bug); splitting into two
loosely-coordinated services invites that class of bug again for no real
benefit.

## State machine

`ProxyService.State` gains one new case and one new sub-condition on top
of today's `Stopped` / `Starting` / `Running` / `Paused` / `Error`:

- **`Idle`** — Auto mode is on, the service is foreground and alive, a
  `WifiSsidWatcher` is running, but nothing heavy is up: no
  `Socks5Server`, no cellular `NetworkRequest`, no wake lock. This is the
  resting state whenever no trusted network currently matches. (Named
  `Idle`, not "Standby" — matching the existing `CellularNetworkProvider
  .State.Idle` naming, and reading as passively off rather than
  armed-and-ready.)
- **`ManuallyStopped`** — reached when the user taps the power button off
  while `Running` or `Idle` with Auto mode on. The watcher keeps running
  (so the notification and SSID tracking stay live) but won't
  self-promote back to `Running` on its own — only a genuine SSID
  *transition* (leaving the current network and later matching a trusted
  one again) re-arms it. This keeps the power button meaningful even with
  Auto mode on: a manual stop sticks until you actually move, rather than
  Auto immediately overriding it.
- **`Error` falls back to `Idle`, not `Stopped`, when Auto mode is on.**
  Today a fatal error (e.g. `BindFailed`) calls `fullCleanup()` and drops
  the foreground notification entirely — reasonable when a human is
  watching the screen to see the error and retry. In Auto mode nobody's
  necessarily watching, so the service instead falls back to `Idle` and
  keeps the watcher alive, so it can retry automatically on the next SSID
  transition rather than requiring a manual relaunch. (See "Bind failure
  surfacing" below for how the user still finds out.)

Transitions: `Idle → Starting → Running` on SSID match (reuses the
existing start path verbatim, just supplying that network's own
address/port instead of a global setting — see below). `Running → Idle`
on SSID mismatch persisting past a 5s debounce. `{Running, Idle} →
ManuallyStopped` on a user-initiated stop while Auto mode is on.
`ManuallyStopped → Idle` on the next SSID transition (leave, or a
different match), re-arming normal auto-behavior from there.

## SSID watcher

A new `WifiSsidWatcher`, modeled directly on the existing
`CellularNetworkProvider` (`app/src/main/java/com/dataproxy/network/`):
same `ConnectivityManager.NetworkCallback`-over-`StateFlow` shape, but
requesting `TRANSPORT_WIFI` instead of `TRANSPORT_CELLULAR`, and reading
`WifiInfo.ssid` off `NetworkCapabilities.transportInfo` in
`onCapabilitiesChanged` rather than holding a bindable `Network`. Exposes
`StateFlow<String?>` — the current (unquoted) SSID, or `null` when
unavailable, permission not granted, or system Location services are off
(Android returns a placeholder SSID in that case; the watcher treats it the
same as "unknown"). `ProxyService` compares this against the trusted list
(case-sensitive exact match) to drive the state machine above, debouncing
a "no longer matches" reading for 5 seconds before demoting — this exists
purely to filter transient `<unknown ssid>`/intermediate readings during
ordinary AP roaming, not to preserve any live connection (a real Wi-Fi
drop already kills the TCP sessions between the LAN client and this
phone's listener regardless of what the watcher does).

## Trusted network storage & per-network listen config

Each trusted entry is `(ssid, listenAddress, port)`, not a bare SSID —
different trusted networks are plausibly different subnets, so a single
global listen address (today's model) may simply not exist on a second
trusted network. Stored as a `Set<String>` under a new
`PREF_TRUSTED_NETWORKS` key in the existing `dataproxy_prefs`
`SharedPreferences` file. Each element is the three fields joined by a
literal `|` (pipe) separator — `ssid|address|port` — rather than pulling
in a JSON dependency for three fields. Real-world SSIDs can technically
contain a `|` character, so on save the SSID field is checked for it and
rejected with an inline error ("network names containing `|` aren't
supported") rather than silently mis-parsing later — simpler and more
honest than an escaping scheme for a case this narrow. A small
`TrustedNetworks` util wraps encode/decode/add/remove/update, mirroring
the existing `AntiKillPreferences` style.

Adding the **current** network reuses the existing `NetworkInterfaceLister`
candidate picker and `PortField` from `ListenAddressScreen` inline in the
add flow — real interface candidates are only enumerable for the network
you're actually on, so this is naturally correct at add-time. Adding a
network **manually** (one you're not currently near) defaults its listen
address to the wildcard `0.0.0.0` — always valid regardless of what subnet
you land on later — editable afterward once you're actually connected
there and can see real candidates. Editing an entry's address/port does
**not** require stopping the proxy first (unlike the existing global
`ListenAddressScreen`, which gates edits on `Stopped`/`Error`) — these are
just stored settings, only read at the moment `ProxyService` promotes that
specific SSID to `Running`.

`BootReceiver` simplifies under this design: when Auto mode is on, boot
always brings the service up into `Idle` — it no longer needs a global
last-used address/port at all, since whichever trusted SSID later matches
supplies its own.

## Permissions & UI placement

A new "Auto network" card sits on the existing Anti-Kill screen, right
alongside the current "Start after reboot" card (same pattern: icon,
title, description, `Switch`). Turning Auto mode on is the only trigger
that requests `ACCESS_FINE_LOCATION`, via the same per-item launcher
pattern already used for notifications/battery-opt in `PermissionsDialog`
— not an upfront manifest-only ask. Denying it reverts the switch off with
an inline explanation. **Auto mode implies boot-autostart**: turning it on
also enables "Start after reboot" (surfaced in the UI as linked, not two
independently-forgettable switches) — a watcher that doesn't survive a
reboot silently stops doing anything until the user happens to notice,
which is worse than not having the feature. The card also live-checks
system Location services and shows a warning if they're off (permission
granted but the toggle is functionally useless without it), mirroring how
`MobileDataDialog` already handles "mobile data is off." Tapping the card
(once enabled) opens a new `TrustedNetworksScreen` — a list of saved
entries (SSID + address:port summary, delete action), a "Trust current
network" primary action, and a manual add fallback — styled consistently
with `ListenAddressScreen`.

No new Home nav tile — consistent with the documented "a fourth tile
wraps on 360dp devices" constraint; this reaches through the existing
Anti-Kill entry point instead, the same way Anti-Kill itself doesn't
occupy one of the three main tiles.

## Bind failure surfacing

If a trusted network's stored listen address fails to bind when
`ProxyService` tries to promote it (`Socks5Server`'s existing
`BindFailed` path), silently falling back to `Idle` per the state machine
above isn't enough on its own — the whole point of Auto mode is
unattended reliability, and a silent failure there is worse than the
original "forgot to turn it on" problem, since it creates false
confidence that the backup WAN is live. Two things happen instead:

1. A separate, dismissible, one-shot notification is posted (distinct
   from the persistent ongoing status notification) — e.g. *"DataProxy
   couldn't start on 'HomeWiFi' — 192.168.1.50:1080 unavailable"* —
   tapping it opens `TrustedNetworksScreen` scrolled to that entry.
2. The offending entry is flagged in `TrustedNetworks` storage (a
   last-bind-error marker) so `TrustedNetworksScreen` shows a visible
   warning badge on that row until it either succeeds or is edited,
   independent of whether the one-shot notification was seen or dismissed.

The service still falls back to `Idle` and keeps watching, so if
conditions change (DHCP hands out a valid address, the user fixes the
setting) the next SSID match retries automatically.

## Notification text

Extends the existing three-way notification text (`Running` /
`Paused` / today's would-be `Stopped`) with a fourth: in `Idle`, the
persistent notification reads "DataProxy · idle" / "Waiting for a trusted
network" — echoing the existing "Waiting for mobile data" phrasing used by
`Paused`, for a consistent product voice. This permanent-but-quiet
notification is the real cost of Auto mode, already flagged during
brainstorming: a low-priority icon is always present while Auto mode is
on, in exchange for zero-touch activation.

## Testing

No automated test framework in this project (consistent with the prior
two features). Verified manually on-device against real, distinct Wi-Fi
networks in range (no hotspot stand-in needed):

- Permission is requested only on toggling Auto mode on, not before.
- Denying the permission reverts the toggle with the explanation shown.
- Add current network (candidate picker prefilled correctly), add a
  network manually (defaults to wildcard), edit an entry without needing
  to stop the proxy, delete an entry.
- Boot with Auto mode on brings the service up in `Idle`, not `Running`,
  until a real SSID match occurs.
- Walking between two real trusted networks with different stored
  addresses correctly rebinds to each one's own address/port.
- A brief disconnect/reconnect on the same network does not flap the
  service between `Running` and `Idle` (5s debounce holds).
- Manual stop while on a trusted network sticks (`ManuallyStopped`) and
  does not immediately restart; leaving and rejoining a trusted network
  re-arms it.
- A forced bind failure (misconfigure one entry's address) surfaces the
  one-shot notification, flags the entry, and falls back to `Idle` rather
  than going dark.
- Notification text matches state (`Idle` / `Running` / `Paused`) at every
  transition.

## Out of scope / explicitly not doing

- No BIND (SOCKS5 command 0x02) support — unrelated, unimplemented
  already.
- No cellular-network-based triggers or geofencing — Wi-Fi SSID only.
- No WorkManager polling fallback — the live watcher is the only
  mechanism; considered and rejected as the *primary* mechanism (15 min
  minimum interval defeats the point) and not worth the added complexity
  as a secondary self-heal path for v1.
- No per-network auth credentials — auth stays the single existing global
  setting regardless of which trusted network is active.
- No behavior change whatsoever when Auto mode is off.
