# GuandanCore

Gate G3 is decided: the iPhone client uses a **native Swift core**, not Kotlin Multiplatform (2026-10-16, project owner). This Swift package is that core's skeleton. It must eventually implement the same profile loading, legal-action rules and wire format as `:core` and `:session`, and pass every shared fixture.

**The rules engine is NOT ported.** The port is WI-11 work, which needs the implementation and testing on a physical iPhone. Until then the package only compiles and checks fixture structure.

## Contents

| Path | What it is |
| --- | --- |
| `Package.swift` | Swift tools 6.0, iOS 17 / macOS 14, library product `GuandanCore`. Zero third-party dependencies (Foundation and XCTest only). |
| `Sources/GuandanRules` | Placeholder for profiles, classifier, legal action generator and deal/match reducers. Holds only `RuleProfileHeader` (id, version, displayName, provisional); no game logic. |
| `Sources/GuandanWire` | Placeholder for wire messages and codec. Declares no protocol version on purpose; the Kotlin side and the wire fixtures are authoritative. |
| `Tests/GuandanConformanceTests` | Conformance tests that read the shared fixtures **by path** from the repository (never copied into `ios/`). |

## Run

```sh
cd ios/GuandanCore && swift build && swift test
```

Works with plain `swift` or `xcrun swift` on macOS. The conformance tests find the repository root by walking up from the test source file to the directory containing `settings.gradle.kts`, so they must run from a full checkout.

## What the conformance tests check today

Structure only, so they survive fixture regeneration:

- every `*.json` under `core/src/test/resources/fixtures/` parses; `format`, `rules` and `wire` each hold at least one file;
- every `rules/profiles/*.json` decodes to `RuleProfileHeader`, its `id` equals the file name, and `version` is MAJOR.MINOR.PATCH;
- `fixtures/rules/golden-v1.json` holds a non-empty list of cases;
- `rules/golden/` holds at least one `.md` note.

A missing folder or an empty scan fails the test; it never passes vacuously. `testRulesEnginePortIsPendingWI11` is reported as **skipped** on every run, so the test report itself shows that the engine is not ported.
