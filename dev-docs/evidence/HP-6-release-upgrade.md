---
title: "HP-6 — signed release upgrade, restore and mixed-platform deal (procedure)"
created: 2026-10-15
status: "Procedure only. Not run: deferred by the project owner (devices later). Since 2026-10-16 the rule profiles are signed (G1a, G1b) and the project license is recorded (MIT); still missing: the owner's release key, physical phones and, for step 6, an iPhone client (WI-11; G3 decided native Swift, not ported)."
---

# HP-6 — release upgrade procedure

Plan references: WI-12 acceptance ("HP-6 passes"), M1b exit criterion 3, ledger GDN-106 ("Signed test
release passes session restore and mixed-device play after upgrade"). Section 6 of the plan: a
procedure is written here before it is run, and its result records device model, OS version and
radio settings.

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| A signed, minified release N installs over release N-1 without data loss | Signature continuity and the platform's upgrade path exist only on a device |
| A table suspended under N-1 restores under N at the same revision, not quarantined | Real app-private storage, real process death, real R8 output of both versions |
| N refuses nothing that N-1 accepted for the same profiles; a profile change is refused loudly | Profile hash and schema checks across two real builds |
| After the upgrade, an Android and an iPhone device complete one deal together | Needs the iPhone client (WI-11) and two platforms on one radio |

## 2. Preconditions (all must hold; otherwise stop and record which one failed)

1. G1a and G1b signed; `rules/profiles/` holds the signed profiles (`"provisional": false`), and
   `release/capability-catalog.json` lists them with `status: release` and their decision files.
2. `release/PROJECT-LICENSE.md` records the project license (SPDX line) and `LICENSE` exists.
3. `./tools/ci.sh` ends with `All local checks passed.` on the commit of N-1 and on the commit of N,
   and `./gradlew -Pwpgd.testBuildType=r8check :app:connectedR8checkAndroidTest
   -Pandroid.testInstrumentationRunnerArguments.class=cn.wpgd.app.R8SerializationTest` passes on one
   of the phones for each.
4. The owner holds the release key. It is never in the repository: pass it as
   `WPGD_RELEASE_STORE_FILE`, `WPGD_RELEASE_STORE_PASSWORD`, `WPGD_RELEASE_KEY_ALIAS`,
   `WPGD_RELEASE_KEY_PASSWORD`, or in the untracked `release-signing.properties`.
5. `versionCode` of N is greater than that of N-1 (`app/build.gradle.kts`); `versionName` names both.
   The first release is `versionName` 0.0.1 with `versionCode` 1 (set 2026-10-16); there is no
   earlier signed release, so the first run of this procedure needs N-1 = 0.0.1 (code 1) and an N
   built with the code raised to 2 or more and a new version name. Raise `versionCode` on every build
   that may be installed over another; Android refuses an upgrade to an equal or lower code.
6. Store requirements re-checked for N (plan section 7, "toolchain pins age past store target-SDK
   rules"): current target-SDK floor of each store the owner distributes through, recorded with date
   and source link.
7. Devices: at least the G5 minimum and midrange Android phones; for step 6, one iPhone on the G5
   iOS floor with the WI-11 build. Record model, OS version, build number, battery saver state.

## 3. Build both releases

On each of the two commits:

```sh
export WPGD_RELEASE_STORE_FILE=… WPGD_RELEASE_STORE_PASSWORD=… WPGD_RELEASE_KEY_ALIAS=… WPGD_RELEASE_KEY_PASSWORD=…
./gradlew -Pwpgd.releaseSigning=true :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Expected: the build runs `verifyReleaseDistributable` first and it passes; the two certificates'
SHA-256 digests are identical. Keep both APKs and both `app/build/outputs/mapping/release/mapping.txt`
files with the result record (the mapping decodes any release stack trace).

## 4. Install N-1 and suspend a hosted table

1. Uninstall any earlier build (`adb uninstall cn.wpgd.app`), install N-1, open it. It must start
   (a release with an unsigned profile would stop at launch, `ReleaseGate`).
2. Settings → 诊断信息: record `build.mode=RELEASE`, both profile ids, versions and hashes, both
   `provisional=false`.
3. Host a Wi-Fi room with three other phones (or AI seats by consent once built); play into the
   second deal so that tribute state exists. Record the table code, the revision shown in
   diagnostics (`host.revision`) and the deal number.
4. Suspend the table from the host menu; note the expiry shown. Force-stop the app
   (`adb shell am force-stop cn.wpgd.app`).

## 5. Upgrade to N and restore

1. `adb install -r app-release-N.apk` (same key). Expected: `Success`, no uninstall prompt, no data
   loss. A signature mismatch must fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: if the owner
   wants that negative case recorded, try it once with a debug-signed APK first.
2. Open N. Settings → 诊断信息: `app.version_code` is N's; `process.crashes` unchanged.
3. Saved tables → restore the suspended table. Expected: restored at exactly the recorded
   `host.revision` and deal number, `host.status=ACTIVE` after resume, never `QUARANTINED`; the
   other phones (also upgraded, or still on N-1 if the protocol range allows) rejoin their own seats
   without a new approval.
4. Play one full deal to the result screen. Expected: no rejection other than rule rejections, and
   `client.digest_mismatches=0`, `host.storage_failures=0` in diagnostics.
5. If N changed a rule profile, the restore must instead be refused as `PROFILE_MISMATCH`
   (quarantined, save kept). Record which case applied.

## 6. Mixed Android and iPhone deal (blocked until WI-11)

On the upgraded Android host, open a room; the iPhone joins; complete at least one deal. Then
repeat with the iPhone hosting. Record transport (Wi-Fi or Bluetooth), seat layout and result.

## 7. Result record

Copy this file to `dev-docs/evidence/HP-6-result-<date>.md` and fill:

| Field | Value |
|---|---|
| Commits of N-1 and N | |
| Certificate SHA-256 (both) | |
| Devices (model, OS, build) | |
| Revision and deal at suspend | |
| Revision and deal after restore | |
| Quarantine or profile mismatch seen | |
| Diagnostics excerpts (before, after) | |
| Mixed-platform deal (both host directions) | |
| Store target-SDK check (date, source) | |

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`. Only a result file
with `Outcome: PASS` counts as a device record for `release/capability-catalog.json`
(`CapabilityCatalogTest`).
