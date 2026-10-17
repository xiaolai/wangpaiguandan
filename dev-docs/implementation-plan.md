---
title: "Wangpai Guandan — implementation plan"
created: 2026-10-14
status: "In progress. Each work item's Status block says what is done, verified or blocked; section 1 records the 2026-10-14 bootstrap baseline and what is still missing."
supersedes: "dev-docs/reference/guandan-implementation-plan.md (reference only; see Reconciliation)"
---

# Wangpai Guandan — implementation plan

## 1. Status and scope

**Done (bootstrap, verified 2026-10-14 per `dev-docs/bootstrap-verification.md`; corrected 2026-10-15 where later work items changed a fact):**

- Gradle wrapper pinned with a SHA-256 checksum (`gradle/wrapper/gradle-wrapper.properties`; bootstrap 8.14.3, now 8.14.6). AGP 8.11.0, Kotlin 2.4.21 (bootstrap 2.2.0), Compose BOM 2025.06.01, R8 9.5.26 (`gradle/libs.versions.toml`, each change explained there). minSdk 26, compile/target SDK 36, JVM target 17 (`app/build.gradle.kts`, `core/build.gradle.kts`).
- Bootstrap had two modules, `:app` and `:core`; WI-01 added `:session` (`settings.gradle.kts`).
- `:core` is a plain Kotlin/JVM module. It holds `Card`, `Suit`, `isWildcard(level)` and an unshuffled 108-card `fixtureDeck()` (`core/src/main/kotlin/.../core/Card.kt`). Three tests pass (`core/src/test/kotlin/.../core/CardTest.kt`).
- At bootstrap `:app` showed a Compose component preview (`DesignPreview.kt`, since replaced by the WI-08 table and the WI-09 rooms). Colors are generated from `design/tokens.json` into `app/src/main/kotlin/cn/wpgd/app/ui/DesignTokens.kt` by `tools/generate_design_tokens.py`.
- Platform backup is already off: `android:allowBackup="false"`, `fullBackupContent="false"`, and `data_extraction_rules.xml` excludes every domain for cloud backup and device transfer (`app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`).
- At bootstrap the app requested no permissions. Since WI-09 the manifest requests exactly `INTERNET` (LAN rooms); the `bleTransport` build adds the Bluetooth permissions. `app/permissions-allowlist.txt` is the list `tools/ci.sh` checks the merged manifests against.
- CI exists in `.github/workflows/verify.yml` (it runs `./tools/ci.sh`) but has never run on a remote host: the repository has no remote. `./tools/ci.sh` runs the same checks locally.

**Not implemented (2026-10-15):** the iPhone client (WI-11, gate G3; `ios/README.md`) and the M2 extensions such as replay (WI-13). Rules, dealing, legal moves, sessions, LAN and Bluetooth transport, spectators, AI, persistence and release gating are built against provisional profiles and unsigned gates (see each work item). No device or human procedure (HP-1 … HP-8) has been run.

**Update 2026-10-16 (project owner decisions, `dev-docs/decisions/`):** G1a and G1b are signed by the project owner alone (basis: the abridged 2022 trial text only; D2 and D11 left as the stated defaults; no rules reviewer), and the packaged profiles are now `P1-base-v1` and `M1-match-v1` 1.0.0. G2 is an owner-delegated default, implemented without external cryptography review (protocol version 2). G3 is decided: native Swift core; `ios/GuandanCore` is a package skeleton, the rules engine is not ported. G4, G5 and HP-1 … HP-8 are deferred by the owner (devices later). The project license is MIT. The app version is 0.0.1 (code 1). Audit finding 17 is fixed with a match number (storage schema 3). The iPhone client is still not implemented.

**This plan covers:** milestones M0 (pure-JVM foundation), M1a (Android playable, LAN), M1b (Bluetooth, iPhone, release evidence), and the boundary of M2 (extensions, to be planned separately).

**This plan does not cover:** physical-card scanning, desktop assistance, cloud accounts, matchmaking, wagers, rankings, voice chat, automatic host migration (same non-goals as the reference plan and ledger). It does not choose the Guandan rule profile. Development uses a provisional profile (section 3); release blocks on that choice.

Estimates are relative: S < M < L. No dates.

## 2. Reconciliation with the reference plan

The reference plan (`dev-docs/reference/guandan-implementation-plan.md`) was written against upstream commit `d66c38a`. `dev-docs/architecture.md` already says its commands and paths are not current.

| Reference assumption | This repo (evidence) | Decision |
|---|---|---|
| AGP 8.7.1, Kotlin 2.0.21, Gradle 8.9, targetSdk 35 | At planning: AGP 8.11.0, Kotlin 2.2.0, Gradle 8.14.3, SDK 36. Now Kotlin 2.4.21 and Gradle 8.14.6, each bump explained in `libs.versions.toml` (`libs.versions.toml`, wrapper properties, `app/build.gradle.kts`) | Keep the repo pins. Any bump is its own reviewed change. |
| Edit upstream `GameEngine.kt`, `Bot.kt`, `NsdHelper.kt`, `SessionStore.kt` | No upstream source exists here; README says no lanticy source was copied | Clean-room rewrite. The audit is a behavior specification, not code to port. |
| `protocol` module; `:core:jvmTest`, `:protocol:jvmTest` | `:core` is Kotlin/JVM, so its task is `:core:test` (`core/build.gradle.kts`) | Add one new JVM module `:session` in M0. Commands use `:core:test` / `:session:test` until G3 decides on KMP. |
| `verification/run_guandan_probes.py` baseline probes | Not in this repo | Reproduce the relevant audited behaviors as named regression tests (section 5). |
| Legacy profile, v1 save migration, D7 | No legacy saves, peers or protocol exist | Drop migration. Keep the principle: a match never resumes under a different profile hash or schema. |
| `releaseTest` build variant | At planning only AGP defaults (`debug`, `release`) and no minify. WI-12 enabled minify on `release` and added an `r8check` test build type (`app/build.gradle.kts`) | Use `release` with minify enabled in WI-12 (done). |
| GPL notices inherited from upstream (KEEP-07, GDN-107) | No GPL code inherited | GDN-107 becomes a dependency/font/model license inventory. Project license stays an owner decision (README). |
| Audit and reuse an upstream iOS client | No iOS code; `ios/README.md` says planned only | Gate G3 (shared core or native core). |
| A29 backup exposure | Already closed by manifest and extraction rules | Keep a regression test so it cannot reopen (WI-06). |
| Six feature flags | At planning nothing to flag. Now: `bleTransport` is a Gradle property (`wpgd.bleTransport`, default off); `spectators` and `tributeProfile` are build constants in `Features` (`app/src/main/kotlin/cn/wpgd/app/room/RoomUi.kt`) | Keep three kill-switch flags: `bleTransport`, `spectators`, `tributeProfile`. The rest move to M2. |
| 14 work items | — | 13 items. Reasons in section 5 intro. |
| Reference CI built only, no checks | `verify.yml` runs `./tools/ci.sh`, which runs `tools/verify_design_import.py`; that script imports `rendered`/`DEST` from `generate_design_tokens.py` and asserts native token parity (line 29) | Token parity is already enforced in CI, transitively. No duplicate `--check` step. WI-01 corrected the README wording that listed `--check` as a separate gate. |
| "Retain last valid generation" checkpoints | — | Recovery source is an append-only event log; checkpoints are a cache. Never restore below an acknowledged revision (WI-06). |

Kept from the reference without change: decisions D1–D6, target rules 1–12, the invariants list, provisional budgets, the kill-switch conditions, and the rule that device and human gates cannot be replaced by unit tests.

## 3. Decision gates

Only decisions that change correctness, privacy or architecture are gates. Except where the development-versus-release paragraph below says otherwise, a gated slice must not start its gated part until the decision is written down in `dev-docs/decisions/` and signed by the decider.

**Development versus release (amended 2026-10-14).** The rule gates G1a and G1b block release, not development. While they are unsigned, development uses a provisional profile `P0-provisional`: the draft `rules/base-v1.md` with the section-9 defaults applied, and each default marked as a default. P0 is versioned and hashed like any profile. A rule change made while P0 is provisional updates the profile version, the golden cases in `rules/golden/` and the affected tests; no new gate is needed. A build that carries an unsigned profile is a development build. Its profile identifier, version and hash must appear in diagnostics, and WI-12 adds a test that a release build refuses to start with an unsigned profile. G2, G3, G4 and G5 are unchanged: each still blocks the work it names.

**Signed (2026-10-16).** The project owner signed G1a and G1b (`dev-docs/decisions/G1a-base-rules.md`, `G1b-match-profile.md`). The provisional profiles became the released profiles `P1-base-v1` 1.0.0 (formerly `P0-provisional`) and `M1-match-v1` 1.0.0 (formerly `M0-provisional`) with the same option values. The plan's "owner + a Guandan rules reviewer" was not met: no reviewer signed, the printed rule books were not checked, and D2 and D11 stay at their stated defaults. The release gate still refuses any profile marked provisional, and the distribution gate also requires a signed decision record naming each profile.

| ID | Decision | Blocks | Proposed default | Decides | Status (2026-10-16) |
|---|---|---|---|---|---|
| G1a | Base rule spec: combination types, sequence bounds (A-low), joker limits, bomb order (4–10 cards, straight flush, four jokers), wildcard substitution limits | Release of any build with rules (not development; see the paragraph above) | One written spec, `rules/base-v1.md`, citing one named source document per clause. No clause without a source. | Owner + a Guandan rules reviewer | **Signed** by the project owner alone, 2026-10-16 (`dev-docs/decisions/G1a-base-rules.md`): abridged 2022 trial text only, printed 2017/2022 books not checked, D2 and D11 left as stated defaults, owner not a Guandan expert, no reviewer. Profile `P1-base-v1` 1.0.0. |
| G1b | Match profile: single/double tribute, ties, return limits and exception, resistance (split jokers), opening lead per deal, level gains, A eligibility and failure counting, card-count reporting, early double-up settlement | Release of any build with tribute rules (not development); full-match exit of M1a | Pick one published event rule document (the ledger cites two) as the first profile. State every override. Note the conflict: `design/original/README.md` lists "过 A（失败 3 次）" as decided, while `dev-docs/design-handoff.md` says passing-A failure counting is unresolved. This plan treats it as unresolved. | Owner + rules reviewer | **Signed** by the project owner alone, 2026-10-16 (`dev-docs/decisions/G1b-match-profile.md`), same basis; every D25–D40 default adopted (D35: no failure limit); no reviewer, fixture list not separately signed. Profile `M1-match-v1` 1.0.0. |
| G2 | Admission and private transport: how peers authenticate, how capabilities are minted, how private payloads are protected on BLE and LAN | WI-05 crypto/admission parts; everything networked | Per-room ephemeral key agreement using platform crypto only (JCA on Android, CryptoKit on iPhone), confirmed by a short code or QR shown by the host. Capabilities are random per-member tokens bound to room epoch. Do not rely on BLE pairing. | Owner, after a Codex refute pass (expensive to reverse) | **Owner-delegated default implemented; no external cryptography review** (and no Codex refute pass), 2026-10-16 (`dev-docs/decisions/G2-host-authentication.md`): pinned room key, nonce, rejoin proof instead of the capability; protocol version 2. |
| G3 | iPhone architecture: Kotlin Multiplatform shared `:core`/`:session` vs a native Swift core held to shared fixtures | WI-11; whether `:core` stays JVM-only | Time-boxed KMP spike (iosArm64 + simulator). If it fails build, binary size or debugging needs, use a Swift core with the same JSON fixtures. | Owner, from spike evidence; Codex refute | **Decided** by the project owner, 2026-10-16, without a spike (`dev-docs/decisions/G3-iphone-architecture.md`): native Swift core; `ios/GuandanCore` skeleton builds and reads the shared fixtures; rules engine not ported. |
| G4 | Spectator policy: one private viewpoint per deal; friendly vs competitive defaults for hints and private follow | WI-05 grant rules; WI-07 hint policy | Reference D6 and ledger default 7: one private view per deal; competitive rooms disable live hints and private follow. | Owner | **Deferred by the owner, devices later.** The proposed default stays built. |
| G5 | Supported device matrix and iOS floor | Exit of M1b (BLE, soak, OEM matrix) | Android 8 (minSdk 26) floor stays provisional; named minimum and midrange phones per vendor in GDN-101; no iOS floor until the G3/WI-10 spike measures one. | Owner, from WI-10 measurements | **Deferred by the owner, devices later.** |

**Non-blocking design decisions** (tracked, defaults used until decided; none affects correctness or privacy):

| Topic (`design-handoff.md`) | Default used | Must be decided before |
|---|---|---|
| Fonts | System CJK + serif fallback (`tokens.json` `font_resolution`). Any bundled font enters the WI-12 license inventory. | M1b release candidate |
| Wildcard color | Vermillion `wild` token plus the "配" badge (`design/original/README.md`), so color is never the only cue | M1a table UI sign-off |
| Recovery grace | Lobby-configurable value in the profile/room policy; no default hard-coded in transport. Candidate values 10 s (ledger) or 30 s (prototype). | M1a exit |
| Hand layout B | Layout A (two rows by rank), the design default | M1b human usability test |
| Font-scale cap vs 200% target | Ledger target wins: 200% must work through an alternate layout (GDN-098) | M1a exit |

## 4. Milestones

| Milestone | Content | Exit criteria (observable checks) |
|---|---|---|
| **M0 Foundation** (JVM only, no radios, no UI) | WI-01 … WI-06, WI-07 baseline policy | 1. `./gradlew :core:test :session:test` passes in CI. 2. Subset oracle passes for every seeded hand of ≤ 8 cards, and the count-based bomb oracle passes on seeded 27-card hands. 3. 10,000 seeded all-bot deals finish with zero invariant violations and zero illegal bot actions (`SimulationInvariantTest`). 4. Privacy property tests pass, including receipt bytes (section 8, P1–P3). 5. Session fault-injection suite passes over the in-memory transport. 6. Kill-at-every-write-step test restores the last committed revision, never below the high-water mark; otherwise the room quarantines. 7. Host state rebuilt from the persisted event log alone matches the checkpoint. 8. G2 and G4 signed. G1a signed before the first release candidate (section 3). |
| **M1a Android playable** | WI-07 complete, WI-08, WI-09, WI-04 | 1. Solo match (1 human + 3 AI) completes on a physical Android phone in airplane mode with Bluetooth and Wi-Fi off (human procedure HP-1). 2. Four Android phones complete a match over LAN, with two spectators (HP-2). 3. `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` pass. 4. TalkBack user completes a deal (HP-4). 5. G1b signed and match-profile fixtures pass. |
| **M1b Nearby release candidate** | WI-10, WI-11, WI-12 | 1. 4 players + 2 spectators, Bluetooth only (Wi-Fi and mobile data off), 60 minutes, with Android host and with iPhone host, on the G5 matrix (HP-3). 2. iPhone passes the same rule and wire fixtures (command chosen by G3). 3. Signed, minified `release` build installs, upgrades over the previous test build and restores a suspended table. 4. Diagnostics redaction test passes. 5. Every M1 ledger row has evidence recorded in `dev-docs/evidence/`. If BLE fails, release requires an explicit LAN-only scope decision by the owner; the ledger row stays open. |
| **M2 Extensions** | WI-13 | Planned separately after M1b evidence. Not a prerequisite of M1. |

## 5. Work items

Why 13 instead of 14: the legal-move generator joins the base rules (WI-02) because the classifier and generator must share one spec and one oracle. Spectator permissions move into the session protocol (WI-05) so privacy is proven in M0 before any radio exists; spectator screens join the room UI (WI-09). Accessibility and effects join the table UI (WI-08) because they are properties of the same composables. Replay, practice and other P2 rows are grouped into WI-13 and deferred.

Paths: `core/test/.../` means `core/src/test/kotlin/` plus the existing package path used by `CardTest.kt`, then the sub-package shown. The same shorthand applies to `session/test/`, `app/test/` (`app/src/test/kotlin/`) and `app/androidTest/` (`app/src/androidTest/kotlin/`). Fixture files live in `core/src/test/resources/fixtures/` as JSON so the iPhone client can consume them.

### WI-01 Foundation and CI parity — S — M0

**Status:** DONE — 2026-10-14
**Changed:** settings.gradle.kts, build.gradle.kts, core/build.gradle.kts, session/build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/gradle-wrapper.properties, .github/workflows/verify.yml, README.md, dev-docs/architecture.md, session/src/**, core/src/test/kotlin/**/FixtureFormatTest.kt, core/src/test/resources/fixtures/format/sample.json
**Verified:** `./gradlew :core:test :session:test :app:assembleDebug :app:lintDebug` BUILD SUCCESSFUL (CardTest 3, FixtureFormatTest 3, SessionModuleSmokeTest 2, 0 failures; lint 0 errors, 3 warnings each explained in gradle/libs.versions.toml); `python3 tools/verify_design_import.py` exit 0; clean-room guard logic run locally, no match


- **Goal:** Set up test and fixture conventions, and make README describe exactly what CI checks.
- **Ledger:** GDN-103, GDN-104, GDN-105.
- **Depends on:** nothing.
- **Changes:** (a) Documentation fix, not a CI gap: CI already enforces token parity through `tools/verify_design_import.py` (it asserts `DEST.read_text() == rendered()`, the same predicate as `--check`). Update README to say so; do not add a duplicate CI step. (b) Add `:session` JVM module (same plugin and JVM 17 setup as `core/build.gradle.kts`). (c) Add `kotlinx-serialization-json` (pinned in `libs.versions.toml`) to `:core` tests and `:session`. Justification: one language-neutral format for fixtures and the wire; KMP-compatible if G3 picks KMP. (d) Resolve each of the 8 lint dependency warnings (`bootstrap-verification.md`): upgrade if compatible with the AGP 8.11 pin, otherwise document why in the version catalog. No lint baseline file. (e) CI guard: fail if any source mentions the upstream package `page.codeberg.lanticy` (clean-room check).
- **First failing tests:** `session/test/.../SessionModuleSmokeTest.kt`; `core/test/.../FixtureFormatTest.kt` (loads one fixture JSON and rejects unknown required fields).
- **Acceptance:** `./gradlew :core:test :session:test :app:assembleDebug :app:lintDebug` passes; README states that `verify_design_import.py` includes the token parity check. Lint shows zero errors and no unexplained warnings.
- **Rollback:** revert the commit; no runtime behavior depends on it.

### WI-02 Base rules: combinations, bombs, wildcards, legal generator — L — M0 — gate G1a

**Status:** DONE — 2026-10-14
**Changed:** core/src/main/kotlin/cn/wpgd/app/core/rules/ (RuleOption, RuleOptions, RuleSettings, RuleProfile, RuleProfileLoader, Combination, Shapes, Classifier, LegalActionGenerator, Sha256); core/src/test/kotlin/cn/wpgd/app/core/rules/ (RuleProfileTest, RuleProfileVariantTest, RuleOptionBehaviourTest, GoldenCaseTest, LegalActionOracleTest, BombOrderTest, WildcardDeclarationTest, CombinationTest, FourJokerBombTest, SevenBomb27CardTest, LargeBombCountOracleTest, GeneratorBudgetTest, PermutedHandTest, Sha256Test); core/src/test/resources/fixtures/rules/golden-v1.json; rules/profiles/P0-provisional.json
**Verified:** `./tools/ci.sh` All local checks passed; `./gradlew :core:test` 86 tests, 0 failures; golden audit 153 cases, FAIL 0; GeneratorBudgetTest records generator time (27-card, two-wildcard hand)
**Update 2026-10-16:** G1a signed by the project owner (`dev-docs/decisions/G1a-base-rules.md`; no rules reviewer; D2 and D11 left as stated defaults). `rules/profiles/P0-provisional.json` became `rules/profiles/P1-base-v1.json` (1.0.0, not provisional, same option values; hash `72aac502…`); RuleProfileTest pins the new canonical form and hash.


- **Goal:** One immutable `RuleProfile` (id, version, source refs, options, hash). A classifier that returns every legal interpretation of a selection. A generator that enumerates every legal action, including each wildcard and suit choice. Declared interpretation is part of the action. **Profile model (2026-10-14):** rules are data, not engine code. An options registry in code defines each option's type, default, source tag and decision ID; profiles are JSON files in `rules/profiles/` that set only the options they change; an unknown option is an error. A new variant is a new profile file, and a new option is one registry entry. See `rules/README.md`.
- **Ledger:** GDN-029, GDN-031, GDN-032, GDN-033, GDN-055.
- **Depends on:** WI-01. Develops against P0-provisional until G1a is signed; G1a is required before any release build.
- **First failing tests:** `core/test/.../rules/RuleProfileTest.kt` (hash stable, unknown option rejects); `rules/CombinationTest.kt` (each type, A-low straight, jokers excluded from sequences); `rules/BombOrderTest.kt` (4–10 card bombs, same-size level bombs, straight flush, four jokers); `rules/WildcardDeclarationTest.kt` (two valid interpretations both offered; forged declaration rejects; jokers cannot be substituted, audit A32); `rules/LegalActionOracleTest.kt` (brute-force subset oracle equals generator on seeded hands ≤ 8 cards; generator output is compared after normalizing to face multiset + declaration); `rules/LargeBombCountOracleTest.kt` (a second, independent oracle over rank/suit multiset counts, not card subsets, so it runs on full 27-card hands: branch A enumerates same-rank bombs per rank, every permitted size up to 10 from natural count + usable wildcards; branch B enumerates the special bombs the signed profile lists, such as four jokers and straight flushes, from suit/rank counts; the union must equal the generator's bomb set); `rules/FourJokerBombTest.kt` (27-card hand with all four jokers and no wildcards: the generator emits the four-joker bomb); `rules/SevenBomb27CardTest.kt` (27-card hand holding all eight physical sevens and both heart-two wildcards at level two; where the signed profile permits it, the generator must include every permitted 9-card and 10-card seven-bomb declaration, i.e. 8 sevens + 1 wildcard, 7 sevens + 2 wildcards, and 8 sevens + 2 wildcards); `rules/GeneratorBudgetTest.kt` (27-card hand with two wildcards).
- **Audit regressions:** A05 (concrete ranks/suits retained), A06 as a generator case (level 2, heart-2 + seven, facing pair of sixes: pair of sevens is legal), A07 as a generator case (four sevens vs a single ace: bomb is legal).
- **Acceptance:** all tests pass; every fixture in `fixtures/rules/` cites a clause of `base-v1.md`; `GeneratorBudgetTest` records time on JVM (device budget checked in WI-08).
- **Rollback:** profile is versioned; a defective rule ships as a new profile version, never an in-place edit of a frozen one.

### WI-03 Deal reducer, typed events, seat projections — L — M0

**Status:** DONE — 2026-10-14 (against P0-provisional; since 2026-10-16 the signed `P1-base-v1`)
**Changed:** core/src/main/kotlin/cn/wpgd/app/core/state/ (Seat, RandomSource, StandardDeck, DealState, StateHash, Command, DealEvent, EventLog, DealReducer, Projections); core/src/test/kotlin/cn/wpgd/app/core/state/ (StateTestSupport, ReducerTest, EventReplayTest, IllegalCommandNoMutationTest, CardConservationPropertyTest, PartnerLeadTest, ProjectionPrivacyTest, SimulationInvariantTest); CoreMultiplatformGuardTest gains an `import java.util.UUID` case; rules/base-v1.md section 9 rows D19–D24 (play order, 接风, deal end, skipping finished seats, ambiguous readings, public table content)
**Verified:** `./gradlew :core:test` 126 tests, 0 failures (SimulationInvariantTest: 10,000 deals, about 30–40 s on the JVM); `./tools/ci.sh` All local checks passed; 10 deliberate reducer mutations each killed by the state suites


- **Goal:** Deterministic reducer `(state, command) -> (state', events) | rejection` with injected RNG. Typed events with visibility class. Projection functions that build each member's view and each AI seat's observation from the same code.
- **Ledger:** GDN-030, GDN-039, GDN-047, GDN-048, GDN-049 (projection), GDN-058 (observation type), GDN-069 (public view content), GDN-091.
- **Depends on:** WI-02.
- **Event log owner:** WI-03 owns the typed event schema and in-memory replay. WI-06 owns persisting it.
- **First failing tests:** `core/test/.../state/ReducerTest.kt` (same seed + profile + commands give same state hash; this hash is host-internal and is never sent to any member); `state/EventReplayTest.kt` (every accepted action replays from typed events alone, with no translated text); `state/IllegalCommandNoMutationTest.kt` (wrong seat, wrong phase, unowned card, duplicate card ID, empty selection); `state/CardConservationPropertyTest.kt` (108 IDs conserved every step, seeded random play); `state/PartnerLeadTest.kt` (active partner, finished partner, all pass combinations); `state/ProjectionPrivacyTest.kt` (section 8 P1); `state/SimulationInvariantTest.kt` (10,000 seeded deals).
- **Audit regressions:** KEEP-04 behaviors (unowned/duplicate rejection, out-of-turn, pass-as-leader rejection, non-mutating illegal follow, partner lead inheritance).
- **Acceptance:** tests pass; no Android or `java.util.UUID` import in `:core` (grep check in CI, matching `architecture.md`).
- **Rollback:** events carry schema version; a reducer change that alters a stored hash requires a new profile or schema version.

### WI-04 Match profile: tribute, levels, passing A, reporting — M — M0→M1a — gate G1b

**Status:** DONE — 2026-10-14 (against M0-provisional; G1b not signed, so not releasable); since 2026-10-16 the signed `M1-match-v1`
**Changed:** rules/match-v1.md (draft G1b spec, rows M1.1–M8.6, decisions D25–D40); rules/profiles/M0-provisional.json; core/src/main/kotlin/cn/wpgd/app/core/rules/ (MatchOptions; ChoiceOption and Decision D25–D40 in RuleOption); core/src/main/kotlin/cn/wpgd/app/core/match/ (MatchSettings, Settlement, TributeRules, MatchState, MatchEvent, MatchCommand, MatchReducer, MatchProjections); state/ gains Phase.DEALT, SetupCommand (Deal, MoveCard, BeginPlay) and events Dealt, CardMoved, PlayBegan; tests in core/src/test/kotlin/cn/wpgd/app/core/match/ and state/SetupCommandTest; RuleProfileTest loads M* profiles against the match registry
**Verified:** `./gradlew :core:test` all suites pass (MatchSimulationTest: 500 seeded matches from level 2 through passing A); `./tools/ci.sh` All local checks passed; 20 deliberate mutations each killed. The rules reviewer still has to sign D25–D40 and the fixture list (G1b).
**Update 2026-10-16:** G1b signed by the project owner alone (`dev-docs/decisions/G1b-match-profile.md`); no rules reviewer signed D25–D40 or the fixture list. `M0-provisional` became `M1-match-v1` (1.0.0, not provisional, same option values; hash `832431cd…`). Audit finding 17: `MatchState.matchNumber` (from 1; a rematch is the next number), carried in `MatchPublicView` (match projection schema 2) and in the state codec (format 2); tests `RematchTest.eachRematchHasTheNextMatchNumber`, `theMatchNumberIsInEveryViewAndInTheSavedState`, `aStateWithoutAMatchNumberIsRefused`.


- **Goal:** Between-deal phases and settlement exactly as the signed profile states.
- **Ledger:** GDN-034, GDN-035, GDN-036, GDN-037, GDN-038, GDN-040, GDN-041, GDN-042, GDN-043.
- **Depends on:** WI-03. Develops against P0-provisional tribute rules until G1b is signed; G1b is required before any release build. Nothing in this item may be coded from design copy alone (`design-handoff.md`).
- **First failing tests:** `core/test/.../match/SettlementTest.kt` (all 24 finishing orders); `match/TributeTest.kt` (single, double, ties, no card loss); `match/ReturnTributeTest.kt` (limit and the all-high exception); `match/ResistanceTest.kt` (two jokers one hand vs split between partners); `match/OpeningLeadTest.kt`; `match/PassingATest.kt` (wins, losses, interruptions, resets); `match/CardCountReportingTest.kt` (hidden counts absent from projection bytes, threshold event emitted once).
- **Audit regressions (expected values come from the profile, not upstream):** A02 (opening lead must follow the profile, not random), A03 (failure counting), A04 (A-team must win its qualifying deal, not an off-level deal), A36 (early double-up policy labeled, inferred ranks marked).
- **Acceptance:** tests pass; each fixture cites a clause of the signed profile; reviewer signs the fixture list.
- **Rollback:** `tributeProfile` flag off falls back to no profile offered in the lobby; matches in progress keep their frozen profile.

### WI-05 Session protocol, admission, spectator grants — L — M0 — gate G2, G4

**Status:** DONE — 2026-10-15 (crypto and grant policy PROVISIONAL: G2 and G4 not signed, so not releasable)
**Update 2026-10-16:** G2 owner-delegated default implemented, no external cryptography review (`dev-docs/decisions/G2-host-authentication.md`): static room key mixed into the key agreement, a fresh nonce in every welcome, rejoin by proof (`Join.rejoinProof`, the capability is never sent by a joiner), pinned host key fingerprint, protocol version 2; wire fixtures regenerated; tests `HostAuthenticationTest` (5), `CryptoTest`, `WireFixtureTest`. G4 deferred by the owner (devices later); the proposed default stays built.
**Changed:** session/build.gradle.kts; session/src/main/kotlin/cn/wpgd/app/session/ (crypto, transport, framing, channel, wire, host, client); session/src/test/kotlin/cn/wpgd/app/session/ (21 test files); session/README.md; core/src/test/resources/fixtures/wire/ (handshake, command, receipt, rejection, frame-envelope, digest); dev-docs/architecture.md
**Verified:** `./gradlew :core:test :session:test` core 192 tests and session 71 tests, 0 failures; `./tools/ci.sh` All local checks passed; 22 deliberate mutations, 20 killed, 2 survivors are backup checks that no input can reach (documented in the agent report)


- **Goal:** Transport-independent session in `:session`: versioned handshake, room id + epoch, capabilities, commands with IDs and expected revision, receipts, per-peer ordered bounded queues, framing with chunk reassembly limits, heartbeat, observer grants with a grant revision, and revocation. Runs over an in-memory transport with fault injection.
- **Receipt contents:** committed revision plus a keyed digest (HMAC with a per-member key set up at admission, G2) over the recipient's own canonical projection. Never a hash of full authoritative state: near the end of a deal a player could enumerate the few remaining hand assignments and match it. The client recomputes the digest over its local projection; a mismatch triggers resync and is counted.
- **Ledger:** GDN-003 (role in handshake), GDN-011, GDN-029 (hash agreement), GDN-016, GDN-022, GDN-023, GDN-024, GDN-025, GDN-026, GDN-027, GDN-028, GDN-049 (delivery), GDN-053, GDN-065, GDN-066, GDN-067, GDN-068, GDN-070, GDN-071 (backpressure), GDN-072, GDN-088, GDN-105 (wire fixtures).
- **Depends on:** WI-03; G2 for admission and encryption, G4 for grant rules. Framing, ordering and receipts can start before G2.
- **First failing tests (`session/test/.../`):** `FramingFuzzTest.kt` (truncated, duplicated, reordered, oversized chunks never yield a partial accepted state); `OrderedDeliveryTest.kt` (delayed writes never regress revision); `IdempotentCommandTest.kt` (retry returns the original receipt); `StaleGenerationTest.kt` (old-epoch or old-connection callbacks cannot close, mutate or act); `AdmissionTest.kt` (unadmitted peer receives no private bytes; version mismatch rejected before hands); `ReplacementTest.kt` (approved replacement invalidates old capability atomically); `ObserverGrantTest.kt` (consent, one view per deal; after the revocation boundary the host emits no new private frames, drops queued private frames, and the client discards any private frame carrying a stale grant revision; departure revokes; replacement needs new consent); `ProjectionDigestTest.kt` (canonical projection encoding and digest are deterministic across runs; digest fixtures shared with iPhone); `ReceiptPrivacyTest.kt` (changing hidden opponent cards leaves every receipt's bytes unchanged, section 8 P1); `ObserverCommandRejectTest.kt`; `SlowObserverTest.kt` (player receipt latency unaffected).
- **Audit regressions:** A14, A15, A16, A17, A18, A19, A20 (rejection reaches issuer), A21 (AI task bound to epoch + turn revision).
- **Acceptance:** all pass; `wire/` fixtures written as JSON for G3 parity.
- **Rollback:** protocol version range rejects incompatible peers with an explicit message; never downgrade an active room.

### WI-06 Event log, checkpoints and recovery policy — M — M0

**Status:** DONE — 2026-10-15 (storage verified only against an in-memory fault injector and the Mac file system; not verified on a phone)
**Update 2026-10-16:** storage schema 3 (the `hostkey` file for G2; match state format 2 with the match number). Schemas 1 and 2 were never released: a pre-release reset, such saves are quarantined (`UNSUPPORTED_OLD_SCHEMA`) and kept; the schema-1 meta migration was removed. A missing or corrupt room key quarantines (`HOST_KEY_UNREADABLE`). Tests `SchemaVersionTest.preReleaseSchemasOneAndTwoAreQuarantinedAndKept`, `aMissingOrCorruptRoomKeyQuarantinesTheTable`, `aRestoredTableKeepsItsRoomKey`.
**Changed:** session/src/main/kotlin/cn/wpgd/app/session/storage/ (Storage, FileStorage, InMemoryStorage, TableJournal, TableRecovery, TableRepository, RestoreValidator); session/.../host/ (Recovery, SessionHost changes); core/src/main/kotlin/cn/wpgd/app/core/match/MatchCodec.kt; session/src/test/kotlin/cn/wpgd/app/session/storage/ and RecoveryPolicyTest, TakeoverRaceTest; app/src/test/kotlin/cn/wpgd/app/BackupRulesTest.kt; app/build.gradle.kts; tools/ci.sh; core/src/test/resources/fixtures/wire/recovery.json
**Verified:** `./gradlew :core:test :session:test :app:testDebugUnitTest` core 196, session 119, app 3 tests, 0 failures; `./tools/ci.sh` All local checks passed; 200 seeded crash-and-recover runs; 22 deliberate mutations, 21 killed and 3 survivors given new tests


- **Goal:** Persisted append-only typed event log (owner of GDN-091 persistence) as the single recovery source. Each record has a checksum and a separate commit marker with its own checksum. A separate high-water-mark file, with its own checksum and written by atomic replace, holds the highest acknowledged revision. Write order per revision: record, sync; marker, sync; high-water mark, sync; only then the receipt. Checkpoints are an atomic, versioned cache that speeds replay; they are never restored on their own. Recovery replays the log up to the last revision whose record and marker both verify; that is the last committed revision. Records are never dropped on marker validity alone: if the recovered revision is below the high-water mark, or the high-water-mark file itself fails its checksum, acknowledged data may be lost, so the room pauses and enters quarantine instead of restoring a lower revision. A committed revision above the high-water mark (crash after the marker, before the receipt) is kept; it was never acknowledged, so a retry of its command returns the original receipt through command-ID dedupe. On rejoin, any member whose last receipt revision is above the recovered revision also forces quarantine. One transition handles disconnect, grace, pause and substitution, both live and after restore. Retention, expiry and explicit deletion of suspended tables.
- **Ledger:** GDN-018 (pause/suspend states), GDN-051, GDN-052, GDN-083, GDN-084, GDN-085, GDN-086, GDN-087, GDN-090, GDN-091 (persistence), GDN-093, GDN-109. GDN-083 says "current or previous valid checkpoint"; here a previous checkpoint is acceptable only as the start of log replay up to the last committed revision, never below the high-water mark.
- **Depends on:** WI-03, WI-05.
- **First failing tests (`session/test/.../storage/` unless noted):** `EventLogCrashTest.kt` (kill at each write step of record, marker, high-water mark and checkpoint; restore yields the last committed revision and never goes below the high-water mark: kill before r11's marker is durable gives r10; kill after the marker and before the receipt gives r11 with no quarantine); `AckedRevisionNeverLostTest.kt` (acknowledge command C at r11, corrupt record r11, restore, retry C: expect rejection and quarantine, never reapplication; corrupt only the marker of r11 with no member online, restart, retry C: expect quarantine because the high-water mark says r11; a member presenting receipt r11 to a room recovered at r10 also forces quarantine); `EventLogRebuildTest.kt` (acknowledge actions across two deals, restart the host, rebuild state from persisted typed events alone, match the checkpoint state hash); `RestoreValidationTest.kt` (bad cardinality, duplicate IDs, wrong turn, profile hash mismatch: quarantined); `SchemaVersionTest.kt` (future schema preserved, not deleted; supported old schema migrates explicitly); `StorageFailurePausesTest.kt` (no receipt after a failed append); `RetentionPolicyTest.kt` (suspended table expires only at the stated time; accidental exit does not delete it); `RecoverableTableDeleteTest.kt` (explicit delete removes log, checkpoints and capabilities together); `session/test/.../RecoveryPolicyTest.kt` (grace, then policy; restored absent human is not stuck, audit A11); `session/test/.../TakeoverRaceTest.kt` (rejoin vs AI: one action, one controller); `app/test/.../BackupRulesTest.kt` (parses manifest and extraction rules; fails if any domain becomes backed up).
- **Audit regressions:** A11, A12, A13, A29, A22 (distinct pause, leave, suspend, end).
- **Acceptance:** tests pass; receipt is sent only after the event record, its commit marker and the high-water mark are durable. Latency cost of the three syncs per action is measured in HP-3.
- **Rollback:** keep reading the previous schema; never delete an unreadable or quarantined save without explicit user action; never resume below an acknowledged revision.

### WI-07 Offline AI and room-governed hints — M — M0→M1a — gate G4 (hints)

**Status:** DONE — 2026-10-15 (hint policy PROVISIONAL: G4 not signed, developed against its proposed default, competitive rooms have no hints; not releasable)
**Changed:** core/src/main/kotlin/cn/wpgd/app/core/ai/ (AiTypes, Decision, HandEvaluator, BaselineHeuristicPolicy, Policies, SeatAiSettings, SeatInputs, Hints); core/src/test/kotlin/cn/wpgd/app/core/ai/ (AiTestSupport, InformationBoundaryTest, BombDecisionTest, WildcardPairTest, PartnerPolicyTest, DeadlineFallbackTest, HintPolicyTest, PairedEvaluationTest, SeatAiSettingsTest, HumanCountMatrixTest); session/src/main/kotlin/cn/wpgd/app/session/ai/ (AiSeatDriver, MemberHints); session/.../host/HostConfig.kt (SeatOptions.resistanceOpen, DeferredSeatPolicy), SessionHost.kt (deferred AI path bound to epoch, revision and controller; in-flight cancellation; fix: an AI task that fired while paused is re-armed on resume); session/src/test/kotlin/cn/wpgd/app/session/ai/ (AiSessionSupport, AiSeatDriverTest, MemberHintsTest, SessionHumanCountMatrixTest, OfflineAiGuardTest); session/README.md; dev-docs/architecture.md; README.md
**Verified:** `./gradlew :core:test :session:test :app:testDebugUnitTest` 0 failures; `./tools/ci.sh` All local checks passed; baseline-heuristic 1.0.0 vs random-legal, 320 seeded deals per seat direction: 629 of 640 deals won (98.3%, Wilson 95% 96.9–99.0%), zero illegal actions, zero fallbacks; 19 deliberate mutations, all killed. Not verified: decision time on the named minimum Android device (WI-08; recorded on the device since 2026-10-15 as diagnostics `owner.ai_decision_ms_p95`, HP-1), HP-5 human partner sessions.


- **Goal:** Baseline heuristic policy using only the seat observation and the WI-02 generator; cancellable, bounded, with a legal fallback; per-seat settings; hints from the same generator obey room policy.
- **Ledger:** GDN-014, GDN-015, GDN-056, GDN-057, GDN-058, GDN-059, GDN-061, GDN-062, GDN-064 (evaluation corpus).
- **Depends on:** WI-02, WI-03 (WI-04 for tribute decisions).
- **First failing tests:** `core/test/.../ai/InformationBoundaryTest.kt` (section 8 P2); `ai/BombDecisionTest.kt` (A07: plays the hand-ending bomb); `ai/WildcardPairTest.kt` (A06: plays pair of sevens); `ai/PartnerPolicyTest.kt` (no needless overtake of a leading partner, A08); `ai/DeadlineFallbackTest.kt` (timeout or exception: one legal fallback action); `ai/HintPolicyTest.kt` (no hints in competitive rooms); `ai/PairedEvaluationTest.kt` (fixed seeds, swapped seats, reports win rate with interval; zero illegal actions).
- **Acceptance:** zero illegal actions in the gate corpus; p95 decision ≤ 1 s on the named minimum Android device (measured in WI-08); HP-5 human partner sessions recorded.
- **Rollback:** switch policy version to the previous one; observation boundary is never relaxed.

### WI-08 Android table, solo play, card interaction, accessibility — L — M1a

**Status:** BLOCKED — 2026-10-15 (code and automated tests complete; device acceptance not run)
**Changed:** app/src/main/kotlin/cn/wpgd/app/{owner,table,data,ui}/, app/src/test/, app/src/androidTest/, app/src/sharedTest/, app/UI-STATE-COVERAGE.md, tools/ci.sh, gradle/libs.versions.toml
**Verified:** `./tools/ci.sh` All local checks passed (`:core` 238, `:session` 135, `:app` 74 unit tests, 0 failures; lint 0 errors; merged manifest has no permission); `connectedDebugAndroidTest` on emulator pixel8: 26 of 26 pass; 14 of 15 deliberate mutations killed (survivor is a no-op by design).
**Blocker:** HP-1 (airplane-mode match on a physical phone), HP-4 (TalkBack deal) and p95 selection time on the named minimum device were not run; they need hardware and a human. Table states T6, T7, T9, T10 belong to WI-09; T11, L5, L7 to WI-11; L4 waits on the layout B decision (see app/UI-STATE-COVERAGE.md). Dark-mode 配 badge fill is about 2.2:1 against the card (text about 7:1).
**Note:** Its dependencies WI-03, WI-06 and WI-07 are done (WI-07 hints PROVISIONAL on G4).
**Update 2026-10-16:** still BLOCKED only on device and human procedures (HP-1, HP-4, minimum-device timing), which the project owner deferred (devices later). The app version is 0.0.1 (code 1) and diagnostics export it (`app.version_name`).


- **Goal:** Lifecycle-independent session owner on Android; solo play with no discovery; the table from the design inventory (`design/table-state-inventory.json` T1–T13, L1–L10); two-row hand layout A; tap and drag selection; pending/rejected feedback; wildcard preview; per-seat last action; settings in-table; effects keyed to event IDs; screen-reader semantics; wake lock only while seated.
- **Ledger:** GDN-005, GDN-008, GDN-050, GDN-073, GDN-074, GDN-075, GDN-076, GDN-077, GDN-078, GDN-079, GDN-080, GDN-081, GDN-082, GDN-088 (stale-state controls), GDN-089, GDN-097, GDN-098, GDN-099, GDN-100, GDN-102.
- **Depends on:** WI-03, WI-06, WI-07; non-blocking design defaults in section 3.
- **New test dependencies:** `androidx.compose.ui:ui-test-junit4` and `androidx.test` runner (pinned; required for any UI test). JUnit for `app/src/test`.
- **First failing tests:** `app/test/.../table/TableViewModelTest.kt` (pending keeps selection; rejection explains and keeps cards; double tap sends one command); `app/androidTest/.../table/CardSelectionTest.kt` (27 cards selectable at 320 dp width and font scale 2.0); `table/WildcardPreviewTest.kt` (payload equals preview); `table/HiddenCountSemanticsTest.kt` (hidden count absent from semantics tree); `table/EventEffectTest.kt` (reconnect does not replay sounds); `SessionOwnerLifecycleTest.kt` (rotation and activity recreation keep the table); `WakeLockTest.kt`; `app/test/.../data/HistoryDeletionTest.kt` (deleting history leaves preferences intact); `data/IdentityResetTest.kt` (reset requires a confirmation that states rejoin access to current tables will be lost).
- **Audit regressions:** A20, A22, A24, A25, A26, A27.
- **Acceptance:** tests pass; HP-1 solo airplane-mode match; HP-4 TalkBack deal; p95 selection < 100 ms measured with a macrobenchmark or frame trace on the named minimum device.
- **Rollback:** layout A stays the fallback; pending/receipt behavior cannot be disabled.

### WI-09 Android rooms, lobby, LAN transport, spectator screens — L — M1a

**Status:** BLOCKED — 2026-10-15 (code and automated tests complete; device acceptance not run)
**Changed:** session/.../lobby/, app/src/main/kotlin/cn/wpgd/app/{room,lan,owner,ui}/, app/permissions-allowlist.txt, tools/ci.sh, app/UI-STATE-COVERAGE.md, dev-docs/architecture.md
**Verified:** `./tools/ci.sh` All local checks passed (`:core` 243, `:session` 148, `:app` 115 unit tests, 0 failures; emulator pixel8 39 of 39; merged manifest requests exactly INTERNET); TransportParityTest: real sockets and in-memory network give identical host state and views.
**Blocker:** HP-2 (four phones over LAN, two spectators) not run; `NsdRoomDirectory` (Android discovery) is tested on one emulator only (`NsdRoomDirectoryTest`, added 2026-10-15), not across phones; G1b not signed; crypto/grants PROVISIONAL (G2/G4). Not built: B2, B3-B6, B8, AI seats by consent, AI difficulty.
**Update 2026-10-16:** G1b is signed and G2 is an owner-delegated default (no external review); G4 and HP-2 are deferred by the owner (devices later). Adverts carry the host key fingerprint (advert format 2); a member that lost its host follows only an advert with its pinned key (`HostPortChangeRejoinTest`). Audit finding 17 fixed: local history keys a deal by (table, match number, deal number) (history format v2; v1 entries read with no match number); `RematchTest.aReturningPlayerKeepsTheOldMatchsDealOneDistinct`.
**Update 2026-10-16 (join by address, emulator end-to-end):** Rooms are found by LAN discovery (NSD) or by address (按地址加入, C10, a production screen from 找桌加入, with a hint about phone hotspots and guest Wi-Fi isolation). The host's lobby shows its Wi-Fi or hotspot addresses and port (B11). The joiner's one plain `room_query` gets a `room_listing` with the advert's public facts (session protocol unchanged at version 2; fixtures `handshake.json`); joining then goes through the unchanged hello, code comparison, approval and G2 pinning. INTERNET only (allow-list exact for debug, release and r8check). Tests: `RoomLookupTest` (9), `ManualAddressTest`, `AddressJoinTest`, `AddressJoinOverLanTest` (loopback sockets: join with code comparison, wrong port, timeout, not a room, ended room, honest and lying key mismatch), `HostAddressesTest`, `LobbyAddressTest`, `AddressJoinUiTest` (device). Emulator end-to-end (`tools/e2e/`): host, three players and one watcher on five emulators played whole matches with one player killed and rejoining by ticket; two runs PASS (`dev-docs/evidence/HP-2-emulator-run-2026-10-16-164828`, `-165456`). That is emulator evidence, not HP-2. Found by the run and fixed: a room stalled for every player when the host's own client lost its local link (liveness 5 s exceeded by a held-up session thread); the room's automatic steps now follow the host on the owner's tick (`HostOwnLinkLostTest`). The driver reads codes through `AutomationHook`, implemented only in `src/debug`; `tools/check_automation_hook.py` keeps it out of release and r8check (mutation run in `dev-docs/automation-hook.md`). Still owed: HP-2 on phones (NSD across devices, real radio, capture, follow/deny/revoke, Wi-Fi interruption), join by address on a real hotspot, the second watcher (did not fit on this Mac).


- **Goal:** Room directory, play-or-watch choice, admission, seat and team arrangement, rules confirmation, ready/start, rematch; nickname storage; LAN adapter (NSD + bounded framing from WI-05); spectator screens G1–G7 and host screens H1–H9 from `design/screen-inventory.json`.
- **Ledger:** GDN-001, GDN-002, GDN-007, GDN-009, GDN-012, GDN-013, GDN-017, GDN-021, GDN-023 (LAN parity); UI for GDN-003, GDN-011, GDN-016, GDN-018, GDN-065, GDN-066, GDN-067, GDN-090.
- **Depends on:** WI-05, WI-06, WI-08; G1b for the rules summary text.
- **First failing tests:** `app/test/.../room/LobbyStateTest.kt` (unready human blocks start; rules change resets ready; duplicate names harmless); `room/NicknameTest.kt` (persisted, bounded length, cannot claim a seat, audit A35); `app/androidTest/.../lan/LanLoopbackSessionTest.kt` (session suite runs over real sockets on one device); `room/TwoRoomsTest.kt`; `room/SuspendChoiceTest.kt` (retention period and expiry are shown before the host chooses suspend; explicit delete of a recoverable table needs confirmation).
- **Acceptance:** HP-2 four phones over LAN plus two spectators; two simultaneous hosts are distinguishable; no internet permission beyond what NSD/LAN sockets need, checked by a manifest test.
- **Rollback:** `spectators` flag hides watch entry and revokes grants; players continue.

### WI-10 Bluetooth transport and device gate — L — M1b — gate G5

**Status:** BLOCKED — 2026-10-15 (software parts complete; device spike and HP-3 not run)
**Changed:** app/src/main/kotlin/cn/wpgd/app/ble/, app/src/main/kotlin/cn/wpgd/app/ui/BluetoothScreens.kt, app/src/test/.../ble/, app/src/androidTest/, app/permissions-allowlist.txt, tools/ci.sh, dev-docs/ble-device-spike.md
**Verified:** `./tools/ci.sh` All local checks passed (`:app` 195 unit tests, 0 failures; emulator 48 of 48 with flag off and on; flag-on build manifest checked exactly; 27 of 27 chunking/link mutations killed; two-emulator room smoke test worked end to end).
**Blocker:** Gate G5 not signed; the device spike (roles, MTU, simultaneous connections, throughput, iPhone as host), HP-3, p95 receipt at 4+2 load and the OEM interruption table need physical phones. No capacity figure is claimed. `bleTransport` is a build property, default off; Android 12+ only.
**Update 2026-10-16:** G5, the device spike, HP-3 and HP-8 are deferred by the project owner (devices later).


- **Goal:** GMS-free BLE adapter on Android (and the iPhone adapter in WI-11) under the WI-05 contract; permission guidance; measured capacity.
- **Ledger:** GDN-004, GDN-019, GDN-022 (device), GDN-071 (capacity), GDN-101.
- **Depends on:** WI-05. **Step 1 runs early, in parallel with M0:** a throwaway spike measuring roles, MTU, simultaneous connections and throughput on G5 phones, including iPhone as host. The result decides whether 4+2 is feasible before UI work depends on it.
- **First failing tests:** `app/test/.../ble/BleChunkingTest.kt` (MTU-sized chunks, loss, duplicates); `app/androidTest/.../ble/PermissionStateTest.kt` (denied and radio-off states are actionable; solo needs no permission); device procedure HP-3.
- **Acceptance:** HP-3 passes in both host directions; p95 receipt < 1 s at 4+2 load; OEM interruption table filled for the G5 matrix.
- **Rollback:** `bleTransport` flag hides the Bluetooth entry; LAN remains; no Bluetooth claim made.

### WI-11 iPhone client and parity — L — M1b — gate G3

**Status:** BLOCKED — 2026-10-15
**Blocker:** Gate G3 (native Swift core vs shared Kotlin core) is an owner decision that needs a measured spike on an iPhone; it is not signed, so no iPhone project was created. The Android-side prerequisites are done: WI-02, WI-03 and WI-05 with shared rule and wire fixtures under `core/src/test/resources/fixtures/`, plus `ios/` folder structure and README. Needs Xcode, a physical iPhone and the owner's G3 sign-off.
**Update 2026-10-16:** G3 decided by the project owner: native Swift core (`dev-docs/decisions/G3-iphone-architecture.md`). `ios/GuandanCore` is a Swift package skeleton (targets `GuandanRules`, `GuandanWire`; test target `GuandanConformanceTests` reads the shared fixtures by path; 7 tests, 1 skipped to mark the port as pending); `tools/ci.sh` builds and tests it when a Swift toolchain is present. **Blocker now:** needs implementation (the Swift rules engine, reducers, projections, wire protocol including G2) and a physical iPhone.


- **Goal:** Native iPhone client over the same rules and wire contract; host and join; Core Bluetooth and LAN adapters.
- **Ledger:** GDN-020; iOS halves of GDN-097, GDN-098, GDN-105, GDN-109.
- **Depends on:** WI-02, WI-03, WI-05, G3.
- **First failing tests:** iOS rule-fixture conformance and wire-fixture decoding against `core/src/test/resources/fixtures/` (test target named when G3 creates the project); mixed-platform table procedure (HP-3 with iPhone host).
- **Acceptance:** identical results on every shared fixture; iPhone hosts an Android+iPhone table; VoiceOver completes a deal (HP-4).
- **Rollback:** do not advertise iPhone; incompatible versions are rejected by handshake.

### WI-12 Release evidence, diagnostics, licensing — M — M1b

**Status:** BLOCKED — 2026-10-15 (software parts complete; release evidence not obtainable here)
**Changed:** release/, app/src/main/kotlin/cn/wpgd/app/{release,diagnostics}/, session/src/main/kotlin/cn/wpgd/app/session/diagnostics/, app/build.gradle.kts, app/proguard-*.pro, tools/ci.sh, dev-docs/evidence/HP-6-release-upgrade.md
**Verified:** `./tools/ci.sh` All local checks passed (`:core` 243, `:session` 154, `:app` 217 unit tests, 0 failures; `assembleRelease` with R8 passes; `R8SerializationTest` 3 of 3 on emulator against the minified build; release build with provisional profiles refuses to start; build-time gate `verifyReleaseDistributable` refuses today).
**Blocker:** HP-6 not run; no real signing key; project license is an OWNER DECISION (release/PROJECT-LICENSE.md); G1a/G1b unsigned so the release gate correctly refuses; WI-11 (iPhone) blocked on G3, so the catalog omits iPhone and Bluetooth.
**Update 2026-10-16:** G1a/G1b signed, so the catalog lists `P1-base-v1` and `M1-match-v1` as `release` with their decision records (CapabilityCatalogTest checks id, version and hash in the record); the project license is recorded as MIT (`LICENSE`, `release/PROJECT-LICENSE.md`, inventory entries `MIT`); `:app:verifyReleaseDistributable` passes, and `tools/ci.sh` first proves it still refuses unsigned copies of the profiles. Remaining blockers: no release signing key on this machine, HP-6 (deferred by the owner, devices later), and the design material's distribution terms (`OWNER-DECISION` entry for the generated design tokens) are not recorded. The catalog still omits iPhone and Bluetooth.


- **Goal:** Truthful capability catalog, license inventory, redacted local diagnostics, signed minified release gate.
- **Ledger:** GDN-046, GDN-064 (asset provenance), GDN-101 (records), GDN-106, GDN-107, GDN-108, GDN-110.
- **Depends on:** WI-04, WI-08 … WI-11.
- **First failing tests:** `session/test/.../DiagnosticsRedactionTest.kt` (no hand, seed, capability or permanent ID in export); `app/test/.../CapabilityCatalogTest.kt` (catalog lists only profiles and transports with recorded evidence); release-upgrade procedure HP-6.
- **Acceptance:** `./gradlew :app:assembleRelease` with minify passes and HP-6 passes; every shipped dependency and font maps to a license entry; owner records the project license.
- **Rollback:** pause distribution on privacy or integrity failure; keep saves.

### WI-13 M2 extensions — to be planned after M1b

**Status:** BLOCKED — 2026-10-14
**Blocker:** Deferred by the plan itself: 'to be planned after M1b'. Not started.


- **Ledger (deliberately deferred, all P2/M2 in the ledger):** GDN-006 tutorial, GDN-010 QR invitation, GDN-044 timed formats, GDN-045 custom profiles, GDN-054 friendly undo, GDN-060 difficulty levels, GDN-063 explanations, GDN-092 replay, GDN-094 summaries, GDN-095 practice fork, GDN-096 export/delete.
- **Reason:** none is needed for the M1 promise; replay and export depend on a stable event schema (WI-03) and grant model (WI-05) that M1 must prove first.

### Ledger traceability (row → acceptance test)

All 110 rows are owned. The 11 deferred rows are listed in WI-13. Every other row maps to at least one named test or human procedure that checks its ledger acceptance text. A test named only in this table still belongs to that work item's first-failing-test list. Where a row has two work items, the first owns the logic and the second owns the UI or device evidence.

| Row | WI | Test or procedure checking the acceptance text |
|---|---|---|
| 001 | WI-09 | HP-1 fresh install reaches an offline room with no login; traffic capture shows no external request |
| 002 | WI-09 | `NicknameTest` (survives restart; cannot claim a seat) |
| 003 | WI-05, WI-09 | `WatcherSeatTest` (watcher joins, four seats unchanged); `PlayOrWatchTest` |
| 004 | WI-10 | `PermissionStateTest` (denied shows recovery and Wi-Fi option; solo needs no permission) |
| 005 | WI-08 | HP-1 (airplane mode, radios off, full match) |
| 007 | WI-09 | `RulesSummaryTest` (tribute, A condition, reporting, assistance shown before ready) |
| 008 | WI-08 | `HistoryDeletionTest`; `IdentityResetTest` |
| 009 | WI-09 | `TwoRoomsTest` (two hosts distinguishable; chosen room joined) |
| 011 | WI-05, WI-09 | `AdmissionTest` (no private bytes before approval); `AdmissionApprovalUiTest` |
| 012 | WI-09 | `SeatArrangementTest` (partners chosen regardless of discovery order) |
| 013 | WI-09 | `LobbyStateTest` (unready human blocks start; empty seats show explicit bot) |
| 014 | WI-07 | `HumanCountMatrixTest` (1–4 scripted humans complete a match under one profile) |
| 015 | WI-07 | `SeatAiSettingsTest` (change visible; applied only at a deal boundary) |
| 016 | WI-05, WI-09 | `ReplacementTest` (old capability invalid; cards and finish state kept); `ReplacementApprovalUiTest` |
| 017 | WI-09 | `RematchTest` (same seats and profile, no rediscovery) |
| 018 | WI-06, WI-09 | `PauseStateTest` (paused table rejects every game command); `ConsequenceConfirmTest` |
| 019 | WI-10 | HP-3 |
| 020 | WI-11 | iPhone fixture conformance; HP-3 with each platform hosting |
| 021 | WI-09 | `LanLoopbackSessionTest`; HP-2 with WAN off; `BleFailureFallbackTest` (offers Wi-Fi; label never claims Bluetooth) |
| 022 | WI-05, WI-10 | `FramingFuzzTest`; `BleChunkingTest` |
| 023 | WI-05, WI-09 | `TransportParityTest` (same fixtures, same state over in-memory and LAN loopback; BLE added in WI-10) |
| 024 | WI-05 | `AdmissionTest` (version mismatch rejected with reason before hands) |
| 025 | WI-05 | `StaleGenerationTest`; `TwoRoomIsolationTest` |
| 026 | WI-05 | `AdmissionTest`; `TransportConfidentialityTest` (captured frames contain no plaintext hand) |
| 027 | WI-05 | `BoundsTest` (oversized frame and stalled hello disconnect; table continues) |
| 028 | WI-05 | `OrderedDeliveryTest`; `IdempotentCommandTest`; `RejectionReceiptTest` |
| 029 | WI-02, WI-05 | `RuleProfileTest`; `ProfileHashAgreementTest` (saved match and every client carry one hash; mismatch rejects) |
| 030 | WI-03 | `CardConservationPropertyTest` |
| 031 | WI-02 | `CombinationTest` |
| 032 | WI-02 | `WildcardDeclarationTest` |
| 033 | WI-02 | `BombOrderTest`; `LargeBombCountOracleTest`; `SevenBomb27CardTest`; `FourJokerBombTest` |
| 034, 035 | WI-04 | `TributeTest` |
| 036 | WI-04 | `ReturnTributeTest` (host validator and client pre-check use the same core) |
| 037 | WI-04 | `ResistanceTest` |
| 038 | WI-04 | `OpeningLeadTest` |
| 039 | WI-03 | `PartnerLeadTest` |
| 040 | WI-04 | `SettlementTest` (all 24 orders; cap and active level explicit) |
| 041 | WI-04 | `PassingATest` |
| 042 | WI-04 | `CardCountReportingTest` |
| 043 | WI-04 | `SettlementTest` (the implemented policy `endEarly` is labeled on every settlement; inferred places are marked). Only one policy exists: the ledger's second one (full finishing order after a double-up) waits on decision D38 (`rules/match-v1.md`), which is the owner's |
| 046 | WI-12 | `CapabilityCatalogTest` (provenance, fixtures and unsupported-settings list per profile) |
| 047 | WI-03 | `ReducerTest` |
| 048 | WI-03 | `IllegalCommandNoMutationTest` |
| 049 | WI-03, WI-05 | `ProjectionPrivacyTest`; `PerMemberDeliveryTest` |
| 050 | WI-08 | `TableViewModelTest` |
| 051 | WI-06 | `TakeoverRaceTest` |
| 052 | WI-06 | `RestoreValidationTest` |
| 053 | WI-05 | `StaleGenerationTest` |
| 055 | WI-02 | `LegalActionOracleTest`; `LargeBombCountOracleTest`; `Generator27CardFixtureTest` (every type) |
| 056 | WI-07 | `BombDecisionTest` |
| 057 | WI-07 | `PartnerPolicyTest` |
| 058 | WI-03, WI-07 | `InformationBoundaryTest` |
| 059 | WI-07 | HP-1 and HP-2 traffic capture show no inference request |
| 061 | WI-07 | `DeadlineFallbackTest` |
| 062 | WI-07 | `HintPolicyTest` |
| 064 | WI-07, WI-12 | `PairedEvaluationTest` (zero illegal, measured strength); license inventory entry |
| 065 | WI-05, WI-09 | `WatcherSeatTest` (deal and bot assignment unchanged); `SpectatorScreensTest` |
| 066 | WI-05, WI-09 | `ObserverGrantTest`; `SpectatorScreensTest` (denial shows public view) |
| 067 | WI-05, WI-09 | `ObserverGrantTest` (P4); `PrivateViewClearTest` (current private UI cleared) |
| 068 | WI-05 | `ObserverGrantTest` (one view per deal) |
| 069 | WI-03 | `ProjectionPrivacyTest` (public view) |
| 070 | WI-05 | `ObserverCommandRejectTest` |
| 071 | WI-05, WI-10 | `SlowObserverTest`; HP-3 |
| 072 | WI-05 | `ObserverGrantTest` (replacement needs new consent) |
| 073 | WI-08 | `CardSelectionTest` |
| 074 | WI-08 | `DragSelectionTest` (six-card sequence, no neighbors selected) |
| 075 | WI-08 | `SortPreservationTest` (IDs, selection and manual groups survive) |
| 076 | WI-08 | `WildcardPreviewTest` |
| 077 | WI-08 | `TurnClarityTest` (trick owner and next actor in text and semantics); HP-4 scenario |
| 078 | WI-08 | `ReconnectTrickDisplayTest` |
| 079 | WI-08 | `HiddenCountSemanticsTest` |
| 080 | WI-08 | `MutedCuesTest`; `ReducedMotionTest` |
| 081 | WI-08 | `InTableSettingsTest` (no disconnect; revision unchanged) |
| 082 | WI-08 | `ResultExplanationTest` (A failure, tribute next step, neutral spectator wording) |
| 083 | WI-06 | `EventLogCrashTest` |
| 084 | WI-06 | `SchemaVersionTest` |
| 085 | WI-06 | `RejoinOwnershipTest` (duplicate names, seat claims, new connections, roles); `TwoRoomIsolationTest` (other room); `HostPortChangeRejoinTest` (changed host port: a lost player follows its room id to the new address) |
| 086 | WI-06 | `RecoveryPolicyTest` |
| 087 | WI-06 | `RecoveryPolicyTest` (no bot play inside grace) |
| 088 | WI-05, WI-08 | `HeartbeatTest` (blackholed peer detected within limit); `TableViewModelTest` and `TableControlsTest` (every control disabled while the link is stale or lost; reconnect offered) |
| 089 | WI-08 | `SessionOwnerLifecycleTest` (recreation keeps table; shutdown cancels all jobs) |
| 090 | WI-06, WI-09 | `RetentionPolicyTest`; `RecoverableTableDeleteTest`; `SuspendChoiceTest` |
| 091 | WI-03, WI-06 | `EventReplayTest`; `EventLogRebuildTest` |
| 093 | WI-06 | `EventLogRebuildTest`; `EventLogCrashTest`; `AckedRevisionNeverLostTest` (high-water-mark rule) |
| 097 | WI-08, WI-11 | `CardSemanticsTest`; HP-4 (TalkBack and VoiceOver) |
| 098 | WI-08, WI-11 | `TouchTargetTest` (48 dp / 44 pt); `LargeTextClipTest` (200%) |
| 099 | WI-08 | `ContrastTokenTest` (computed from `design/tokens.json`); HP-4 grayscale |
| 100 | WI-08 | `WakeLockTest`; HP-3 battery and temperature record |
| 101 | WI-10, WI-12 | HP-8 OEM interruption matrix |
| 102 | WI-08 | Selection macrobenchmark on minimum device; `NoMainThreadAiTest` |
| 103 | WI-01 | CI builds only through `./gradlew` with the checksummed wrapper |
| 104 | WI-01 | `AuditRegressionIndexTest` (each audit ID in this plan has a tagged test); `IllegalCommandNoMutationTest` corpus |
| 105 | WI-01, WI-05, WI-11 | `FixtureFormatTest`; wire fixtures; iPhone fixture decoding |
| 106 | WI-12 | HP-6 (signed upgrade, restore, then a mixed Android + iPhone deal) |
| 107 | WI-12 | `LicenseInventoryTest` (every resolved runtime dependency and bundled asset has an entry) |
| 108 | WI-12 | `DiagnosticsRedactionTest` (revisions and errors present; secrets absent) |
| 109 | WI-06, WI-11 | `BackupRulesTest`; iPhone storage exclusion test |
| 110 | WI-12 | `CapabilityCatalogTest`; review of store and help text against the catalog |

**Audit IDs not reproduced as regressions:** A28 (missing tests and wrapper) and A32 (inaccurate upstream docs) describe the upstream repo, not behavior; their intent is covered by WI-01 and WI-12. A31 and A34 are absent-capability findings covered by WI-11 and WI-08. A10 is covered by heartbeat tests in WI-05.

## 6. Verification

### Runs in this repo today

Updated 2026-10-15. `verify.yml` has one step, `./tools/ci.sh`; the repository has no remote, so it has never run there, and `./tools/ci.sh` is run locally instead. "In CI" below means "run by `tools/ci.sh`".

| Command | Source of truth | In CI today |
|---|---|---|
| Clean-room guard (no tracked or untracked source names `page.codeberg.lanticy`) | `tools/ci.sh` | Yes |
| `python3 tools/generate_design_tokens.py --check` | `tools/generate_design_tokens.py` (argparse `--check`), README | Not run directly; the same parity predicate runs inside `verify_design_import.py` |
| `python3 tools/verify_design_import.py` | README, `tools/ci.sh` | Yes |
| `python3 -I tools/golden/build.py --check` then `tools/golden/audit.py` | `tools/golden/`, `rules/golden/README.md` | Yes (fails if the committed file differs from the generator, and on any FAIL) |
| `./gradlew :core:test :session:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease` | `tools/ci.sh` | Yes |
| `./gradlew :app:verifyReleaseDistributable` | `tools/ci.sh`, `app/build.gradle.kts` | Yes; it must refuse while a profile is provisional or lacks a signed decision record, or the project license is unrecorded. Since 2026-10-16 it passes; `tools/ci.sh` first runs it against unsigned copies of the profiles (`-Pwpgd.gateProfilesDir`) and requires a refusal, and reports a missing release signing key |
| `cd ios/GuandanCore && swift build && swift test` (warnings as errors) | `tools/ci.sh`, `ios/GuandanCore/` | Yes when a Swift toolchain is installed (G3, 2026-10-16); skipped with a message otherwise |
| `./gradlew -Pwpgd.testBuildType=r8check :app:assembleR8check :app:assembleR8checkAndroidTest` | `tools/ci.sh` | Yes (built only; running needs a device) |
| `./gradlew -Pwpgd.bleTransport=true :app:assembleDebug :app:lintDebug`, then merged-manifest permission checks for both builds | `tools/ci.sh`, `app/permissions-allowlist.txt` | Yes |
| `python3 -I tools/check_automation_hook.py present` (debug) then `absent` (release and r8check APKs, compiled classes, R8 mappings, merged manifests); release and r8check merged manifests request exactly the allow-list (2026-10-16) | `tools/ci.sh`, `dev-docs/automation-hook.md` | Yes |
| `python3 -I tools/e2e/run_e2e.py …` multi-emulator LAN match (join by address, kill and rejoin, watcher privacy, identical results) | `tools/e2e/README.md` | No: needs five or six running emulators (or phones) |

### Added by milestone (automatable)

| Milestone | Command | Notes |
|---|---|---|
| M0 | `./gradlew :core:test :session:test` | `:session` created by WI-01 |
| M0 | `./gradlew :core:test --tests '*LegalActionOracleTest'` | Run alone when changing the generator |
| M1a | `./gradlew :app:testDebugUnitTest` | AGP default task; needs test deps from WI-08 |
| M1a | `./gradlew :app:connectedDebugAndroidTest` | Needs a connected device or emulator; not in hosted CI unless an emulator job is added |
| M1b | `./gradlew :app:assembleRelease` | AGP default `release` variant; minify enabled by WI-12 |
| M1b | iOS test command | Named by WI-11 once G3 fixes the project layout (e.g. `xcodebuild test` or `./gradlew :core:iosSimulatorArm64Test` under KMP). G3 decided 2026-10-16: `swift test` in `ios/GuandanCore` (runs today; structural fixture checks only until the port) |

These Gradle task names follow AGP and Kotlin plugin conventions. They were not executed while writing this plan. Since then every row except the iOS one has run: the Gradle rows inside `tools/ci.sh`, and `connectedDebugAndroidTest` on the `pixel8` emulator (not part of `tools/ci.sh`).

### Needs real devices or people

| ID | Procedure | Why automation is not enough |
|---|---|---|
| HP-1 | Solo match, airplane mode, Bluetooth and Wi-Fi off, Android (and iPhone after WI-11) | Proves no hidden network or permission dependency |
| HP-2 | Four Android phones + two spectators, LAN, full match | Real NSD, sockets, OEM power management |
| HP-3 | 4 players + 2 spectators, Bluetooth only, 60 minutes, both host platforms; record battery and temperature | BLE capacity, thermal behavior, backgrounding |
| HP-4 | TalkBack and VoiceOver user completes a deal; 200% text; grayscale | Screen-reader usability is a human judgment |
| HP-5 | Human partner sessions with the baseline AI | Partner quality is not captured by win rate |
| HP-6 | Install signed release N-1, suspend a table, upgrade to N, restore; then a mixed Android and iPhone table completes at least one deal | Signing, upgrade path and post-upgrade interoperability |
| HP-7 | Older-player hand-layout test (A vs B) | Open design decision |
| HP-8 | OEM interruption matrix: background, lock/unlock, radio loss, process kill on each G5 device | Vendor power management differs per device |

Each procedure is written as a checklist in `dev-docs/evidence/` before it is run (index: `dev-docs/evidence/README.md`), and results record device model, OS version and radio settings.

**2026-10-16:** HP-1 … HP-8 are deferred by the project owner (devices later), together with gates G4 and G5. None has been run.

## 7. Risks and how each is exposed

| Risk | Mechanism that exposes it |
|---|---|
| Rule spec gaps or conflicts (e.g. passing-A rule in design README vs handoff) | G1 fixture review: every fixture must cite a clause; a fixture with no citable clause is a finding |
| Generator misses a legal action | `LegalActionOracleTest` (subsets, ≤ 8 cards); `LargeBombCountOracleTest` and `SevenBomb27CardTest` (9–10 card bombs on 27-card hands) |
| Generator too slow with two wildcards (audit A27: 52² substitutions) | `GeneratorBudgetTest` on JVM; frame trace on the minimum Android device in WI-08 |
| Private data leaks through a projection, log or accessibility tree | P1–P3 property tests; `HiddenCountSemanticsTest`; `DiagnosticsRedactionTest` |
| Client state diverges from host | Keyed digest of the recipient's own projection in every receipt (`ProjectionDigestTest`); `ReducerTest` determinism; mismatch forces resync and is counted |
| Receipt leaks hidden cards through a hash | `ReceiptPrivacyTest` (receipt bytes unchanged when hidden opponent cards change) |
| Recovery silently goes below an acknowledged revision | `AckedRevisionNeverLostTest` |
| BLE cannot carry 4+2, or iPhone cannot host reliably | WI-10 step 1 spike measurements, before M1a UI depends on BLE |
| KMP does not work for iPhone | G3 spike: build, run fixtures on simulator and device, measure binary size |
| Durable-before-receipt makes BLE feel slow | p95 receipt latency measured under HP-3 load |
| Old callbacks act in a new room | `StaleGenerationTest` with injected delays |
| Upstream GPL code is copied by accident | CI grep for `page.codeberg.lanticy`; review rule in WI-01 |
| Toolchain pins age past store target-SDK rules | WI-12 release checklist checks current store requirements before M1b |
| Baseline AI is a poor partner | `PairedEvaluationTest` plus HP-5 |
| Large-text layout breaks the 27-card hand | `CardSelectionTest` at font scale 2.0 and 320 dp |

## 8. Kill-switch and privacy invariants (testable)

Privacy properties:

| ID | Property | Test |
|---|---|---|
| P1 | For any member, changing cards they are not authorized to see leaves their serialized projection and every receipt they receive byte-identical | `ProjectionPrivacyTest` (WI-03), `ReceiptPrivacyTest` (WI-05) |
| P2 | Changing hidden opponent cards with the same observation leaves AI input and output unchanged | `InformationBoundaryTest` (WI-07) |
| P3 | A public spectator view contains no hand, hint or unused wildcard declaration | `ProjectionPrivacyTest` (WI-03) |
| P4 | After the revocation boundary the host emits no new private frames for that grant, drops queued ones, and the client discards any private frame with a stale grant revision. Frames already handed to the transport may still arrive, and information already received cannot be retracted. | `ObserverGrantTest` (WI-05) |
| P5 | A spectator may hold at most one private grant per deal; a new seat needs a new consent | `ObserverGrantTest` (WI-05) |
| P6 | An unadmitted or wrong-epoch peer receives no private bytes and changes no state | `AdmissionTest`, `StaleGenerationTest` (WI-05) |
| P7 | Nothing private is backed up or transferred by the platform | `BackupRulesTest` (WI-06) |
| P8 | Diagnostics contain no hands, seeds, capabilities or permanent IDs | `DiagnosticsRedactionTest` (WI-12) |
| P9 | No network request other than nearby session traffic during play | HP-1 and HP-2 with traffic capture |

State invariants (checked in `SimulationInvariantTest` and at restore): exactly four playing seats; 108 unique card IDs conserved; one controller per seat; spectators never issue accepted commands; profile hash constant for a match; committed revision strictly increasing; each command ID applied at most once; receipt only after the event record, its commit marker and the high-water mark are durable; recovery never resumes below the highest acknowledged revision.

Kill-switch: if any of these is observed in the field or in testing, turn off the affected entry (`bleTransport`, `spectators` or `tributeProfile`), keep saves, show an explicit suspension message, and never switch rules or protocol inside an active room:

| Condition | Detector |
|---|---|
| Private hand or capability disclosure | P1–P8 tests; diagnostics report |
| Host/client state divergence | Receipt projection-digest mismatch counter |
| Lost acknowledged action | `AckedRevisionNeverLostTest`; in the field, quarantine count when a member's receipt revision exceeds the recovered revision |
| Repeated crash or stall | Local crash/stall counters in diagnostics |
| Transport breaks the offline promise | HP-1/HP-3 traffic capture |

If BLE fails its gate, a LAN-only release needs an explicit owner decision, and GDN-019 stays open in the ledger.
