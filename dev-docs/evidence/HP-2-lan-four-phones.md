---
title: "HP-2 — four Android phones and two spectators over LAN, full match (procedure)"
created: 2026-10-15
status: "Procedure only. Not run: it needs six physical Android phones on one Wi-Fi network, a traffic-capture point, and (for a releasable result) signed G1b, G2 and G4."
---

# HP-2 — LAN match with spectators

Plan references: M1a exit criterion 2; WI-09 acceptance ("HP-2 four phones over LAN plus two
spectators; two simultaneous hosts are distinguishable"); ledger GDN-021 (LAN with WAN off),
GDN-009 (two rooms distinguishable), GDN-059 (no inference request), GDN-065–GDN-068 (spectators
and private follow), GDN-085 (rejoin own seat); privacy property P9. Real NSD discovery
(`NsdRoomDirectory`) has no automated test; this procedure is its only device evidence.

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| Rooms are found over real NSD/mDNS on a real Wi-Fi network | `NsdRoomDirectory` runs only on Android with a real network; tests use `InProcessDirectory` |
| Four phones complete a match over TCP with two watchers attached | Real sockets across devices, OEM power management, Wi-Fi power save |
| No request leaves the local network during play (P9) | Needs a capture outside the app |
| Two hosts at once are told apart by table name, host and 桌号 | Two real adverts on one network |

## 2. Preconditions

1. `./tools/ci.sh` ends with `All local checks passed.` on the commit under test.
2. Six Android phones (G5 names the matrix; unsigned today: state the models used). Default build
   (`./gradlew :app:assembleDebug`), installed on all six.
3. One Wi-Fi network with its WAN uplink disconnected (GDN-021 "HP-2 with WAN off"), and a capture
   point on that network.
4. Results are evidence for M1a only after G1b (match profile), G2 (admission/crypto) and G4
   (spectator policy) are signed; before that, record the run as development evidence: the
   diagnostics show `profile.*.provisional=true`.

## 3. Steps

1. Record each phone's model, Android version and battery-saver state. All phones: Wi-Fi on (same
   network), mobile data off, Bluetooth off.
2. Start the capture.
3. Phone H: main menu → 开一桌 → choose 友好局 (friendly; watchers may ask to follow) → 开桌. Note
   the 桌号.
4. Phone H2 (a second host, for GDN-009): also 开一桌 with a different 桌名. On a joiner, 找桌加入
   must list both, each with 房主 and 桌号; record what the list showed. Then end H2's table.
5. Phones P1–P3: 找桌加入 → choose H's table → 坐<seat>; compare the 确认码 with the host's screen;
   host approves. Phones W1–W2: choose 观看（不占座位，不影响 AI）.
6. All players 准备; host taps 开始. Play a full match.
7. During play: W1 asks 请求看<player>的牌; the player taps 同意, later 收回. Expected: W1's
   screen shows the public view again and the private hand is cleared (GDN-067). W2 asks a
   player who taps 不同意 (GDN-066). A second follow request by W1 in the same deal is refused
   (one view per deal, GDN-068).
8. Interruption: turn P2's Wi-Fi off for 20 s, then on. Expected: P2 shows 联系不上房主 …
   and comes back to its own seat without approval; the table follows the room's 有人断线时 setting.
9. At the end, on H and on each player: 设置 → 诊断信息 → 生成诊断信息. Copy `host.revision`,
   `host.rejoined`, `host.grants_activated`, `host.grants_revoked`, `host.follow_frames_dropped`,
   `host.heartbeat_timeouts`, `host.violations`, `client.digest_mismatches`,
   `owner.rejoin_attempts`, `owner.receipt_ms_p95`, `process.crashes`, and the profile fields.
10. Stop the capture.

## 4. Result record

Copy this file to `dev-docs/evidence/HP-2-result-<date>.md` and fill:

| Field | Value |
|---|---|
| Commit, build type | |
| Phones (role, model, Android version, battery saver) | |
| Network (WAN off confirmed how) | |
| Two-room listing (step 4) | |
| Match completed (deals, final levels) | |
| Follow, deny, revoke, second-request results (step 7) | |
| Wi-Fi interruption result (step 8) | |
| Diagnostics excerpts (host and each player) | |
| Capture: any packet to a non-local address from the app | |

Pass: match completes; watchers never held a hand without consent and lost it on revoke;
`host.violations` and `client.digest_mismatches` are 0 on every phone; no packet from the app left
the local network.

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`.
