# iPhone target

Status: G3 decided 2026-10-16 by the project owner: the iPhone client uses a **native Swift core** (`dev-docs/decisions/G3-iphone-architecture.md`). `GuandanCore/` is a Swift package skeleton that builds and checks the shared fixtures' structure; the rules engine is **not** ported. No Xcode project and no app code yet. WI-11 (`dev-docs/implementation-plan.md`) stays BLOCKED: it needs the implementation and a physical iPhone.

## Layout

| Folder | Holds | Needed when |
|---|---|---|
| `WangpaiGuandan/` | The SwiftUI app: screens, lifecycle, accessibility, design tokens. Xcode project goes here. Bundle id `cn.wpgd.app`. | Always |
| `GuandanCore/` | The Swift package for the rules, state and wire code, written natively. Today a skeleton (`GuandanRules`, `GuandanWire`, conformance tests); see its README. | Always (G3 chose the native core) |
| `Conformance/` | Tests that decode the shared JSON fixtures and check the iOS core against them. | Always, once a core exists |

Kotlin Multiplatform is not used: `:core` and `:session` stay JVM-only.

## Shared data

The Swift core reads the same fixtures as the Kotlin core, so the iPhone and Android cores cannot silently diverge:
- Rule profiles: `rules/profiles/*.json`
- Rule cases: `core/src/test/resources/fixtures/rules/golden-v1.json`
- Core and wire fixtures: `core/src/test/resources/fixtures/`

Do not copy these into `ios/`. Read them by path from the test target.

## Decision (G3)

Decided 2026-10-16 by the project owner, without the planned Kotlin Multiplatform spike: native Swift core, held to the shared fixtures (the plan's fallback outcome). `./tools/ci.sh` runs `swift build` and `swift test` for `GuandanCore/` (warnings as errors) when a Swift toolchain is installed. Still owed under WI-11: the Swift port of the rules, match and wire code (including the G2 rejoin proof, vectors in `core/src/test/resources/fixtures/wire/frame-envelope.json`), the SwiftUI app, and testing on a physical iPhone (HP-1, HP-3, HP-4, HP-6; deferred by the project owner, devices later).
