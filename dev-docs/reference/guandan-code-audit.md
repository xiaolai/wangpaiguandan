# Guandan mobile: comprehensive baseline code audit

Date: 2026-10-13, Asia/Shanghai. Repository: `a local clone`. Upstream: <https://codeberg.org/lanticy/guandan>. Commit: `d66c38a1ff4ee6691e5a3ce621fc170caad5b19e`. App version: 1.2.2, Android version code 7.

## Outcome and scope

The repository is a small Android/LAN Guandan application with useful separation between card rules, authoritative game state, transport and Compose views. It is suitable as a prototype and source reference. Reaching the requested nearby mobile experience requires a complete rule specification, stronger AI, actual Bluetooth transport, an iOS implementation, spectator permissions and reliable session handling. Adding screens alone will not address the principal gaps.

This review covers all tracked Kotlin application source, manifest, resources as configuration, Gradle configuration, protocol/README, release metadata and both build workflows. It traces launch → room discovery/admission → authoritative action → snapshot → UI → checkpoint → disconnect/restore. Binary icons and store screenshots were inventoried, not used as evidence of current runtime behavior. The repository has no AGENTS.md/CLAUDE.md, no tracked test suites and no iOS implementation. An earlier investigation's conclusions were rechecked rather than treated as proof.

Physical-card scanning, physical-table tracking, desktop assistance and earphone advice for physical play are deferred. The improved ledger and implementation plan contain no work that depends on them. Digital hints and digital replay are separate, optional capabilities of this mobile game.

Evidence labels:

- **Executed:** compiled original Kotlin core and observed a behavior in probes.
- **Source:** exact implementation or missing capability inspected; not a physical-device reproduction.
- **Risk:** failure possible from a code path/concurrency model; needs an integration/device reproducer before claiming it occurred in use.
- **Specification gap:** current behavior is explicit but correct target depends on a chosen rules profile. Do not label every regional difference a universal bug.

Severity: P0 = integrity/privacy/core correctness; P1 = intended release reliability or essential functionality; P2 = enhancement or polish. Priority does not imply likelihood has been measured.

## Architecture and behavior inventory

| Area | Baseline owning source | Assessment |
|---|---|---|
| Launch/navigation | `MainActivity.kt:39–100` | Single Activity and ViewModel; route selected by role and snapshot phase; keep-screen-on set for whole Activity |
| Identity | `net/GameController.kt:54–61`; `ui/MainMenuScreen.kt:38–126` | Persistent installation UUID; nickname defaults to device model and is not persisted |
| Lobby/seats | `net/GameController.kt:213–249,370–414`; `ui/LobbyScreen.kt` | Sequential first-free seats, fixed opposite teams, implicit AI fill, no readiness/approval |
| Discovery | `net/NsdHelper.kt:26–105` | Fixed service name; resolves a discovered service and connects; no room directory or session ID |
| Protocol | `net/Messages.kt`; `PROTOCOL.md` | Newline JSON over plain TCP; four seat-based peers; no version handshake/action receipt/state revision |
| Rules | `game/Rules.kt`; `model/Card.kt`; `model/Combo.kt` | Common combinations and bomb order; exhaustive wildcard substitution over 52 card identities; strongest interpretation chosen |
| State | `game/GameEngine.kt` | Fixed profile; random leader each deal; three finishers needed to settle; separate team levels; no tribute phases |
| AI/hints | `game/Bot.kt`; `ui/GameTableScreen.kt:156–159` | Heuristic candidate selection; no partnership observation; same function used for hint |
| Session execution | `net/GameController.kt:268–325` | Serialized engine command consumer; transport callbacks and sends outside that consumer; bot timers have no session/turn generation |
| Checkpoint/recovery | `net/SessionStore.kt`; `game/GameEngine.kt:381–405` | Direct JSON overwrite; expiry after 12 hours; no schema/profile version; disconnected restored humans not automatically bots |
| Background behavior | `net/GameSessionService.kt` | Foreground service raises process importance but does not own engine/transport; `START_NOT_STICKY` |
| Table UI | `ui/GameTableScreen.kt`; `ui/CardView.kt` | Tap-selection, horizontal rank columns, type-only preview, small exit/log icons, immediate selection clearing |
| Results/preferences | `ui/ResultScreen.kt`; `ui/SettingsScreen.kt`; `ui/Theme.kt` | Basic results, five saved themes, saved sound setting; settings reachable from menu |
| Audio | `audio/SoundPlayer.kt`; `ui/GameTableScreen.kt:108–134` | Synthesized sounds cached lazily; presentation effects keyed to UI state rather than accepted event IDs |
| Delivery | `app/build.gradle.kts`; `.forgejo/workflows/android.yml`; `.github/workflows/android.yml` | Debug/release/releaseTest variants; minification; CI builds; no automated functional tests; incomplete wrapper |

All paths above are relative to `app/src/main/java/page/codeberg/lanticy/guandan/` unless otherwise stated. Full source references can be resolved against the pinned upstream commit; current branch changes must not be assumed equivalent to this audit.

## Foundations to preserve

| Evidence ID | Capability | Why retain it |
|---|---|---|
| KEEP-01 | Local account-free app | Matches a gathering; no required server, analytics SDK or cloud inference in baseline dependencies |
| KEEP-02 | Missing-seat bots | One to four human participants use the same engine |
| KEEP-03 | mDNS + TCP LAN | Simple cross-platform protocol foundation; LAN does not require WAN |
| KEEP-04 | Unique card IDs and authoritative legality | Rejects unowned/duplicated IDs, wrong-turn play and invalid follows; common card combinations work |
| KEEP-05 | Seat-filtered snapshots | Other hands are not sent to ordinary clients; host retains full state |
| KEEP-06 | Responsive basic table/results | Existing rank columns, owner/turn display and results reduce initial UI work |
| KEEP-07 | GPL source and small dependency surface | Clear baseline license and no downloaded AI asset to disentangle yet; modified distribution still requires an appropriate source/notice process |

## Findings

### Rules and AI

| ID | Priority / evidence | Finding, trigger and implication | Source | Resolution / verification |
|---|---|---|---|---|
| A01 | P0 / Source, specification gap | No rule-profile object, tribute/return/resistance phases or tournament formats. Exact counts always exposed; gains 3/2/1 and A first+second are hardcoded. Cannot claim all mainland rules. | `GameEngine.kt:103–126,244–288,320–334`; `Messages.kt:9–12`; `SettingsScreen.kt` | Versioned profiles with provenance and conformance fixtures; check data projection as well as display. WI-002 |
| A02 | P0 / Source | Every new deal chooses a random leader, including later deals. Tribute and no-tribute profiles with prescribed leaders cannot be reproduced. | `GameEngine.kt:103–126` | Opening-lead resolver taking previous result and tribute/resistance outcome. WI-002 |
| A03 | P0 / Executed, specification gap | A first+third result resets failure count; opponent win also resets it. Only A-team first+fourth results increment. This is not a general three-failed-attempt policy. | `GameEngine.kt:262–287` | Specify cumulative/consecutive attempts and active team, then exhaust result/interruption fixtures. WI-002 |
| A04 | P0 / Executed, specification gap | Team stored at A can finish first+second and win the match during an active level-2 deal. Settlement checks team level, not active deal level. | `GameEngine.kt:107,262–266` | Decide A challenge ownership explicitly; corrected profile must check its defined qualifying deal, not infer from stored team level alone. WI-002 |
| A05 | P1 / Source | Wildcard interpretations collapse by type/rank/size and strongest is selected automatically. Concrete represented ranks/suits are not retained; user cannot declare another valid interpretation. | `Rules.kt:107–168`; `Messages.kt:15–18`; `GameTableScreen.kt:423–444` | Declared combination plus substitution mapping; validate host-side; render identical preview. WI-002/WI-011 |
| A06 | P0 / Executed | Bot misses winning wildcard pair: level 2, hand heart-2 plus seven, facing pair six → PASS despite legal pair seven. Rank-based generator does not add needed wildcard selections or every suit choice. | `Bot.kt:45–94` | Complete generator shared by AI and hint; small-hand exhaustive oracle and full-hand fixtures. WI-006 |
| A07 | P0 / Executed | Bot ignores all responding bombs. Four sevens alone facing ace → PASS despite bomb ending hand. | `Bot.kt:31–37` | Consider legal bombs; ending-hand and threat-control fixtures. WI-006 |
| A08 | P1 / Source | Policy only receives hand/current combo/level. Cannot identify partner, trick owner, public counts, history or strategic finish order. | `Bot.kt:11`; `GameController.kt:298` | Seat-private observation and partnership-aware policy. WI-006 |
| A09 | P1 / Source | Hints call the same weak bot; no alternatives, explanation or assistance policy. | `GameTableScreen.kt:156–159` | Legal alternatives and explicit room assistance; optional grounded explanation after competent policy exists. WI-006 |
| A36 | P2 / Executed, specification gap | First two finishers being partners does not settle; opponents continue until third finisher. Usually unnecessary for gain, but whether to stop early depends on result-history policy. | `GameEngine.kt:199–202,228–244` | Explicit early-double-up/full-ranking setting; label inferred rankings when stopping early. WI-003 |

### Connections, identity and command processing

| ID | Priority / evidence | Finding, trigger and implication | Source | Resolution / verification |
|---|---|---|---|---|
| A10 | P1 / Risk | Detection depends on blocking read failure. Radio blackhole can leave a stale-looking connected table; no heartbeat or read deadline. | `GameController.kt:184–205,428–455` | Bounded liveness checks and visible state; network fault injection. WI-005 |
| A14 | P0 / Source | Rooms lack unique session identity; fixed discovery name and automatic first-service connection also used for reconnect. Multiple tables can be confused. | `NsdHelper.kt:26–32,45–76,102–104`; `GameController.kt:129–132,479–487` | Identifiable room list; room ID/epoch; exact rejoin. WI-004/WI-007 |
| A15 | P0 / Source | Rejoin falls back from self-declared playerId to same name or any disconnected seat. An unrelated participant can take the seat and receive its hand. No host approval or protected private transport. Installation UUID is an identifier, not authentication. | `GameController.kt:213–242,384–386`; plain `Socket` transport | Session capability and authenticated admission; host-approved replacement. WI-004/WI-007/WI-010 |
| A16 | P0 / Risk | Old connection's `finally` cleans up by seat index, using current global arrays. Reset/reuse can allow an old task to close a new connection or convert its seat to bot. | `GameController.kt:142–175,204–205,251–260` | Connection generation/session ownership; disconnect only matching socket generation. WI-004 |
| A17 | P0 / Risk | Independent coroutine writes share a lock, preventing byte interleaving but not preserving logical message order. No revisions or command IDs to reject stale snapshot/action. | `GameController.kt:531–553`; `Messages.kt` | Ordered per-peer writer queue; monotonic revisions and idempotent receipts; adversarial scheduling tests. WI-004 |
| A18 | P1 / Risk | Check for `hostSocket != null` precedes launching asynchronous connect. Retry/discovery callbacks can overlap; old read loop can clear new host connection. | `GameController.kt:428–455,457–488` | Single connect state machine with generation identity and cancellation. WI-004/WI-005 |
| A19 | P0 / Source | Unbounded command channel, line reader, unassigned connections and nickname input; no hello deadline/frame-size/rate limits. Nearby misbehaving peer can consume resources. | `GameController.kt:104,177–199,214`; `MainMenuScreen.kt:82–87` | Bounded queues, framed reads, admission timeouts and rate limits; fuzz/stall tests. WI-004 |
| A20 | P1 / Source | Invalid actions produce an error only for host seat 0; client receives no rejected-action receipt. UI clears selected cards immediately on send. | `GameController.kt:285–293`; `GameTableScreen.kt:164–169` | Action receipt/error for issuer; pending selection until acceptance. WI-004/WI-011 |
| A21 | P0 / Risk | Each afterChange schedules another bot tick; tick includes only seat, not turn revision. Delayed task accesses whichever `commands` field exists when delay ends. Old room/turn tasks can act in a new one. | `GameController.kt:295–323`; `EngineCmd.BotTick` | One cancellable AI task per turn bound to session epoch and turn revision. WI-004/WI-006 |
| A23 | P1 / Source | No Bluetooth code/permissions, observer role/capability or spectator admission. Exactly four seat arrays, player actions only. | `AndroidManifest.xml`; `Messages.kt`; `GameController.kt:73–78,213–235` | Prove BLE mixed-device transport; add independent read-only membership and consent. WI-008/WI-010 |
| A31 | P1 / Source | iOS protocol interoperability is documented, but this clone has no Swift/project files, iOS client link or reusable iOS rules implementation. | `README.md`; `PROTOCOL.md`; full tracked-file inventory | Acquire/inspect separate client or implement one, with cross-platform fixtures. WI-009 |

### Recovery and lifecycle

| ID | Priority / evidence | Finding, trigger and implication | Source | Resolution / verification |
|---|---|---|---|---|
| A11 | P0 / Executed + Source | Ordinary disconnect converts seat to bot immediately. Restore only marks human disconnected, leaves isBot=false, so absent human can stall indefinitely. No grace or explicit table policy. | `GameEngine.kt:338–356,400`; `GameController.kt:316–324,343–367` | Unified reconnect/grace/substitution transition, including restore. WI-005 |
| A12 | P0 / Risk | Direct overwrite can truncate the only checkpoint during process death. Parse failure then deletes it. Save failure is logged, but clients may already have received a newer snapshot. | `SessionStore.kt:35–62`; `GameController.kt:316–318` | Atomic versioned checkpoint; defined receipt-after-durability order; retain last valid save. WI-005 |
| A13 | P0 / Source | No save schema version, profile ID or state consistency validation; no protocol version negotiation. JSON defaults cannot replace migrations or card/turn validation. | `SessionStore.kt:12–17,22,44–62`; `GameEngine.kt:381–405`; `Messages.kt` | Versioned envelopes, validated migration/quarantine and incompatible-client handling. WI-004/WI-005 |
| A22 | P1 / Source | Exit icon immediately clears saved session and resets host; no consequence-specific confirmation, pause, suspend or rematch. In-game settings inaccessible without returning to menu. | `GameTableScreen.kt:250–252`; `GameController.kt:136–140`; `ResultScreen.kt:108–120`; `MainMenuScreen.kt` | Distinct leave/suspend/end; host confirmation; in-table preferences and rematch. WI-005/WI-007/WI-013 |
| A29 | P0 / Source | Platform backup enabled with no exclusion policy while private hands and persistent identity are stored locally. This does not prove upload occurred, but contradicts an unconditional claim that state never leaves the room. | `AndroidManifest.xml:20`; `SessionStore.kt:24`; `GameController.kt:54–61` | Explicit backup/extraction rules for private state and rejoin capabilities; clarify preference backup. WI-005 |
| A34 | P1 / Source | Foreground service is a priority shell; ViewModel still owns engine/sockets/jobs. Starting service silently fails and there is no session-service lifecycle owner. Host migration absent. | `GameSessionService.kt:34–40,86–98`; `GameController.kt:73–82,575–579` | Lifecycle-independent session manager, diagnostics, supported OEM testing. Defer automatic host migration unless hidden-state design is solved. WI-005/WI-014 |
| A35 | P2 / Source | UUID persists but nickname does not. Name falls back to device model; arbitrary length accepted. | `GameController.kt:31,54–61,586–587`; `MainMenuScreen.kt:82–87` | Bound and persist nickname; keep identity separate from admission. WI-007 |

### Presentation, performance and delivery

| ID | Priority / evidence | Finding, trigger and implication | Source | Resolution / verification |
|---|---|---|---|---|
| A24 | P1 / Source | Tap-only rank stacks with horizontal scrolling; no drag/group layout/manual arrangement. Small central area forces shrinking; readability not device-tested here. | `GameTableScreen.kt:353–405,448–460`; `CardView.kt` | Touch/drag prototype, group-preserving arrangements and small-phone/human tests. WI-011 |
| A25 | P1 / Source | Many custom clickable surfaces/cards lack deliberate semantics; exit/log icons are 28/26dp. Text sizing and layout need screen-reader/font-scale validation. | `GameTableScreen.kt:250–269,390–402`; `CardView.kt`; `SettingsScreen.kt` | Platform touch targets, logical accessibility tree, non-color cues, large-text alternatives. WI-013 |
| A26 | P2 / Source | PASS audio effect keyed to snapshot log size stops retriggering after log reaches 40 entries. No reduced-motion mode; flash and sound derive from snapshot changes and can replay on reconnect. | `GameTableScreen.kt:108–134,175–186`; `GameEngine.kt:332` | Accepted-event IDs for effects, opt-in vibration, reduced motion and reconnect suppression. WI-013 |
| A27 | P1 / Source, performance risk | UI composition interprets selections; hint runs synchronously; two wildcards enumerate 52² substitutions. Audio first-use renders synchronously. Activity always keeps screen awake. No measured lower-end latency budget. | `Rules.kt:99–121`; `GameTableScreen.kt:156–159,423–430`; `SoundPlayer.kt:59–61`; `MainActivity.kt:43–44` | Cancellable background calculations/cache, bounded AI budget, prewarm sound, active-table wake policy; named-device timing. WI-006/WI-011/WI-013 |
| A28 | P0 / Source | No unit/instrumented tests or test dependencies; CI runs builds only. Wrapper properties exist without gradlew/wrapper JAR. Reproducibility and semantics aren't gated. | tracked inventory; `app/build.gradle.kts`; workflow build steps | Complete pinned tooling, core/property/protocol/recovery tests and signed release-device gates. WI-001/WI-014 |
| A30 | P1 / Source | Human-readable log is truncated in snapshots, cleared each deal, and not a structured replay stream. Events cannot reliably reconstruct accepted plays, passes, wildcard intent or permissions. | `GameEngine.kt:124–126,146–147,188–189,332`; `Messages.kt:58–74` | Versioned typed event history and privacy-aware projection; local replay later. WI-003/WI-012 |
| A32 | P2 / Source | Documentation calls wildcards usable as any card, but code cannot substitute jokers. README refers to build instructions not present. iOS support not locally verifiable. | `README.md`; `fastlane/metadata/android/en-US/full_description.txt`; `Rules.kt:99–103`; `.github/workflows/android.yml` | Accurate capability/rules docs linked to actual tests and build setup. WI-014 |

## Verification performed

The probe harness compiles the original `Card.kt`, `Combo.kt`, `Rules.kt`, `Bot.kt`, `GameEngine.kt` and `Messages.kt` using cached Kotlin 2.0.21 libraries and JDK 21, without modifying upstream code. Serialization annotations compile, but this harness does not exercise generated JSON serializers, Android framework code, UI or live transport.

20 behavioral probes passed. They include correct wildcard-pair recognition; missed wildcard pair by bot; legal winning bomb and bot refusal; A first+third rejection and counter reset; opponent-win counter reset; delayed double-up settlement; bomb hierarchy; unowned/duplicate-card rejection; out-of-turn/pass-as-leader rejection; non-mutating illegal follow; A-low straight; joker substitution exclusion; partner lead inheritance; off-level A settlement; restored disconnected human without bot control; and simulation invariants.

Twelve randomly dealt all-bot hands completed in 1,304 accepted actions in the recorded run. Every step checked unique cards, no replayed card IDs, 108-card conservation, authorized own-hand snapshot and valid final ordering. These random deals are a smoke/property sample, not comprehensive strategy evaluation, rule-profile conformance or a seeded repeatable benchmark.

Artifacts: `verification/GuandanProbe.kt`, `verification/run_guandan_probes.py`, `verification/guandan-probe-results.txt`. Run `python3 verification/run_guandan_probes.py` from this output directory on the current host. The script expects the local checkout and cached Kotlin libraries at the paths it specifies. Future automated tests should package dependencies and fixture seeds explicitly.

Android build/lint: attempted `assembleDebug lintDebug` using the available Gradle 8.14.3 and JDK 21; this differs from the repository's intended Gradle 8.9. The offline attempt failed at dependency resolution for Android Gradle Plugin 8.7.1. The online retry resolved far enough to request Android SDK Build-Tools 34, then made no further progress for approximately 12 minutes and was stopped. Its output is recorded in `verification/guandan-build-online-results.txt`. No successful APK build or clean lint result was obtained. The improved plan includes a reproducible pinned build gate rather than treating local cache absence as a source defect.

Not verified: Bluetooth (absent), iPhone client (absent), Android/iPhone live interoperability, actual OEM kill/rejoin behavior, screen-reader usability, physical-device frame/thermal measurements, adversarial socket races or AI strength against humans.

## Rules evidence and review boundary

Published mainland event rules establish concrete variation; they do not supply an exhaustive national inventory. [China University of Petroleum event rules](https://www.cup.edu.cn/jiguan/docs/2024-03/a37a68b7f82c405f94a214c2becf7cc3.pdf) permit passing A with first plus a partner who is not last, specify tribute and opening leads, and reporting at ten cards. [Southeast University event rules](https://ddgh.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf) specify their own reporting and tribute details. Both are event specifications, not permission to combine rules into an unsupported official profile. The target catalog must identify exact sources and unresolved choices.

Cross-platform BLE is a feasibility gate. [Android BLE documentation](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview) describes roles, service discovery and small-data transport; [Apple Core Bluetooth](https://developer.apple.com/documentation/corebluetooth) is the corresponding native framework reference. Their existence is not evidence that four players plus observers work reliably on every phone. Test without Google Play services and without Wi-Fi/WAN before claiming the requested nearby behavior.

## Recommendation

Preserve the product's lightweight local character and useful code boundaries, while replacing the fixed-rule and seat/socket coupling with a deterministic rule core, capability-based session membership, an ordered versioned protocol and atomic persistence. Make legal-move completeness and partnership-aware offline AI prerequisites for quality hints. Build Bluetooth and consented spectators only on top of those boundaries. Ship an explicit supported-rule and device matrix, then expand with evidence.

The companion ledger contains 110 feature records, each mapped to a work item. The implementation plan defines ordering, acceptance, tests, migration and release evidence. None of these documents marks proposed work as complete.

## Independent document check

`jev docket secret-scan` on the audit, ledger and plan returned credential **0.03**, private_infra **0.04**, personal_data **0.12**. These are calibrated estimates, not verdicts. The personal-data estimate is inconclusive under the user's thresholds; direct review confirms the documents contain the requested local checkout path, verification-runtime paths and participant/decision-owner names. No claim that the documents are sanitized for public publication is made. Source review and behavioral tests remain the evidence for the code findings.
