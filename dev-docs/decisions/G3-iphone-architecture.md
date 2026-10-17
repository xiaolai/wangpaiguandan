# G3 — iPhone architecture

**Status: DECIDED**

| Field | Value |
|---|---|
| Gate | G3 (`dev-docs/implementation-plan.md` section 3) |
| Decider | project owner |
| Date | 2026-10-16 |
| Decision | The iPhone client uses a **native Swift core**, held to the shared JSON fixtures. Kotlin Multiplatform is not used; `:core` and `:session` stay JVM-only. |
| Spike | Not run. The plan proposed a time-boxed KMP spike first; the owner decided without it. The plan's fallback outcome (native Swift core with the same fixtures) is the one chosen. |
| Codex refute pass | Not run. |

## What exists now

- `ios/GuandanCore/` is a Swift package skeleton (`Package.swift`; targets `GuandanRules` and `GuandanWire`, empty but compiling; test target `GuandanConformanceTests`). The test target reads the shared fixtures by path from `core/src/test/resources/fixtures/`, `rules/profiles/` and `rules/golden/`; it checks that they parse and are well formed. It does **not** check rule behaviour, because the rules engine is not ported.
- `tools/ci.sh` runs `swift build` and `swift test` for the package when a Swift toolchain is installed.

## What is still owed (WI-11, BLOCKED)

- The Swift port of the rules core, deal and match reducers, projections and the wire protocol (including the G2 rejoin proof), held to every shared fixture.
- The SwiftUI app, Core Bluetooth and LAN adapters.
- A physical iPhone for HP-1, HP-3 (iPhone host), HP-4 (VoiceOver) and HP-6.
