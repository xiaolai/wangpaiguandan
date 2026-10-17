---
title: "HP-3 — 4 players + 2 spectators over Bluetooth only, 60 minutes, both host platforms (checklist)"
created: 2026-10-15
status: "Procedure only. Not run: it needs six physical phones on the G5 matrix (G5 unsigned), a build with -Pwpgd.bleTransport=true, and for the iPhone-host half the WI-11 client (gate G3)."
---

# HP-3 — Bluetooth load run

Plan references: M1b exit criterion 1; WI-10 acceptance ("HP-3 passes in both host directions; p95
receipt < 1 s at 4+2 load"); WI-06 acceptance (latency cost of three syncs per action measured
here); ledger GDN-019, GDN-071, GDN-100 (battery and temperature record).

The procedure itself is `dev-docs/ble-device-spike.md`: build and logging (section 2), device
matrix (section 3), S2 and S3 (section 4), result tables R2 and R3 (section 5), pass criteria
(section 6). This file only fixes the checklist and the record; it does not restate those steps.

Outcome: not run

## 1. Checklist

- [ ] G5 matrix named (spike section 3 table filled with model, OS version, Bluetooth version)
- [ ] Build: `./gradlew -Pwpgd.bleTransport=true :app:assembleDebug`, APK from
      `app/build-ble/outputs/apk/debug/`, same commit on every phone; `./tools/ci.sh` passed on it
- [ ] Radio settings per phone: Wi-Fi off, mobile data off, Bluetooth on, battery saver off
      (screenshot each)
- [ ] `adb logcat -v time -s wpgd-ble` captured on host and every player for the whole run
- [ ] Spike S2 (4 + 2 admitted; host `ble server` line recorded)
- [ ] Spike S3, Android host, 60 minutes
- [ ] Spike S3, iPhone host, 60 minutes — blocked until WI-11
- [ ] Battery % start/end and end temperature per phone (GDN-100)
- [ ] Diagnostics per phone at the end (设置 → 诊断信息): `owner.receipt_ms_p50/p95/max`,
      `owner.receipts`, `owner.rejoin_attempts`, `host.heartbeat_timeouts`, `host.violations`,
      `client.digest_mismatches`, `host.storage_failures`, `process.crashes`, profile fields

## 2. Result record

Copy spike tables R2 and R3 into `dev-docs/evidence/HP-3-result-<date>.md` and add:

| Field | Value |
|---|---|
| Commit | |
| Host platform | |
| Run length (min) and deals played | |
| Worst player `receipt_ms_p95` (ms) | |
| Members lost and rejoined (count, causes) | |
| Room temperature | |

Pass (plan, not chosen here): every player phone's `receipt_ms_p95` < 1000 ms for the 60-minute
4 + 2 run, with an Android host and with an iPhone host, on the G5 matrix. If Bluetooth fails, a
LAN-only release needs an explicit owner decision and GDN-019 stays open.

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`.
