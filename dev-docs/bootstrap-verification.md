# Bootstrap verification

Verified on 2026-10-14. This records the initial project foundation, not full-game acceptance.

- Design import: all eight supplied files match their recorded SHA-256 values. Ten separately restored baseline images also match their provenance manifest.
- Inventories: 50 non-table screens and 23 table states; generated Android colors match the extracted tokens.
- Android: `:app:assembleDebug` and `:app:lintDebug` passed with JDK 21, SDK 36 and Build Tools 35.0.0. Lint reports zero errors and eight dependency-version warnings.
- Core: all three tests passed, covering physical-card identity, level-card wildcard eligibility and invalid-input boundaries.
- Native emulator: installed and launched the debug APK; card selection, clearing and theme switching passed. Light/dark screenshots were inspected after status-bar and token fixes.

The preview uses fixture cards and a scrollable component grid. Full table layout, game rules, session/transport, spectators, AI, persistence, replay and iPhone implementation remain unimplemented. CI is configured but has not run on a remote hosting service. No remote repository or publication was created.
