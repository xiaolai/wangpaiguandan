---
title: "HP-1 — solo match in airplane mode, plus the minimum-device budgets (procedure)"
created: 2026-10-15
status: "Procedure only. Not run: it needs a physical Android phone named by G5 (unsigned), a traffic-capture setup, and for the iPhone half the WI-11 client (gate G3)."
---

# HP-1 — solo airplane-mode match

Plan references: M1a exit criterion 1; WI-08 acceptance ("HP-1 solo airplane-mode match"; "p95
selection < 100 ms … on the named minimum device"); WI-07 acceptance (p95 AI decision ≤ 1 s on the
named minimum Android device, measured in WI-08); ledger GDN-001 (fresh install reaches play with
no login, no external request), GDN-005 (airplane mode, radios off, full match), GDN-059 (no
inference request), GDN-102 (selection budget); privacy property P9 (plan section 8). Section 6 of
the plan: a procedure is written here before it is run, and its result records device model, OS
version and radio settings.

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| A fresh install plays a full solo match with no account, no network and no permission prompt | Only a real install on a real phone shows the OS's own network and permission behaviour |
| No network request leaves the phone during play (P9, GDN-059) | Needs a capture outside the app; `SoloNeedsNoNetworkTest` only proves the code path |
| Selecting cards stays under 100 ms p95 on the minimum device (GDN-102) | JVM timing (`GeneratorBudgetTest`) says nothing about a slow phone's frames |
| The AI decides within 1 s p95 on the minimum device (WI-07) | Same: JVM timing is not device timing |

## 2. Preconditions (all must hold; otherwise stop and record which one failed)

1. `./tools/ci.sh` ends with `All local checks passed.` on the commit under test.
2. Device: the minimum Android phone named by G5 (G5 is unsigned today, so no device is named yet).
   Until G5 names one, a run may be recorded as provisional with the phone stated, but it does not
   satisfy the WI-07/WI-08 budgets.
3. Default build (`bleTransport` off): `./gradlew :app:assembleDebug`, or a release build once HP-6's
   preconditions hold. The default build requests only `android.permission.INTERNET` (for Wi-Fi
   rooms; `app/permissions-allowlist.txt`), which is an install-time permission with no prompt.
4. Traffic capture for the P9 half: a capture point the phone cannot bypass (for example a capture
   on the Wi-Fi access point in step 5 below, or `adb shell` with a VPN-based capture app installed
   on the test phone only). Airplane mode alone proves nothing about what the app would send.
5. For the budget half: Perfetto (`adb shell perfetto`, or the Android Studio profiler) to record a
   frame timeline. The app has no custom trace sections: the selection budget is read from frame
   durations around taps. The AI decision budget is read from diagnostics (Settings → 诊断信息):
   `owner.ai_decisions`, `owner.ai_decision_ms_p50`, `owner.ai_decision_ms_p95`,
   `owner.ai_decision_ms_max` (compute time of each AI decision on the AI worker thread).

## 3. Steps — solo match with radios off

1. Uninstall any earlier build (`adb uninstall cn.wpgd.app`), install the build under test.
2. Turn on airplane mode; confirm Wi-Fi off, Bluetooth off, mobile data off in the quick settings.
   Photograph or screenshot the quick settings panel.
3. Open the app. Expected: no login, no permission dialog. Main menu shows 单人练习.
4. Tap 单人练习 and play a full match (from level 2 through passing A, or until a team wins the
   match) against the three AI seats. Use tribute when it occurs.
5. During play: tap and drag-select cards, play, pass, open 设置 in the table and toggle 声音 and
   减少动态效果 (the table must not disconnect, GDN-081).
6. At the end: 设置 → 诊断信息 → 生成诊断信息. Copy `build.mode`, `app.version_code`,
   `profile.base.id/version/hash/provisional`, `profile.match.id/version/hash/provisional`,
   `host.revision`, `host.deal_number`, `host.violations`, `host.ai_rejected`, `host.ai_cancelled`,
   `client.digest_mismatches`, `owner.failures`, `process.crashes`.

## 4. Steps — traffic capture (P9, GDN-001, GDN-059)

1. Fresh install again. Airplane mode off, Wi-Fi on, connected through the capture point; mobile
   data off; Bluetooth off.
2. Start the capture, open the app, play one solo deal to the result screen, close the app.
3. Expected: zero packets from the app's UID (or, for an AP capture, from the phone's address apart
   from OS traffic that also appears with the app closed — capture a two-minute baseline with the
   app closed first and compare).

## 5. Steps — minimum-device budgets (GDN-102, WI-07)

1. On the G5 minimum device, start a Perfetto trace with the frame timeline, open a solo table.
2. Perform 50 single-card taps and 20 drag selections across a 27-card hand.
3. Read the duration from each touch event to the first frame presenting the new selection. Record
   p50, p95, max. Pass: p95 < 100 ms.
4. AI decision time: at the end of the match, open Settings → 诊断信息 and record
   `owner.ai_decisions`, `owner.ai_decision_ms_p95` and `owner.ai_decision_ms_max`. Pass: p95 ≤ 1000
   (WI-07). The diagnostics keep the last 4,096 decisions.

## 6. Result record

Copy this file to `dev-docs/evidence/HP-1-result-<date>.md` and fill:

| Field | Value |
|---|---|
| Commit, build type | |
| Device (model, Android version, build number, RAM) | |
| Radio settings per part (screenshots attached) | |
| Match completed (deals played, final levels) | |
| Diagnostics excerpt (fields in 3.6) | |
| Capture method and result (packets from the app) | |
| Selection latency p50 / p95 / max (ms) | |
| AI decisions counted, p95 and max (ms), from diagnostics | |
| Anything that needed a network, a permission or a restart | |

Pass: the match completes with radios off; `host.violations`, `host.ai_rejected`,
`client.digest_mismatches`, `owner.failures` and `process.crashes` are 0; the capture shows no
request from the app; selection p95 < 100 ms on the G5 minimum device.

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`.

## 7. iPhone half (blocked until WI-11)

The plan runs HP-1 on iPhone too once WI-11 exists (gate G3). No iPhone client exists; record
"blocked: WI-11" until it does.
