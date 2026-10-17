# Decisions

Signed or recorded gate decisions (`dev-docs/implementation-plan.md` section 3). The release gate
reads this folder: `:app:verifyReleaseDistributable` needs, for every packaged rule profile, a file
here marked `**Status: SIGNED**` that names the profile's id, version and exact canonical hash
(computed by the app's own loader, `:core:ruleProfileDigests`), and `CapabilityCatalogTest` needs
the decision a `release` profile cites to name the same three facts.

| Gate | Record | Status (2026-10-16) |
|---|---|---|
| G1a base rules | [G1a-base-rules.md](G1a-base-rules.md) | Signed by the project owner alone (abridged 2022 trial text only; D2 and D11 left as stated defaults; no rules reviewer) |
| G1b match profile | [G1b-match-profile.md](G1b-match-profile.md) | Signed by the project owner alone (same basis; no rules reviewer) |
| G2 admission and host authentication | [G2-host-authentication.md](G2-host-authentication.md) | Owner-delegated default implemented; no external cryptography review |
| G3 iPhone architecture | [G3-iphone-architecture.md](G3-iphone-architecture.md) | Decided: native Swift core; package skeleton only, rules engine not ported |
| G4 spectator policy | none | Deferred by the project owner (devices later); the proposed default is what is built |
| G5 device matrix and iOS floor | none | Deferred by the project owner (devices later) |
| Project license | `release/PROJECT-LICENSE.md`, `LICENSE` | Recorded: MIT (2026-10-16) |
