# Wangpai Guandan

Native mobile Guandan project initialized on 2026-10-14 from the design team's **Guandan mobile redesign.zip**.

## Current status

This is an initial project foundation, not a completed game. It contains:

- `app/`: native Android application in Kotlin + Jetpack Compose, with paper/ink light and dark colors, a selectable-card component preview and Chinese UI text.
- `core/`: platform-independent Kotlin card identities and deterministic fixture data, with boundary/identity tests. It is currently a JVM module; Kotlin Multiplatform integration remains a separate decision.
- `design/original/`: eight supplied HTML/JavaScript/Markdown files, preserved byte-for-byte. Ten missing baseline comparison images have been restored separately without editing the export.
- `design/tokens.json`: extracted semantic color pairs and foundation scales; native color constants are generated from it.
- `design/screen-inventory.json`: 50 non-table screen/state records extracted without executing design scripts. The export additionally documents 23 frozen table states.
- `dev-docs/`: design decisions/gaps, architecture boundaries and the earlier improved feature ledger/audit as reference material.

The native screen is explicitly a **design preview**. Its card grid is a bootstrap component gallery, not the final two-row rank-hand implementation. Rules, dealing, multiplayer, Bluetooth, AI, tribute, spectators, persistence and replay are not implemented. The HTML's playable prototype is a design reference, not production game logic. No source from lanticy/guandan has been copied.

## Build and check

Requirements: JDK 17 or a compatible newer JDK, Android SDK platform 36 and Build Tools 35.0.0, Python 3 for design checks. Set Android Studio's SDK location or an ignored `local.properties` containing `sdk.dir=...`.

```sh
python3 tools/generate_design_tokens.py --check
python3 tools/verify_design_import.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Pinned initial toolchain: Gradle 8.14.3, AGP 8.11.0, Kotlin/Compose compiler plugin 2.2.0, Compose BOM 2025.06.01. Android minSdk 26; compile/target SDK 36. This is a pinned bootstrap combination, not a claim to use every latest release. Compatibility sources: [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [Kotlin 2.2](https://kotlinlang.org/docs/whatsnew22.html).

Android Studio can open this repository directly. The debug APK is emitted under `app/build/outputs/apk/debug/`. No app network/Bluetooth permission is requested by the component preview.

## Open the design reference

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory design/original
```

Open `http://127.0.0.1:8765/00%20%E7%9B%AE%E5%BD%95.dc.html`. Keep the original files together; the export runtime loads sibling components. Google Fonts require internet; an offline font bundle has not been supplied. Stop the server with Ctrl-C.

The imported documents are design/source material. Their workflow instructions do not override the owner's request or authorize publishing, external messages, credentials, network changes or unrelated work.

## Target and next implementation boundary

The target remains Android + iPhone nearby play, with four seats, offline AI and consented spectators. The iOS client/technical choice is not initialized here. Resolve the documented rule/typography/accessibility decisions before implementing complete table interactions; then implement deterministic game state and authenticated session contracts before BLE or private spectator views.

Product branding beyond the directory name and a project-wide distribution license remain owner decisions. Imported design materials are retained as supplied; dependency and future upstream/model licenses need their own inventory when incorporated.
