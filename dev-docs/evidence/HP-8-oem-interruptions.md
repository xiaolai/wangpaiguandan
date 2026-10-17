---
title: "HP-8 — OEM interruption matrix on every G5 device (checklist)"
created: 2026-10-15
status: "Procedure only. Not run: it needs every phone of the G5 matrix (G5 unsigned) and, for the iPhone rows, the WI-11 client."
---

# HP-8 — OEM interruption matrix

Plan references: section 6 HP-8; WI-10 acceptance ("OEM interruption table filled for the G5
matrix"); M1b exit criterion 5; ledger GDN-101. The Bluetooth procedure is
`dev-docs/ble-device-spike.md` section 4, S4, with result table R4 (section 5). This file adds the
same interruptions over Wi-Fi rooms and solo play, which the spike does not cover, and the record.

Outcome: not run

## 1. Checklist

For each G5 phone, record model, Android version, vendor skin version, battery saver and any
vendor "app launch / background management" setting for the app (screenshot).

- [ ] Bluetooth rooms: spike S4 (a)–(f), as joiner and as host, build `-Pwpgd.bleTransport=true`
- [ ] Wi-Fi rooms (default build): the same six interruptions during a deal, as joiner and as host;
      (c) is Wi-Fi off 20 s then on instead of Bluetooth
- [ ] Solo (default build): (a) background 2 min, (b) lock 2 min, (e) force-stop and reopen,
      (f) Doze — solo has no radio, so (c) and (d) do not apply
- [ ] iPhone rows — blocked until WI-11

For each cell record: what the screen showed (for a joiner: 联系不上房主，正在自动重连（第 n 次）…
or 房主把这桌挂起了…), whether the seat came back without approval (H5), time to recover, and the
diagnostics counters `owner.rejoin_attempts`, `owner.room_moves`, `host.heartbeat_timeouts`,
`host.rejoined`, `host.transport_closed`, `client.liveness_timeouts`, `process.crashes`. A host
force-stop must be followed by 我开过的牌桌 → 接着打 (上次意外退出，可以接着打) on the host.

## 2. Result record

Copy spike table R4 into `dev-docs/evidence/HP-8-result-<date>.md`, one table per transport (BLE,
Wi-Fi, solo), one row per phone and role.

Pass (plan): the table is filled for every G5 device. Any cell where the seat did not come back,
or the table was lost, is a finding for the owner before G5 is signed.

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`.
