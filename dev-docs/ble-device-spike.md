---
title: "WI-10 step 1 — Bluetooth device spike (G5 input) and HP-3 / HP-8 measurement procedure"
created: 2026-10-15
status: "Procedure only. Nothing below has been run. No capacity, MTU, range, latency or battery figure is claimed anywhere in this repository until a filled result table exists in dev-docs/evidence/."
---

# Bluetooth device spike

## 1. Why this exists

Gate G5 (supported device matrix and iOS floor) and the M1b exit both depend on numbers that only real
phones can produce. The software for Bluetooth rooms (WI-10) is built and tested on the JVM against a
fake radio, and a two-emulator room smoke test (`BleTwoDeviceTest`) ran end to end; none of the
following has been observed on phones:

| Question | Decides | Plan reference |
|---|---|---|
| Can each phone in the matrix host (advertise + GATT server) and join (scan + GATT client)? | G5 matrix; whether an iPhone may host | WI-10 step 1, G5 |
| What ATT MTU does each pair negotiate? | Packets per session chunk (1 at MTU >= 185, 13 at MTU 23) | WI-10, GDN-022 |
| How many simultaneous centrals does a host keep (target: 3 players + 2 watchers)? | Whether 4+2 is feasible at all | GDN-071, risk table "BLE cannot carry 4+2" |
| Packet, record and command-to-receipt latency under 4+2 play | HP-3 acceptance: p95 receipt < 1 s | WI-10 acceptance, GDN-071 |
| Behaviour under background, lock, radio loss, process kill per vendor | HP-8 OEM interruption table | GDN-101 |
| Battery and temperature over 60 minutes | HP-3 record | GDN-100 |

If BLE cannot carry 4+2, release needs an explicit LAN-only scope decision by the owner, and ledger
row GDN-019 stays open (plan section 8).

## 2. What the build exposes

Build with the flag on (it is off by default):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew -Pwpgd.bleTransport=true :app:assembleDebug
# APK: app/build-ble/outputs/apk/debug/app-debug.apk
adb install -r app/build-ble/outputs/apk/debug/app-debug.apk
```

While a Bluetooth room is active (hosting, or joined with a link open) the app writes one block of
diagnostics lines every 10 seconds to logcat under the tag `wpgd-ble`:

```sh
adb logcat -v time -s wpgd-ble > spike-<device>-<run>.log
```

The lines carry counts and milliseconds only: no payload, no Bluetooth address, no room id, no
nickname (privacy property P8). Source: `BleReport`, `BleLinkMetrics`, `BleServerStats`,
`BleRadioStats`, `OwnerMetrics.receiptMs`.

| Line | Field | Meaning |
|---|---|---|
| `ble server` | `connected` | GATT connections the host holds now (members plus any advert reader) |
| | `max_connected` | Highest simultaneous connections since the room opened |
| | `subscribed` | Connections that subscribed to the notify characteristic (session members) |
| | `refused` | Connections refused because 8 were already open (`AndroidBleRadio.MAX_CONNECTIONS`) |
| | `advertising`, `advertise_failures` | Advertising state and failed starts |
| | `links_opened` | Session links created on this phone so far |
| `ble radio` | `scan_starts`, `scan_failures`, `scan_results` | Scans started, failed (including Android's 5-starts-per-30-s throttle), results seen |
| | `advert_reads`, `advert_read_failures` | Short GATT connections that read a host's advert |
| | `connect_failures` | Joiner connections that failed before ready (10 s timeout) |
| | `notify_timeouts` | Host notifications the stack never confirmed within 2 s |
| `ble link#k` | `att` | ATT payload in use = negotiated MTU - 3, capped at 512 |
| | `ready_ms` | Joiner: connect → MTU exchange → discovery → subscribed. Host side: ~0 |
| | `att_changes` | MTU changes after ready |
| | `packets_out/in`, `bytes_out/in` | GATT writes or notifications, and their bytes |
| | `records_out/in` | Session chunks sent and reassembled |
| | `send_failures`, `dropped_records` | Writes or notifications the stack reported failed; the record was dropped |
| | `dup_packets`, `v_truncated`, `v_conflict`, `v_length_mismatch`, `v_oversized`, `v_malformed` | Packets refused by reassembly (oversized and malformed close the link) |
| | `queue_high` | Most records waiting at once (window 256) |
| | `packet_ms_{n,p50,p95,max}` | One packet: handed to the stack → confirmed |
| | `record_ms_{n,p50,p95,max}` | One session chunk: queued → last packet confirmed |
| `ble table` | `receipt_ms_{n,p50,p95,max}` | This phone's game commands: handed to the session → receipt. The HP-3 number. |

Throughput over an interval is `Δbytes_out / Δt` between two blocks of the same link.

Configuration the spike should know it is measuring (all in code, change only as a reviewed result of
this spike): writes with response (`WRITE_TYPE_DEFAULT`); one notification in flight across all
centrals on the host; connection priority HIGH; LE 1M PHY; requested MTU 517; advertising mode
LOW_LATENCY, TX power MEDIUM, service UUID only; scan mode LOW_LATENCY with a service-UUID filter;
session framing 160-byte chunk payload (176-byte record).

## 3. Device matrix (fill before running)

Name the phones per vendor as GDN-101 asks (minimum and midrange). Android 12 (API 31) is the floor for
Bluetooth rooms: below it a BLE scan requires a location permission this app never requests.

| ID | Vendor | Model | Android / iOS version | Bluetooth version | Role(s) tested |
|---|---|---|---|---|---|
| D1 | | | | | |
| D2 | | | | | |
| D3 | | | | | |
| D4 | | | | | |
| D5 | | | | | |
| D6 | | | | | |
| I1 | Apple | | | | Host (needs WI-11) |

## 4. Procedure

Radio settings for every run: Wi-Fi off, mobile data off, Bluetooth on, battery saver off unless the
step says otherwise. Record room temperature. Phones on a table within 2 m unless a step says otherwise.

### S1 Roles and discovery (each phone as host once, each as joiner once)

1. Host phone: main menu → 蓝牙牌桌 → 用蓝牙开桌 → grant "Nearby devices" → 开桌. Note whether the gate
   showed CANNOT_HOST (no advertising support).
2. Joiner: 蓝牙牌桌 → 用蓝牙找桌. Start a stopwatch at the tap; stop when the table appears.
3. Join a seat; compare the 6-digit code; host approves. Record time from tap on the seat to the lobby.
4. Copy the first `ble link#` line of the joiner (att, ready_ms).

### S2 Simultaneous connections (4 + 2)

1. One host, three joining players, two joining watchers (six phones).
2. After all five are admitted, record the host's `ble server` line (`connected`, `max_connected`,
   `subscribed`, `refused`).
3. Add a seventh phone searching (an advert reader) and an eighth joining as a watcher (it must be
   refused by the session's two-watcher cap, not by the radio). Record `refused` and whether any
   existing member dropped.

### S3 Load and latency (HP-3 core, 60 minutes)

1. 4 players + 2 watchers, Bluetooth only, Android host. Play continuously for 60 minutes (AI may fill
   a seat only if a person leaves; note it).
2. Keep `adb logcat -s wpgd-ble` running on the host and on every player phone (USB hub, or `adb tcpip`
   is not available with Wi-Fi off: use USB).
3. At the end record, per phone: the last `ble table receipt_ms_p95`, `ble link#` `packet_ms_p95`,
   `record_ms_p95`, `send_failures`, `dropped_records`, and any `v_*` above zero; battery % at start and
   end; skin temperature at end (IR thermometer) or the OS battery temperature.
4. Repeat with an iPhone host once WI-11 exists.

### S4 Interruptions (HP-8 OEM matrix)

For each phone, as joiner and as host, during a deal: (a) app to background 2 min; (b) lock 2 min;
(c) Bluetooth off 20 s then on; (d) walk out of range then back; (e) force-stop the app and reopen;
(f) Doze (`adb shell dumpsys deviceidle force-idle`, then `unforce`). Record what the screen showed,
whether the seat came back without approval (H5), time to recover, and the `ble radio` counters.

## 5. Result tables (copy into dev-docs/evidence/ble-spike-<date>.md)

**R1 Roles and MTU**

| Host | Joiner | Advertises | Found in (s) | Lobby in (s) | att (MTU - 3) | ready_ms | Notes |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

**R2 Simultaneous connections**

| Host | Members admitted | connected | max_connected | subscribed | refused | Drops during S2.3 |
|---|---|---|---|---|---|---|
| | | | | | | |

**R3 Latency and load, 60 minutes, 4 + 2**

| Host | Phone | Role | receipt_ms p50 / p95 / max | packet_ms p95 | record_ms p95 | bytes_out per min | send_failures | dropped_records | Battery start → end | Temp end |
|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | |

**R4 Interruptions (HP-8)**

| Phone | Role | Background | Lock | Radio off/on | Out of range | Force-stop | Doze |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

## 6. Pass criteria (from the plan, not chosen here)

- HP-3: 4 players + 2 spectators, Bluetooth only, 60 minutes, with an Android host and with an iPhone
  host, on the G5 matrix; p95 receipt < 1 s at that load (`receipt_ms_p95` on every player phone).
- HP-8: the interruption table is filled for every G5 device.
- The owner signs G5 from these tables. Until then the app ships Bluetooth only with the flag on in
  test builds; the default build has no Bluetooth entry and declares no Bluetooth permission.

## 7. Known limits to probe (not defects found on hardware; none was available)

- The host sends one notification at a time across all centrals (Android 12 writes the shared
  characteristic per send). Throughput at 4+2 may be bounded by this; R3 shows it.
- Writes use write-with-response for flow control. Write-without-response may be faster; change only
  with R3 evidence.
- A joiner rejoins at the address it found. A host whose random address rotates during a long game is
  not re-found automatically; S4(d) and the 60-minute run show whether it happens.
- Android throttles scans (more than 5 starts in 30 s fail silently, counted in `scan_failures`).
- No iPhone adapter exists yet (WI-11, gate G3): the iPhone-host rows cannot be filled until it does.
