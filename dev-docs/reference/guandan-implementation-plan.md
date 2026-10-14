---
title: "Guandan improved mobile game — implementation and verification plan"
created_at: "2026-10-13 17:38 Asia/Shanghai"
mode: "full-plan"
baseline_commit: "d66c38a1ff4ee6691e5a3ce621fc170caad5b19e"
status: "Proposed; no product implementation performed"
---

## Outcomes

Deliver a local gathering game: one to four humans play on their phones with offline AI filling four seats; Android and iPhone can host and join; Bluetooth-only play works on the supported matrix; two additional invited observers can watch an authorized viewpoint. Make rules explicit, actions reliable, recovery understandable and card handling accessible. Retain account-free use and local Wi-Fi as an explicit fallback.

The source audit is `guandan-code-audit.md`; the complete 110-row target ledger is `guandan-feature-ledger-improved.md` with CSV/JSON equivalents. Ledger IDs are requirements; work items group implementation responsibility. Priorities/stages are proposed ordering, not completion status.

Non-goals: physical-card scanning, desktop assistance, physical-table earphone advice, cloud accounts, public matchmaking, wagers/chip economies, global rankings, voice chat and automatic host migration. Digital replay/hints do not depend on physical-card work. No claim of all mainland variants: only reviewed, source-backed and tested profiles are supported.

## Constraints & Dependencies

- Baseline toolchain: AGP 8.7.1, Kotlin 2.0.21, intended Gradle 8.9, JVM target 17, Android minSdk 26/compileSdk and targetSdk 35. These are upstream settings, not a commitment to retain obsolete target requirements at release. Validate official requirements when implementing release work.
- Baseline has Android source only. iOS app/code must be located and audited or implemented. Minimum iOS version is unresolved; use a declared baseline selected by the device spike rather than imply universal coverage.
- Cross-platform core recommendation: isolate deterministic rules/state first, then test Kotlin Multiplatform feasibility. Replace Java UUID/random dependencies with injected portable interfaces. If KMP integration fails the build/runtime spike, use two native cores against identical language-neutral fixtures; do not claim shared logic without proof.
- BLE must use native, GMS-independent capabilities for the mainland-market device matrix. Central/peripheral roles, simultaneous connections, MTUs, background behavior and capacity must be measured. Framework availability is not capacity evidence. No mandatory Google Play services or external inference.
- Four players + two observers is the initial tested capacity target; observer expansion is not implied. Default supports a host authority, not a distributed mesh; suspend when host unavailable.
- Required secrets: none for normal local play; release signing material is managed separately from source. Session keys/capabilities are ephemeral application data, not global credentials.
- Source fork/distribution must carry GPL notices and corresponding-source process; AI models and datasets need their own verified terms before inclusion.
- Flags: `bleTransport`, `tributeProfile`, `spectators`, `learnedPolicy`, `friendlyUndo`, `replayPractice`. Developer testing may enable individually. Intended M1 functionality must pass with required flags enabled before being advertised.

## Current Behavior Inventory

Launch uses Activity + ViewModel. Host publishes fixed mDNS name; first resolved service connects over newline JSON/TCP. Four seat arrays assign members in join order. Engine validates cards/turn, updates mutable state, sends per-seat snapshots and directly overwrites a JSON checkpoint. Empty seats become heuristic bots; disconnect immediately converts to bot. Restore marks absent humans disconnected without equivalent control policy. UI renders snapshots and clears selection when sending. Results require host next-deal command. Preferences retain theme/sound and installation UUID; history is translated text, cleared each deal and truncated to 40 entries in views.

Confirmed core observations include missed wildcard pair, responding-bomb refusal, A-failure resets, off-active-level match win, restored absent human not a bot, valid bomb ordering, invalid-action rejection and partner lead inheritance. Source risks include unauthenticated fallback seating, unordered writes, stale callbacks/timers, unbounded reads/queues and non-atomic saves. See audit evidence labels; risk is not a claim of physical-device reproduction.

## Target Rules

1. **Authority and consistency:** one authority per room epoch. Accept commands only from authenticated member capability with correct epoch, revision/turn and phase. Assign one command ID per intent; duplicate retries return the original receipt. Rejected actions do not mutate state.
2. **Rules precedence:** frozen profile/version/hash → validated explicit overrides → event-specific state. No client-local rule preference overrides host profile. Rule changes require new agreement at next match, never silently during a deal.
3. **Private data:** players get their own cards and profile-permitted public data. AI receives the identical information boundary. Observer capabilities are read-only and specify public-only or one approved seat. Restrict actual payloads, not just visible widgets.
4. **Observation consent:** private follow requires the human seat owner's approval; host approves following a bot. Default one private viewpoint per deal. Revocation stops future private delivery and clears cached private UI; it cannot retract already observed information. Replacement player needs new consent.
5. **Session admission:** displayed nickname is not authentication. Rejoin uses a room/seat capability. New player replacing an absent human requires explicit host approval and invalidates old control permission atomically.
6. **Disconnect:** a proposed default 10-second grace pauses the affected turn; then the room's agreed pause/substitute policy applies. Treat restore the same way. Exact delay is configurable in lobby, not a hidden transport decision. Rejoin races against AI resolve at one turn boundary.
7. **Randomness and replay:** authority generates one deal with injected seed/RNG; do not distribute hidden-deal seed during live play. Persist enough private state to restore authority and enough authorized events to reconstruct each client. Replays preserve visibility and label inferred finishing order.
8. **Durability:** checkpoint/event commit succeeds before action is acknowledged as durable. Use atomic replace and last-valid generation. Storage failure pauses rather than falsely promising recovery. Schema incompatibility preserves data for recovery, not silent deletion.
9. **Feedback:** pending play retains selection until receipt; rejection explains why. Effects derive from accepted event IDs and are not replayed as new sounds on reconnect. Stale state disables action controls.
10. **Nearby promise:** clearly label current connection method. BLE-only test uses Wi-Fi and mobile data off. Wi-Fi fallback is explicit. Unsupported/denied permission states remain actionable; solo mode needs no nearby permission.
11. **Lifecycle:** session owns network/jobs independent of screens. Every delayed job and callback is bound to room epoch and connection/turn generation. Leaving menu, rotation, settings and foreground-service transitions cannot mutate unrelated sessions.
12. **Termination/format:** profile resolves tribute, return, resistance, opening seat, level changes, A eligibility/attempts, card reporting and early-double-up settlement. Timed formats finish current deal and prevent another start; never cancel an in-flight accepted action to meet a clock.

Boundary cases: no peers; one human; all seats full; duplicate names; unknown/oversized frames; extra/missing JSON fields; no eligible return card; tied double tribute; split jokers; duplicate card IDs; empty selections; wrong phase/turn; finished partner; both teams at A; opponent winning an A challenge; interrupted attempts; delayed receipt; old room reconnect; observer revoke during send; process kill during write; low storage; unsupported saved schema; Bluetooth permission denial; radio off; screen lock; two hosts in one room; future profile capabilities.

## Decision Log

| ID | Decision | Alternatives and rationale |
|---|---|---|
| D1 | Local host-authoritative session | Mesh/distributed full-state replication complicates hidden-card privacy and consensus. Host loss suspends; explicit recovery precedes migration work. |
| D2 | Reviewed rule profiles | A blanket regional/national label hides contradictory event clauses. Custom settings use only supported, validated options. |
| D3 | Bluetooth is a measured launch gate | LAN alone is simpler but does not meet the stated Bluetooth scenario. Retain LAN for fallback while testing native BLE. |
| D4 | Competent offline baseline AI first | A chat model does not provide legal move completeness or reproducible decisions. Learned policy is an optional licensed, measured upgrade. |
| D5 | Common executable core/fixtures before platform work | KMP is preferred subject to a spike; duplicated native cores remain acceptable only with conformance parity. |
| D6 | One private observer viewpoint per deal | Unlimited switching lets an observer collect opponents' hidden hands. Public-only viewing remains available. |
| D7 | Legacy sessions are separate | New protocol/rules do not silently rewrite old match semantics. Legacy profile may be retained as labeled compatibility; corrected defaults are separate. |

## Open Questions

| Question | Why it matters | Who decides / proposed default |
|---|---|---|
| First tribute-enabled profile and exact source edition? | Correct tribute ties/resistance/return and A criteria cannot be inferred from region name. | Xiaolai + knowledgeable Guandan reviewers; default select one documented event profile and state every override. |
| Does passing A require the team's active challenge deal; how count failures? | A03/A04 could reflect a niche policy rather than every ruleset. | Rules reviewer; proposed corrected profile requires its defined active A challenge and explicit attempt policy. |
| Minimum phones and iOS baseline? | BLE roles and local AI budgets vary. | Device spike + Xiaolai; retain Android 8 as provisional floor, no iOS floor promise until tested. |
| Existing upstream iOS client available for reuse? | License/source accessibility and interoperability are currently unverified. | Engineering research; implement native client if unavailable. |
| Observer cap beyond two? | BLE throughput/background capacity is empirical. | Device results; initially cap at tested 4+2. |
| Friendly versus competitive assistance defaults? | Hints/private following change table information policy. | Xiaolai; default friendly permits declared hints, competitive disables live hints/private following. |
| Baseline AI strength adequate with humans? | Legal correctness alone does not make a good partner. | Paired evaluation + human sessions; no highest-strength claim without evidence. |

These do not block the audit/ledger. They gate the dependent implementation slices; independent build/core/transport characterization proceeds first.

## Data Model

Suggested portable types (target, not existing implementation):

- `RuleProfile(profileId, version, sourceRefs, optionValues, validationVersion, hash)`; immutable during match.
- `Room(roomId, epoch, protocolRange, capacity, policy, authorityId)`; epoch changes on a new authority/session, not every port change.
- `Member(memberId, displayName, role, seatId?, capabilityRef, connectionGeneration, readiness)`; observer is not a fifth seat.
- `Seat(seatId, teamId, controllerType, ownerMemberId?, substitutionPolicy, aiPolicyVersion)`.
- `GameState(revision, phase, dealId, activeLevel, activeTeam, teamLevels, AAttempts, hands, currentTrick, finishOrder, result)`.
- `GameEvent(eventId, revision, dealId, actor, typedPayload, visibility, profileHash)`; event order is canonical and text is generated for display.
- `Command(commandId, epoch, turnId, expectedRevision, capabilityRef, typedIntent)`; receipts carry result/rejection and committed revision.
- `Checkpoint(schemaVersion, epoch, profile, committedRevision, state, integrityMetadata, writtenAt)`; integrity checksum detects corruption, not hostile tampering; use authenticated storage/transport where needed.
- `ObserverGrant(grantId, watcherMemberId, seatId, dealScope, status)`; server maintains grant; revocation clears future private projection.

## API / Contract Changes

Introduce protocol v2 handshake/admission/capabilities, room descriptor, profile agreement, ready commands, observer grants, commands/receipts, heartbeat and full snapshot/event resynchronization. Serialization fixtures must cover Kotlin and iOS. Unknown optional fields may be ignored; unknown required capabilities must reject explicitly. BLE wraps versioned framed bytes; TCP uses bounded framing rather than unbounded lines. Large snapshots are chunked/reassembled with strict size and timeout limits; receipts and player actions have priority over observer snapshots.

Do not let clients send arbitrary `forSeat` requests or forge private-view grants. Error messages distinguish incompatible version, full table, denied admission, stale state, rule violation, permission/radio failure and host unavailable.

## Migration Plan

1. Preserve original unversioned save before parsing. Validate list sizes, IDs, hands, turn, phase and result against the original engine's semantics. Treat its human-readable log as text only; do not fabricate historic events.
2. Import valid state into a labeled legacy rule profile and versioned checkpoint, retaining original file until verified migration commit. Assign a new session epoch and explicit rejoin/admission because original installation IDs are not capabilities.
3. New corrected/tribute profiles begin new matches after all clients agree. v1 peers do not join v2 silently; compatibility mode, if retained, is isolated and explicit.
4. Preferences migrate independently; nickname defaults remain editable. Do not backfill rankings/history from incomplete text logs.
5. Keep schema versions readable for supported upgrades; unknown future saves are quarantined with recovery guidance. Rollback preserves old file but cannot reconstruct newly accepted v2 actions in a v1 engine. Stop/suspend active v2 rooms before rollback; do not downgrade their state.

Invariants: exactly four playing seats; unique 108-card deck identities conserved; valid phase/turn; one controller per seat; observers read-only; no private payload without grant; profile hash constant per match; strictly increasing committed revision; checkpoint/event agreement; each command applied at most once; restored authority remains the only writer. Validate these in unit/property/integration tests and at load, not via database queries (no database is required initially).

## Observability

Measure discovery/admission latency, reconnect delay, accepted/rejected command count, state revision lag, write-queue depth, AI decision duration/fallback count, checkpoint duration/failure, BLE chunk retries and UI latency. Store bounded local diagnostics, disabled detailed tracing by default. Exclude hands, seeds, capabilities, permanent IDs and received private data from default diagnostics. Show players actionable connection/storage errors without exposing protocol internals.

Provisional budgets: p95 UI selection <100ms; p95 action receipt <500ms LAN/<1s BLE at 4+2 load; baseline AI <=1s with legal timeout fallback; restore to last committed revision; recovery goal <10s after connectivity returns and host is available. Budgets must be measured on named minimum hardware. A 60-minute thermal/battery session is an evidence requirement, not an unmeasured battery-percent promise.

## Work Items

### WI-001: Build and verification foundation

- Priority / stage / estimate: P0 / M0 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-103, GDN-104, GDN-105.
- Goal: Complete pinned build setup, extract core test seam, and preserve audited reproducers as regression cases.
- Acceptance (measurable): Clean checkout builds with Gradle 8.9/JDK 17 or validated 21; debug and releaseTest compile; existing behaviors recorded before fixes; test inventory and CI gating present.
- Tests (first): `build/BuildSmokeTest; core/RulesRegressionTest.kt; core/StateInvariantTest.kt; protocol/FixtureCompatibilityTest.kt`. Intent: Initially reproduce A03/A04/A06/A07/A11; detect accidental semantic change; classify expected changes by profile. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: gradlew, gradle/wrapper, app/build.gradle.kts, workflows, new core/test modules; characterize portable-core/iOS integration feasibility.
- Dependencies: None; baseline inspection and current source fixtures.
- Risks + mitigations: Dependency resolution can mask source failures; use pinned fresh-environment job and explicit cache/network diagnostics.
- Rollback: Tooling/test-only changes can revert independently; keep baseline fixtures and source revision archived.

### WI-002: Rule specifications and profile engine

- Priority / stage / estimate: P0 / M0→M1 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-029, GDN-031, GDN-032, GDN-033, GDN-034, GDN-035, GDN-036, GDN-037, GDN-038, GDN-040, GDN-041, GDN-042, GDN-044, GDN-045.
- Goal: Specify profiles and implement complete tribute/return/resistance/opening/level/A/reporting policies.
- Acceptance (measurable): Every supported profile has provenance and golden fixtures; all finishing permutations, A boundaries and tribute ties pass; unknown overrides reject; frozen hash identical across clients.
- Tests (first): `core/RuleProfileTest.kt; core/TributeTest.kt; core/AChallengeTest.kt; core/WildcardDeclarationTest.kt; fixtures/rules/*.json`. Intent: Write expected outcomes from chosen source before engine edits; cover return exceptions, resistance split jokers, A ownership and sequence boundaries. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Rules.kt, GameEngine.kt, Combo.kt, portable RuleProfile and profile validators; room profile agreement.
- Dependencies: WI-001
- Risks + mitigations: Regional clauses may conflict; do not merge them implicitly. Require rule-owner signoff on each new profile.
- Rollback: Keep labeled legacy profile; stop new-profile sessions before disabling. Never resume under different rules.

### WI-003: Deterministic state and structured events

- Priority / stage / estimate: P0 / M0 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-030, GDN-039, GDN-043, GDN-047, GDN-048, GDN-054, GDN-091.
- Goal: Create replayable authoritative reducer, state invariants and phase-aware commands.
- Acceptance (measurable): Same seed/profile/events yields same hash; invalid actions mutate nothing; card conservation holds; early settlement and complete-order modes explicitly tested.
- Tests (first): `core/ReducerTest.kt; core/CardConservationPropertyTest.kt; core/PhaseTransitionTest.kt; core/EventProjectionTest.kt`. Intent: Generate legal/illegal sequences; exhaust pass/finished-seat boundaries and result permutations; compare replay to checkpoints. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: GameEngine.kt, Card.kt, new GameEvent/StateReducer/Rng interfaces; remove Android/network dependencies from core.
- Dependencies: WI-002
- Risks + mitigations: Refactor can change rule behavior; run original fixtures and new-profile fixtures separately.
- Rollback: Retain v1 reader for valid legacy saves; new event schema can roll back only with matching checkpoints.

### WI-004: Authenticated ordered session protocol

- Priority / stage / estimate: P0 / M0 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-022, GDN-023, GDN-024, GDN-025, GDN-026, GDN-027, GDN-028, GDN-049, GDN-053.
- Goal: Replace seat/socket coupling with versioned capabilities, bounded framing, revisions, receipts and session-owned callbacks.
- Acceptance (measurable): Two-room and stale-generation tests pass; duplicates apply once; outsider cannot receive private hand; delays never regress revision; limits reject malformed/stalled peers.
- Tests (first): `protocol/AdmissionTest.kt; protocol/OrderedDeliveryTest.kt; protocol/FrameFuzzTest.kt; protocol/StaleGenerationTest.kt; protocol/PrivateProjectionTest.kt`. Intent: Fault-inject old connection cleanup, overlapping connects, reordered writes, command retries, oversized lines/chunks and observer commands before production protocol edits. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Messages.kt, GameController.kt, NsdHelper.kt, PROTOCOL.md; new SessionManager, PeerWriter, capability and framing modules.
- Dependencies: WI-001, WI-003
- Risks + mitigations: Crypto or framing misuse undermines privacy; use reviewed libraries and platform-secure randomness; test both transports through one contract.
- Rollback: Reject v2 when disabled; optionally offer isolated legacy LAN mode, never downgrade an active v2 room.

### WI-005: Durable recovery and lifecycle

- Priority / stage / estimate: P0 / M0→M1 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-018, GDN-051, GDN-052, GDN-083, GDN-084, GDN-085, GDN-086, GDN-087, GDN-088, GDN-089, GDN-090, GDN-093, GDN-109.
- Goal: Unify disconnect/restore/grace/substitution, atomic persistence and session lifecycle ownership.
- Acceptance (measurable): Kill-at-write tests preserve last valid checkpoint; restored absent human follows room policy; storage error pauses; old jobs cannot affect new room; backup excludes private state/capabilities.
- Tests (first): `session/RecoveryTest.kt; storage/AtomicCheckpointTest.kt; storage/MigrationTest.kt; session/TakeoverRaceTest.kt; android/ActivityLifecycleTest.kt`. Intent: Inject process/write failure, low storage, unknown schemas, duplicate names, AI/rejoin races and foreground-service failure. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: SessionStore.kt, GameSessionService.kt, MainActivity.kt, GameController.kt, manifest backup rules; lifecycle-independent session owner.
- Dependencies: WI-003, WI-004
- Risks + mitigations: Durability acknowledgement can slow play; bounded asynchronous commit but never ack before promised durability; retain last valid generation.
- Rollback: Preserve old save before migration; suspend v2 matches; do not silently delete unknown or downgrade incompatible saves.

### WI-006: Complete legal generator and competent offline AI

- Priority / stage / estimate: P0/P1 / M0→M1 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-014, GDN-015, GDN-055, GDN-056, GDN-057, GDN-058, GDN-059, GDN-060, GDN-061, GDN-062, GDN-063.
- Goal: Separate complete legal actions, seat-private observation, bounded policy and room-governed hints.
- Acceptance (measurable): Small-hand oracle agrees with generator; bomb/wildcard fixtures pass; zero illegal decisions in gate corpus; hidden-state changes do not alter observation; timed fallback acts once.
- Tests (first): `ai/LegalActionOracleTest.kt; ai/PartnerPolicyTest.kt; ai/InformationBoundaryTest.kt; ai/DeadlineFallbackTest.kt; ai/PairedEvaluationTest.kt`. Intent: Exhaust subsets in small hands, test 27-card adversarial fixtures, partnered threat/endgame cases, seeds and swapped seat assignments. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Bot.kt, Rules.kt, GameController.kt, hints; new SeatObservation, LegalActionGenerator, Policy and evaluator.
- Dependencies: WI-002, WI-003, WI-004
- Risks + mitigations: Winning rate and partner experience differ; measure both. Imported weights require separate license/performance gate; baseline works without them.
- Rollback: Policy switch to tested heuristic fallback; keep legal generator and observation boundaries; never restore omniscient input.

### WI-007: Room UX, membership and seating

- Priority / stage / estimate: P1 / M1 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-001, GDN-002, GDN-003, GDN-005, GDN-007, GDN-009, GDN-010, GDN-011, GDN-012, GDN-013, GDN-016, GDN-017.
- Goal: Make room selection, approval, teams, readiness and rematch explicit.
- Acceptance (measurable): Two hosts distinguishable; duplicate names harmless; four seat combinations start; nickname persists; unready humans block start; rematch retains group.
- Tests (first): `room/AdmissionFlowTest.kt; room/SeatingReadyTest.kt; ui/LobbyFlowTest.kt; ui/RematchTest.kt`. Intent: Test wrong room, denial, full table, seat swap, replacement approval, rules change readiness reset and interrupted admission. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: MainMenuScreen.kt, LobbyScreen.kt, ResultScreen.kt, room descriptor/settings and member model.
- Dependencies: WI-004, WI-005, WI-006
- Risks + mitigations: Too much configuration harms quick start; default profile with readable summary and optional advanced settings.
- Rollback: Disable optional QR/customization; required explicit room/identity controls remain.

### WI-008: Native BLE transport and LAN fallback

- Priority / stage / estimate: P1 with P0 framing / M1 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-004, GDN-019, GDN-021.
- Goal: Implement GMS-independent BLE discovery/session transport and measured LAN fallback.
- Acceptance (measurable): Four players plus two watchers pass 60-minute Bluetooth-only test in both host directions on declared matrix; fragmentation/retry/backpressure gates pass; denial/radio-off actionable.
- Tests (first): `transport/BleFramingTest.kt; transport/BackpressureTest.kt; device/BluetoothOnlySessionTest; device/PermissionRecoveryTest`. Intent: First characterize roles, supported host capacity, MTU and throughput on actual phones. Then inject dropped chunks, slow observer, app background and radio toggles. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: New native BLE transport adapters, NsdHelper LAN adapter, permission/UI guidance, shared transport contract.
- Dependencies: WI-004, WI-005
- Risks + mitigations: Hardware/peripheral limitations may block promise. Treat as release gate; explicit LAN-only scope decision required if failed, not a silent substitute.
- Rollback: Disable BLE entry if broken; preserve session/LAN implementation; no BLE claim until gate repeats successfully.

### WI-009: iPhone client and cross-platform parity

- Priority / stage / estimate: P1 / M1 / L (relative effort, not a calendar promise).
- Ledger coverage: GDN-020.
- Goal: Audit available iOS source or implement native client above the same rule/wire contracts.
- Acceptance (measurable): iOS builds/tests on selected minimum and current targets; fixtures match Android; either platform hosts; privacy/recovery/observer semantics equivalent.
- Tests (first): `ios/RuleConformanceTests; ios/WireFixtureTests; ios/SessionLifecycleTests; device/MixedPlatformTableTest`. Intent: Consume golden rules and wire fixtures before UI/transport integration; test process interruption, revocation and wrong version. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: New iOS project, portable core bridge or native fixture-conformant core, Core Bluetooth/LAN adapters and table views.
- Dependencies: WI-001, WI-002, WI-003, WI-004
- Risks + mitigations: Documented upstream iOS compatibility is not source availability; verify license and code before reuse. Parallel client drift prevented by shared fixtures.
- Rollback: Do not advertise iOS if unavailable; suspend incompatible sessions before reverting client protocol.

### WI-010: Consented spectators

- Priority / stage / estimate: P1 with P0 permissions / M1 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-065, GDN-066, GDN-067, GDN-068, GDN-069, GDN-070, GDN-071, GDN-072.
- Goal: Add independent read-only observers and scoped private-follow grants.
- Acceptance (measurable): At least two observers; no fifth playing seat; approval/revoke/switch restrictions enforced in payload; crafted action rejected; slow observer cannot delay players.
- Tests (first): `observer/GrantTest.kt; observer/RevocationRaceTest.kt; observer/PrivateProjectionTest.kt; device/ObserverCapacityTest`. Intent: Test bot-seat host approval, human consent, revoke during queued send, replace/departure, reconnect and public-only view. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Member/capability protocol, per-peer projections, lobby/watch UI and result wording on both platforms.
- Dependencies: WI-004, WI-007, WI-008, WI-009
- Risks + mitigations: Previously observed information cannot be retracted; communicate actual revocation scope. Clear queued private frames and local private display.
- Rollback: Disable admission of observers and revoke grants; active players continue; never reinterpret observer as player.

### WI-011: Card interaction and action feedback

- Priority / stage / estimate: P1 / M1 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-050, GDN-073, GDN-074, GDN-075, GDN-076, GDN-077, GDN-078, GDN-079, GDN-082, GDN-102.
- Goal: Improve hand layout, tap/drag selection, sorting, declarations and pending/rejected actions.
- Acceptance (measurable): All 27 cards selectable on declared minimum display; selection p95 <100ms; preview matches payload; double tap applies once; rejection retains selection.
- Tests (first): `ui/CardSelectionTest.kt; ui/WildcardPreviewTest.kt; ui/PendingReceiptTest.kt; benchmark/SelectionBenchmark; device/SmallPhoneUsabilityTest`. Intent: Test duplicate cards, 10-card bomb, font scale, rotation, stale/rejected response and sort while selection active; human users perform representative plays. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: GameTableScreen.kt, CardView.kt, results and per-seat last-trick presentation; background interpretation cache.
- Dependencies: WI-002, WI-004, WI-006
- Risks + mitigations: Auto grouping can harm player agency; maintain manual groups and make suggestions reversible.
- Rollback: Keep a simple rank layout as fallback; pending receipt and legality consistency cannot be disabled.

### WI-012: Digital replay and practice extensions

- Priority / stage / estimate: P2 / M2 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-092, GDN-094, GDN-095, GDN-096.
- Goal: Add complete privacy-aware local history, replay, summaries and practice forks.
- Acceptance (measurable): Replay hashes match checkpoints; unknown historic events are not fabricated; practice cannot mutate original; export previews private data and requires relevant consent.
- Tests (first): `replay/ReplayParityTest.kt; replay/VisibilityTest.kt; replay/ForkIsolationTest.kt; storage/HistoryDeletionTest.kt`. Intent: Restore/truncate/import history, observer views, original-versus-practice state isolation and all-hand export consent. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Event store, replay view, local summary/index, practice runner and export/delete UI.
- Dependencies: WI-003, WI-005, WI-006, WI-010
- Risks + mitigations: Replay exposes hidden cards if projections are careless; default own-seat/public view; explicit post-game grants for others.
- Rollback: Disable new replay/practice entry; preserve typed event store and erase only on explicit user action.

### WI-013: Accessibility, effects and device-friendly preferences

- Priority / stage / estimate: P1 / M1 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-006, GDN-008, GDN-080, GDN-081, GDN-097, GDN-098, GDN-099, GDN-100.
- Goal: Make cards operable with assistive technology and audio/motion independently configurable.
- Acceptance (measurable): TalkBack/VoiceOver complete a deal; platform targets and 200% font scenarios pass; reduced motion removes flashes; effects follow accepted event IDs; wake flag released outside play.
- Tests (first): `ui/AccessibilitySemanticsTest.kt; ui/EventEffectTest.kt; device/ScreenReaderChecklist; device/FontContrastChecklist; device/BatteryThermalSession`. Intent: Include >40 log entries, reconnect sounds, muted play, grayscale teams, large text, settings while connected and menu inactivity. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: CardView.kt, GameTableScreen.kt, SettingsScreen.kt, SoundPlayer.kt, Theme.kt, MainActivity.kt and iOS equivalents.
- Dependencies: WI-007, WI-011
- Risks + mitigations: Custom gestures can conflict with screen readers; provide equivalent focusable actions and alternate large-hand layout.
- Rollback: Disable decorative effects; accessibility/essential text controls remain enabled.

### WI-014: Release evidence and truthful capability catalog

- Priority / stage / estimate: P0/P1 / M0→M1 / M (relative effort, not a calendar promise).
- Ledger coverage: GDN-046, GDN-064, GDN-101, GDN-106, GDN-107, GDN-108, GDN-110.
- Goal: Publish a tested rules/device/transport catalog, asset provenance, redacted diagnostics and signed release gates.
- Acceptance (measurable): Each M1 requirement has evidence; minified signed build upgrades/recovers correctly; source notices and model terms inventoried; docs match tested transports/profile omissions.
- Tests (first): `release/MinifiedBuildSmokeTest; release/UpgradeRestoreTest; release/DiagnosticsRedactionTest; release/CapabilityCatalogConsistencyTest`. Intent: Test signed test package, persisted-session upgrade, fault logs, no private data in default diagnostic export and no unjustified all-variants claim. These are proposed suite names; they do not exist upstream yet. For device/human criteria, write the test procedure/fixture first rather than pretending automation substitutes for human acceptance.
- Touched areas / responsibility: Build workflows, metadata, documentation, license inventory, diagnostics and evidence index.
- Dependencies: WI-001, WI-002, WI-005, WI-006, WI-008, WI-009, WI-010, WI-011, WI-013
- Risks + mitigations: Source-only testing can hide device failures; require hardware and human acceptance evidence before marking M1 achieved.
- Rollback: Pause distribution for integrity/privacy failures; retain recoverable saves and downgrade guidance; describe actual capability if a feature is withheld.

## Testing Procedures

Current reproducible core check: run `python3 verification/run_guandan_probes.py` from this plan directory on the current host. The script compiles unmodified upstream source; it is a baseline audit probe, not a complete future product suite. Recorded output lives in `verification/guandan-probe-results.txt`.

Target commands after WI-001 introduces the named modules/wrapper:

```sh
./gradlew :core:jvmTest :protocol:jvmTest
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleReleaseTest
./gradlew :app:connectedDebugAndroidTest
xcodebuild test -scheme Guandan -destination '<declared iOS test device or simulator>'
```

The `core` and `protocol` modules and iOS scheme are proposed names; these commands are not claimed runnable in the baseline. WI-001 must update exact commands to the chosen structure. Use real devices for BLE capacity, radio/offline conditions, OEM interruptions and screen-reader/human selection gates. Simulators and LAN tests do not prove Bluetooth-only performance.

Run focused fixtures on each relevant change. Run invariant/property and protocol faults when core/session changes. Run signed minified builds, migration fixtures and mixed-device 60-minute session before a release candidate. Repeat broader gates when new profile/transport/platform changes justify it; do not repeatedly run unrelated gates for copy-only edits.

## Plan → Verify Handoff

Evidence per work item: exact commit/build/profile/policy version; passing suite names and logs; fixture corpus/version; recorded device models/OS/radio settings; fault injection steps and expected/observed result; unresolved issues and reviewer signoff. Device/human rows additionally require observed completion, not a screenshot of a settings screen.

Required fixtures: all 24 finishing permutations; each combination including joker and A-low boundaries; one/two-wildcard declarations; 4–10-card bombs and flush ordering; single/double tribute with ties, return exceptions and resistance splits; A challenge permutations/interruptions; deterministic deck/event seeds; invalid/duplicate/wrong-turn commands; legacy/versioned/corrupt/unknown saves; two simultaneous rooms; duplicate nicknames; delayed writes; old-generation disconnect; missing/chunked/duplicated BLE frames; spectator approve/revoke/switch/departure; crash/timeout AI; representative small/large-text hands and >40-event audio history.

Gap closure: every audit ID A01–A32 that exists in the audit, plus A34/A35/A36, maps through ledger evidence to at least one WI. Missing numeric IDs are reserved, not unreviewed requirements. KEEP IDs identify foundations to preserve. No deferred physical-card task appears as an unowned dependency.

## Rollout Plan

1. M0: freeze source-backed profile specifications, fix audited behaviors, introduce core/protocol/storage and pass fixtures. Do not add new transport until information/state boundaries exist.
2. M1 internal: Android LAN and native baseline AI; validate UI, durability, room admission and privacy; then iOS parity and BLE hardware characterization. Internal sequencing is not permission to advertise partial scope as complete.
3. M1 release candidate: enable tribute profile, BLE and spectators together on supported matrix; run signed minified upgrade/recovery and 4+2 Bluetooth-only soak; collect human partner and accessibility acceptance.
4. M2: digital replay/practice, optional custom profiles/formats and stronger AI after licensed/benchmarked evidence. Each new profile has conformance review.

Kill-switch conditions: private-hand/capability disclosure, state divergence, lost acknowledged actions, repeated crash/stall, or transport violating advertised offline promise. Disable affected entry path, preserve saves, provide explicit suspension/recovery. Never switch active room rules or downgrade protocol under users. If BLE feasibility fails, require a deliberate scope decision before a LAN-only release; retain the unfulfilled BLE requirement in the ledger.

## Manual Test Checklist

- [ ] Fresh Android and iPhone start without an account or WAN; solo play with both radios off.
- [ ] Two nearby hosts are distinguishable; friends choose partners and accept profile before start.
- [ ] One, two, three and four humans complete a match with suitable offline bots.
- [ ] Four players plus two observers complete 60 minutes with Wi-Fi/mobile data off, either platform hosting.
- [ ] Tribute, return, resistance, A eligibility/failures and reporting follow the selected reviewed profile.
- [ ] Observer approval, revocation, bot-seat follow and departure/replacement respect private boundaries.
- [ ] Radio loss, lock/unlock, incoming interruption, host kill/restore and low-storage failure preserve defined state.
- [ ] Illegal/stale play gives issuer feedback; selection survives rejection; double tap cannot duplicate action.
- [ ] Human users select representative hands on small phones; TalkBack/VoiceOver and 200% text are usable.
- [ ] Settings work while connected; reduced motion/mute preserve essential information.
- [ ] Minified signed release upgrades/restores; diagnostics contain no hands, seeds or capabilities.
- [ ] Store/help claims match the actual tested device/profile/transport catalog.
