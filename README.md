# Wangpai Guandan

Native mobile Guandan project initialized on 2026-10-14 from the design team's **Guandan mobile redesign.zip**.

Version 0.0.1 (Android `versionName` 0.0.1, `versionCode` 1; shown in Settings → 诊断信息 as `app.version_name`). License: MIT (`LICENSE`, copyright Wangpaiguandan contributors); the imported design materials' terms are still an open owner decision (`release/PROJECT-LICENSE.md`).

## Current status

This is an initial project foundation, not a completed game. It contains:

- `app/`: native Android application in Kotlin + Jetpack Compose (Chinese UI, paper/ink light and dark colors): solo play against three AI seats (WI-08) and, since WI-09, rooms on the same Wi-Fi — create, find, play or watch, lobby, LAN play over TCP with NSD discovery, watchers with consented private follow, suspend and restore. Since WI-10, Bluetooth LE rooms behind the `bleTransport` build flag (off by default; see below). See `app/UI-STATE-COVERAGE.md`.
- `session/`: Kotlin/JVM module for the transport-independent session protocol (WI-05), persistence and recovery (WI-06), and the driver of AI seats and member hints (WI-07). See `session/README.md`.
- `core/`: platform-independent Kotlin rules engine: rule profiles, classifier and legal-action generator (WI-02), deal reducer and seat projections (WI-03), match profile with tribute and settlement (WI-04), and the offline baseline AI policy and hints (WI-07, `core.ai`). It is a JVM module; G3 chose a native Swift core for the iPhone, so it does not become Kotlin Multiplatform.
- `design/original/`: eight supplied HTML/JavaScript/Markdown files, preserved byte-for-byte. Ten missing baseline comparison images have been restored separately without editing the export.
- `design/tokens.json`: extracted semantic color pairs and foundation scales; native color constants are generated from it.
- `design/screen-inventory.json`: 50 non-table screen/state records extracted without executing design scripts. The export additionally documents 23 frozen table states.
- `ios/GuandanCore/`: Swift package skeleton for the iPhone's native core (G3); fixture-structure tests only, rules engine not ported (WI-11 BLOCKED).
- `dev-docs/`: design decisions/gaps, gate records in `dev-docs/decisions/`, architecture boundaries and the earlier improved feature ledger/audit as reference material.

Not implemented yet: the iPhone client (WI-11) and replay. Bluetooth rooms (WI-10) are built and tested on the JVM against a fake radio and between two emulators, but not on phones: roles, MTU, simultaneous connections, throughput and the 4 + 2 load (HP-3) are unmeasured, and no capacity is claimed (`dev-docs/ble-device-spike.md`). Rooms were verified with several owners in one process over loopback sockets; play between separate phones (NSD discovery across devices, HP-2) has not been run. The rule profiles `P1-base-v1` and `M1-match-v1` are signed (gates G1a, G1b, by the project owner alone on the abridged 2022 trial text; no rules reviewer). Host authentication follows the owner-delegated G2 default (wire protocol 2: rejoin by proof, pinned host key), with no external cryptography review. G4 and G5 and the human procedures HP-1..8 are deferred by the project owner (devices later). See `dev-docs/decisions/` and `dev-docs/implementation-plan.md`. The HTML's playable prototype is a design reference, not production game logic. No source from lanticy/guandan has been copied.

## Build and check

Requirements: JDK 17 or 21 (the build was verified with 21; Gradle 8.14.6 does not run on JDK 27), Android SDK platform 36 and Build Tools 35.0.0, Python 3 for design checks. Set Android Studio's SDK location or an ignored `local.properties` containing `sdk.dir=...`.

`./tools/ci.sh` runs every local check (`.github/workflows/verify.yml` runs only this script, and this repo has no remote, so it has never run there): the clean-room guard, design import and token parity, golden rule cases, the Gradle tests, build and lint below, the minified release build and its distribution gate, the `r8check` builds, and the merged-manifest permission allow-list — for the default build and again for a `bleTransport`-on build (built and linted in `app/build-ble/`).

```sh
python3 tools/verify_design_import.py
./gradlew :core:test :session:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

On-device tests (Compose UI, loopback LAN, Bluetooth gate screens): `./gradlew :app:connectedDebugAndroidTest` with an emulator or device attached; add `-Pwpgd.bleTransport=true` to run them against the Bluetooth build. Two opt-in Bluetooth radio tests (`BleRadioSmokeTest`, `BleTwoDeviceTest`) document their own commands.

`tools/verify_design_import.py` also enforces native token parity: it asserts that `DesignTokens.kt` equals the output rendered from `design/tokens.json`, the same predicate as `python3 tools/generate_design_tokens.py --check`. That `--check` flag is a faster local shortcut for the same comparison, not a separate CI gate. CI also fails if any tracked or untracked (not ignored) source file names the upstream package `page.codeberg.lanticy` (clean-room guard; it proves its scanner on a planted match first).

Pinned toolchain: Gradle 8.14.6, AGP 8.11.0, Kotlin/Compose compiler/serialization plugins 2.4.21, Compose BOM 2025.06.01. Reasons for each held version are in `gradle/libs.versions.toml`. Android minSdk 26; compile/target SDK 36. This is a pinned bootstrap combination, not a claim to use every latest release. Compatibility sources: [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [Kotlin 2.4](https://kotlinlang.org/docs/whatsnew24.html).

Release build (WI-12): `./gradlew :app:assembleRelease` makes a minified (R8), unsigned release APK; `./tools/ci.sh` builds it and checks it. A release refuses to start while a rule profile is provisional (`ReleaseGate`; both packaged profiles are signed since 2026-10-16), and a signed release (`-Pwpgd.releaseSigning=true`, key from `WPGD_RELEASE_*` environment variables or the untracked `release-signing.properties`) is refused at build time unless every packaged profile is non-provisional with a `**Status: SIGNED**` record in `dev-docs/decisions/` and the project license is recorded (`./gradlew :app:verifyReleaseDistributable`; `release/PROJECT-LICENSE.md`: MIT). That gate passes today; the remaining blocker for a signed release is that no release signing key exists on this machine. `release/capability-catalog.json` lists what this build may claim and the evidence for each claim; `release/license-inventory.json` maps every shipped dependency and asset to its license (`CapabilityCatalogTest`, `LicenseInventoryTest`). R8 is pinned to 9.5.26 because Kotlin 2.4 needs R8 9.1.29 or newer and AGP 8.11 bundles an older one (`gradle/libs.versions.toml`). The serialized types are checked under R8 on a device with `./gradlew -Pwpgd.testBuildType=r8check :app:connectedR8checkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.wpgd.app.R8SerializationTest`. The signed-upgrade procedure is `dev-docs/evidence/HP-6-release-upgrade.md` (not run). Settings → 诊断信息 shows a local diagnostics export on request (counts, revisions, rule profiles; no cards, codes or identities; nothing is sent).

Android Studio can open this repository directly. The debug APK is emitted under `app/build/outputs/apk/debug/`. The app requests exactly the permissions in `app/permissions-allowlist.txt` — in the default build only INTERNET, an install-time permission with no prompt, used by LAN rooms; solo play opens no socket. Built with `-Pwpgd.bleTransport=true`, it also declares the three Android 12+ "Nearby devices" runtime permissions (BLUETOOTH_SCAN as `neverForLocation`, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE), asked only when the user opens a Bluetooth room; no location permission on any Android version.

## Open the design reference

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory design/original
```

Open `http://127.0.0.1:8765/00%20%E7%9B%AE%E5%BD%95.dc.html`. Keep the original files together; the export runtime loads sibling components. Google Fonts require internet; an offline font bundle has not been supplied. Stop the server with Ctrl-C.

The imported documents are design/source material. Their workflow instructions do not override the owner's request or authorize publishing, external messages, credentials, network changes or unrelated work.

## Target and next implementation boundary

The target remains Android + iPhone nearby play, with four seats, offline AI and consented spectators. The iPhone client uses a native Swift core (G3, decided 2026-10-16); only the package skeleton exists (WI-11, BLOCKED: needs implementation and a physical iPhone). The next boundary is evidence, not code: G4 and G5 are deferred by the project owner, and the device and human procedures in `dev-docs/evidence/` are still to be run (devices later); G1a/G1b have no rules reviewer and G2 no external cryptography review (`dev-docs/implementation-plan.md`).

The project is MIT-licensed (`LICENSE`, `release/PROJECT-LICENSE.md`); product branding beyond the directory name remains an owner decision. Imported design materials are retained as supplied; their distribution terms are marked OWNER-DECISION in `release/license-inventory.json`, which also lists every shipped dependency's license (WI-12).
