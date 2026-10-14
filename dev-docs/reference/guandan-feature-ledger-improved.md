# Guandan Mobile — Improved Feature Ledger

Date: 2026-10-13 (Asia/Shanghai). Baseline: lanticy/guandan at `d66c38a1ff4ee6691e5a3ce621fc170caad5b19e`.

This is a target-product ledger, not a claim that these features are implemented. It is derived from the complete tracked-source review and behavioral probes in `guandan-code-audit.md`. The scope is a group sitting together, playing on their phones, filling empty seats with AI, and allowing spectators to follow consenting players. Physical-card scanning, table cameras, earphone advice for physical play, and desktop assistance are deferred entirely. No dependencies in this ledger require that deferred work.

## Product promise and boundaries

Create an identifiable nearby table without an account or internet, arrange four seats, agree on rules, play with competent offline AI where needed, and let invited people watch a permitted viewpoint. Bluetooth-only mixed Android/iPhone play is a launch requirement to prove on a device matrix, not an existing capability or a guaranteed result from a framework name. Local Wi-Fi remains an explicit fallback. The initial acceptance target is four players plus two observers; higher observer counts require their own capacity evidence.

Public matchmaking, money/chip economies, global rankings, cloud accounts and voice chat are outside this product's initial scope. People already share the physical room. Non-shuffle play, exotic bomb/scoring variants and additional regional profiles enter the supported catalog only after source-backed specifications and conformance tests; they are not implicitly included by a custom-settings UI.

## Reading the ledger

- Baseline: Existing means source support, not physical-device validation. Partial means some mechanics exist with omissions. Documented means claimed without the relevant implementation in this clone. Missing/New means absent. Defective/Unsafe identifies a demonstrated behavior or a cited code hazard; consult the audit for evidence level.
- Priority: P0 protects correctness, privacy or state integrity; P1 is required for the intended first usable release; P2 is a later enhancement.
- Stage: M0 = core/spec/protocol foundations; M1 = first complete nearby mobile release; M2 = optional enhancements after M1 evidence.
- Work item: implementation owner/group and dependencies are in the companion full plan. All listed work is proposed.
- Acceptance: a testable target, not a measured current result. Device and human usability gates cannot be replaced by unit tests.

## Rule coverage policy

Use named, versioned rule profiles, not blanket labels such as "all mainland rules" or an unsupported "national standard" badge. Each profile records its source, unresolved choices, overrides and fixtures. Start with a compatibility profile reproducing the current game and a separately reviewed tribute-enabled profile. Compatibility preserves old behavior only in a clearly labeled legacy mode; it is not the recommended corrected default. Existing mainland event documents demonstrate differences in passing A, reporting and tribute policy, but do not exhaust every regional practice.

Reference examples: [China University of Petroleum event rules](https://www.cup.edu.cn/jiguan/docs/2024-03/a37a68b7f82c405f94a214c2becf7cc3.pdf), [Southeast University event rules](https://ddgh.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf). These support the need for distinct profiles; they do not authorize combining selected clauses into a purported official ruleset.

## Default room policies proposed for the first release

1. Four playing seats; two observers guaranteed only on tested hardware; no account and no required WAN.
2. Rules and assistance policy freeze at start; everyone readies after changes.
3. AI sees exactly the selected seat's permitted observation. Offline baseline always available; learned policy is optional pending license/performance gates.
4. Observers need seat consent to see a private hand; one private viewpoint per deal. Revocation prevents future access but cannot erase information already seen.
5. Short reconnect grace, then an explicitly chosen pause/substitute policy. Rejoin never relies on a nickname.
6. Host remains authoritative; session suspends if the host disappears. Automated host migration is deferred because distributing the complete hidden state creates additional privacy and consistency obligations.
7. Friendly undo is optional later and consent-based. Competitive rooms disable live hints and private-view spectators unless an explicit session policy permits them.

## Feature inventory

### Getting started and local identity

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-001 | Account-free use | Existing; KEEP-01 | Create or join without an account or internet | Fresh installation reaches an offline room without login or external requests | P1 / M1 / WI-007 |
| GDN-002 | Persistent local nickname | Partial; A35 | Save an editable nickname separate from seat credentials | Restart preserves nickname; changing it cannot claim another seat | P1 / M1 / WI-007 |
| GDN-003 | Visible play/watch choices | New; A23 | Choose play or watch before requesting admission | A watcher joins without consuming one of four playing seats | P1 / M1 / WI-007 |
| GDN-004 | Bluetooth permission guidance | New; A23 | Explain and request only platform-required nearby permissions when needed | Denied permission shows recovery instructions and a Wi-Fi alternative; permission is not required for solo play | P1 / M1 / WI-008 |
| GDN-005 | Connection-independent solo play | Partial; KEEP-01 | Start one human plus three local AI without starting discovery or a server | Airplane mode with Bluetooth and Wi-Fi disabled permits a complete match | P1 / M1 / WI-007 |
| GDN-006 | Interactive first-hand tutorial | New; A25 | Teach selection, passing, level cards, wildcards and partnerships through a short deal | Learner completes each required action; tutorial can be skipped and replayed | P2 / M2 / WI-013 |
| GDN-007 | Rules explanation at entry | Partial; A01 | Show the selected rule profile and a readable summary before joining | Every participant can see tribute, A condition, reporting and assistance policy before readying | P1 / M1 / WI-007 |
| GDN-008 | Local settings and data controls | Partial; A29 | Offer settings, session deletion, history deletion and identity reset separately | Deleting history does not erase preferences; resetting identity explains loss of rejoin access | P1 / M1 / WI-013 |

### Rooms, seats and the gathering

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-009 | Named room directory | Missing; A14 | List identifiable nearby rooms instead of connecting to the first service | Two simultaneous hosts are distinguishable and the chosen room is joined | P1 / M1 / WI-007 |
| GDN-010 | QR invitation | New; A14 | Optional QR invitation encodes room identity and admission material without hands | Scan selects the intended table; expired invitation cannot join another session | P2 / M2 / WI-007 |
| GDN-011 | Host admission | New; A15 | Host approves new players and watchers; genuine rejoin uses a credential | Unknown peer cannot read a private snapshot before approval | P1 / M1 / WI-007 |
| GDN-012 | Choose seat and partnership | Missing; A23 | Players arrange seats and teams in lobby with host confirmation | Friends can become partners regardless of discovery order | P1 / M1 / WI-007 |
| GDN-013 | Ready and start control | Partial; A01 | All human players accept the frozen room rules before dealing | Host cannot start while a human is unready; empty seats have explicit bot assignments | P1 / M1 / WI-007 |
| GDN-014 | One to four human players | Existing; KEEP-02 | Fill remaining seats with AI chosen in lobby | All human counts 1 through 4 complete a match using the same rules | P1 / M1 / WI-006 |
| GDN-015 | Per-seat AI settings | Missing; A08 | Select supported difficulty and pacing for each bot | Seat-specific changes are visible and permitted only at an agreed deal boundary | P1 / M1 / WI-006 |
| GDN-016 | Replacement player admission | Unsafe fallback; A15 | Host approves a substitute; takeover occurs once at a defined action boundary | Replacement invalidates old control capability and preserves cards and finishing state | P0 / M1 / WI-007 |
| GDN-017 | Rematch with same group | Missing; A22 | Keep table membership and offer another match after results | One rematch request retains agreed seats and profile without rediscovery | P1 / M1 / WI-007 |
| GDN-018 | Pause and end controls | Missing; A22 | Distinguish pause, leave seat, suspend table and permanently end table | Host receives consequence-specific confirmation; paused table accepts no game action | P1 / M1 / WI-005 |

### Nearby transport and protocol

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-019 | Bluetooth-only table | Missing; A23 | GMS-independent BLE connection for the initial supported device matrix | Four players and two watchers complete a 60-minute table with Wi-Fi and mobile data off | P1 / M1 / WI-008 |
| GDN-020 | Mixed iPhone and Android | Documented only for LAN; A31 | Ship interoperable clients and test either platform hosting | Android-host and iPhone-host configurations pass shared rule and wire fixtures | P1 / M1 / WI-009 |
| GDN-021 | Wi-Fi and hotspot fallback | Existing LAN only; KEEP-03 | Keep local Wi-Fi transport with explicit fallback instructions | Room works without WAN; failure of BLE offers Wi-Fi without claiming Bluetooth-only success | P1 / M1 / WI-008 |
| GDN-022 | BLE fragmentation and resynchronization | Missing; A17 | Frame payloads with sizes, revisions, reassembly bounds and retry rules | Truncated, duplicated and reordered chunks never produce a partial accepted game state | P0 / M0 / WI-004 |
| GDN-023 | Transport-independent session | Missing; A14 | Reuse one session protocol above BLE and LAN | Identical event fixtures give identical state over either transport | P0 / M0 / WI-004 |
| GDN-024 | Protocol version negotiation | Missing; A13 | Exchange compatible protocol ranges and required capabilities before seating | Unsupported versions get an actionable rejection before receiving hands | P0 / M0 / WI-004 |
| GDN-025 | Room and session identity | Missing; A14 | Bind every command and rejoin to a unique room and session epoch | A nearby second room or a restarted old match cannot accept commands from the original session | P0 / M0 / WI-004 |
| GDN-026 | Authenticated private transport | Missing; A15 | Authenticate admitted peers and protect private payloads on both transports | Unadmitted peers and an observer of transport traffic cannot obtain another seat's hand | P0 / M0 / WI-004 |
| GDN-027 | Input and connection bounds | Missing; A19 | Bound frame sizes, hello time, names, action rate, queues and spectator connections | Oversized frames and stalled peers disconnect without blocking a valid table | P0 / M0 / WI-004 |
| GDN-028 | Ordered delivery and action receipts | Missing; A17,A20 | Single send queue per peer; state revisions; idempotent command IDs; accepted/rejected receipts | No state regression under delayed writes; retried command applies once; rejection reaches the issuing client | P0 / M0 / WI-004 |

### Rule profiles and complete Guandan play

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-029 | Versioned rule profiles | Missing; A01 | Named immutable profiles with sources, version and explicit overrides | Every saved match and connected client references the same profile hash | P0 / M0 / WI-002 |
| GDN-030 | Two-deck card identity | Existing; KEEP-04 | Preserve unique physical-card IDs across duplicates | 108 unique IDs are conserved among hands and played cards; no ID is played twice | P0 / M0 / WI-003 |
| GDN-031 | Basic combinations and sequence boundaries | Existing; KEEP-04 | Validate all supported combinations including A-low sequences | Golden cases cover every type, joker exclusion, sequence lengths and level-card ranking | P0 / M0 / WI-002 |
| GDN-032 | Wildcard substitution and declaration | Partial; A05 | Show and validate declared type, ranks and suits for wildcard uses | Selected cards can represent all permitted interpretations; illegal substitutions and forged declarations reject | P0 / M0 / WI-002 |
| GDN-033 | Bomb hierarchy | Existing fixed; KEEP-04 | Make supported bomb comparisons explicit in each profile | Fixtures cover 4 through 10 card bombs, same-size level bombs, straight flushes and four jokers | P0 / M0 / WI-002 |
| GDN-034 | Single tribute | Missing; A01 | Transfer the prescribed eligible maximum card after single-down result | Wildcard exclusion and ties are handled exactly by the selected profile | P0 / M1 / WI-002 |
| GDN-035 | Double tribute and routing | Missing; A01 | Resolve both contributions, ties and recipient mapping | Differing and equal tribute ranks produce profile-correct recipients with no card loss | P0 / M1 / WI-002 |
| GDN-036 | Return tribute | Missing; A01 | Offer only profile-legal returns and define the all-high-card exception | Return under or at the limit and exception cases are validated by host and both clients | P0 / M1 / WI-002 |
| GDN-037 | Resistance to tribute | Missing; A01 | Express single/double-down joker conditions per documented profile | Two jokers in one hand and split between partners are distinct fixtures with explicit expected outcomes | P0 / M1 / WI-002 |
| GDN-038 | First and subsequent opening leads | Incorrect for tribute profiles; A02 | Resolve opening seat from first-deal and tribute/resistance policy | Subsequent deal never falls back to arbitrary random leader when profile prescribes one | P0 / M1 / WI-002 |
| GDN-039 | Partner lead inheritance | Existing; KEEP-04 | Retain partner borrowing lead and handle already-finished partner | Fixtures cover active partner, finished partner and all remaining pass combinations | P0 / M0 / WI-003 |
| GDN-040 | Level advancement | Existing fixed; A01 | Preserve separate team levels and profile-specific gains and caps | All finishing permutations settle according to profile; level cap and active level are explicit | P0 / M0 / WI-002 |
| GDN-041 | Passing A and attempt tracking | Partial defective/ambiguous; A03,A04 | Specify active-level ownership, eligible finishing positions and failure-count policy | At-A fixtures cover wins, losses, interruption and reset; unrelated active level cannot silently satisfy an A-only profile | P0 / M0 / WI-002 |
| GDN-042 | Card-count visibility and announcements | Partial fixed; A01 | Profile controls exact-count visibility, threshold and on-request reporting | Restricted counts are absent from payloads as well as the UI; threshold crossing emits one structured event | P0 / M1 / WI-002 |
| GDN-043 | End-of-deal timing | Partial; A36 | Profile chooses early double-up settlement or full finishing order | Both supported completion policies produce correct gains and distinguish inferred from actually played ranks | P1 / M1 / WI-003 |
| GDN-044 | Timed and fixed-deal sessions | Missing; A01 | Optional session formats separate from card rules; finish current deal at time limit | Clock expiration cannot terminate mid-action or start a new deal; tie policy is explicit | P2 / M2 / WI-002 |
| GDN-045 | Profile import and custom validation | Missing; A01 | Allow validated combinations of supported settings with a human-readable diff | Invalid combinations reject with a reason; imported unknown options never silently default | P2 / M2 / WI-002 |
| GDN-046 | Rules coverage catalog | Missing; A01 | Publish supported profiles and omissions rather than claiming all mainland variants | Every profile has provenance, conformance fixtures and a visible unsupported-settings list | P1 / M1 / WI-014 |

### Actions and authoritative game state

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-047 | Deterministic event reducer | Partial host engine; A30 | Rule core reduces versioned events with injected random source | Same seed, profile and event stream reproduce the same state hash | P0 / M0 / WI-003 |
| GDN-048 | Turn and phase enforcement | Existing basic; KEEP-04 | Validate play, pass, tribute, return, pause and next-deal permissions | Wrong phase, wrong seat and malformed actions leave state unchanged | P0 / M0 / WI-003 |
| GDN-049 | Seat-private snapshots | Existing basic; KEEP-05 | Build views from capabilities rather than arbitrary requested seat IDs | Each client receives exactly its authorized hand and profile-allowed public data | P0 / M0 / WI-004 |
| GDN-050 | Pending action feedback | Missing; A20 | Show pending state until host receipt; retain selection on rejection | Double tap cannot duplicate play; rejected action explains reason and retains cards | P1 / M1 / WI-011 |
| GDN-051 | Action-bound AI takeover | Partial; A11,A21 | Assign bot control at a confirmed turn boundary with explicit disconnect policy | Human rejoin racing AI produces one accepted action and one controller for the seat | P0 / M1 / WI-005 |
| GDN-052 | Validated state restore | Partial; A13 | Check cardinality, card IDs, turn, profile and event continuity before restore | Corrupt and inconsistent snapshots are quarantined instead of entering gameplay | P0 / M0 / WI-005 |
| GDN-053 | Per-session task ownership | Missing; A16,A18,A21 | Bind callbacks, sockets and delayed tasks to session epoch and connection generation | Callbacks from an old room cannot close, mutate or take a turn in the new room | P0 / M0 / WI-004 |
| GDN-054 | Optional friendly undo | Missing; A22 | Allow consent-based undo before a later accepted action; disabled in competitive mode | No unilateral rollback; published information is acknowledged and both views resynchronize | P2 / M2 / WI-003 |

### AI players and digital hints

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-055 | Complete legal-action generation | Defective; A06 | Enumerate all legal actions including wildcard and suit choices | Exhaustive small-hand oracle agrees with generator; targeted 27-card fixtures cover all types | P0 / M0 / WI-006 |
| GDN-056 | Winning bomb decisions | Defective; A07 | Consider bombs when responding and finish when strategically warranted | Bot plays its last-hand winning bomb; required bomb fixtures never produce illegal output | P0 / M0 / WI-006 |
| GDN-057 | Partnership awareness | Missing; A08 | Observe acting seat, partner, trick owner and allowed public counts/history | Scripted partner-leading cases avoid needless overtake unless a documented tactical benefit exists | P1 / M1 / WI-006 |
| GDN-058 | Information boundary for bots | Partial; A08 | Pass a seat-specific observation to AI; never pass complete host state | Changing hidden opponent cards while keeping observation constant cannot change deterministic policy input/output | P0 / M0 / WI-006 |
| GDN-059 | Offline inference | Existing heuristic; KEEP-02 | Bundle a competent lightweight policy with no required cloud service | Mixed tables complete offline; network tracing finds no inference request | P1 / M1 / WI-006 |
| GDN-060 | Difficulty levels | Missing; A08 | Use measured policies/search budgets rather than hidden-card access | Difficulty comparison uses common deals, both seat assignments and confidence intervals | P2 / M2 / WI-006 |
| GDN-061 | Bounded decision time and fallback | Missing; A27 | Run cancellable decisions off UI thread with a legal fallback | Timeout or policy crash does not stall table; fallback applies once within configured budget | P0 / M1 / WI-006 |
| GDN-062 | Digital hints respecting room policy | Partial weak; A09 | Show legal alternatives and disable live assistance when room policy requires | Hints are consistent with selected profile and unavailable in no-assistance rooms | P1 / M1 / WI-006 |
| GDN-063 | Grounded short explanations | Missing; A09 | Explain visible tactical factors using rule engine and policy evidence | Explanation names only known cards/events; no unsupported win-probability claims | P2 / M2 / WI-006 |
| GDN-064 | AI evaluation and asset provenance | Missing; A28 | Track model/rule versions, licenses and paired evaluation fixtures | Release AI has documented redistribution rights, zero illegal actions in the gate corpus and measured strength | P0 / M0 / WI-014 |

### Spectators following a player

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-065 | Independent observer role | Missing; A23 | Watcher is a member without a playing seat or game command capability | Adding a watcher does not change deal or bot assignment | P1 / M1 / WI-010 |
| GDN-066 | Follow a consenting seat | Missing; A23 | Request one player's permission to see their live hand | Approval reveals only that player's hand; denial leaves a public-only view | P1 / M1 / WI-010 |
| GDN-067 | Consent and visibility indicator | Missing; A15 | Player sees who is watching and can revoke permission | Revocation prevents all later private events reaching watcher and clears current private UI | P1 / M1 / WI-010 |
| GDN-068 | Controlled seat switching | Missing; A15 | Default no private-view switching until next deal; new consent required | Watcher cannot collect multiple current hands by cycling seats | P0 / M1 / WI-010 |
| GDN-069 | Public-only watching | Missing; KEEP-05 | Allow public table view without private-hand access | Public view contains no hand, private hint or unused wildcard declaration | P1 / M1 / WI-010 |
| GDN-070 | Read-only protocol enforcement | Missing; A23 | Host rejects actions, tribute choices and takeover requests from observer capability | Crafted observer command changes neither turn nor hand | P0 / M1 / WI-010 |
| GDN-071 | Watcher capacity and backpressure | Missing; A19 | Support at least two watchers initially; cap membership and drop slow watcher queues safely | Four players plus two watchers pass BLE gate; slow observer cannot delay player receipts | P1 / M1 / WI-010 |
| GDN-072 | Observer behavior after departure | Missing; A22 | If followed player leaves, revoke private access pending replacement consent | Replacing the player does not transfer private-view approval automatically | P1 / M1 / WI-010 |

### Mobile table and card handling

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-073 | Responsive 27-card hand | Partial; A24 | Readable hand on supported small phones, tablets and orientations | All cards selectable at 320dp width and 200% font scale using an explicit alternate layout | P1 / M1 / WI-011 |
| GDN-074 | Tap and drag selection | Tap only; A24 | Support precise tap, continuous drag and easy deselection | User can select a six-card sequence without unintended neighboring cards | P1 / M1 / WI-011 |
| GDN-075 | Rank and combination sorting | Rank only; A24 | Offer rank/suit ordering and suggested groups without destroying manual arrangement | Sort changes preserve card IDs and selection; manual groups survive accepted unrelated plays | P1 / M1 / WI-011 |
| GDN-076 | Wildcard interpretation preview | Partial type label; A05 | Show intended type and represented ranks/suits before play | Two valid interpretations are selectable; payload matches preview | P1 / M1 / WI-011 |
| GDN-077 | Trick and turn clarity | Existing basic; KEEP-06 | Show owner, passes, partner and next actor without relying on color alone | Users can identify whose play they must beat and whose turn follows in scenario tests | P1 / M1 / WI-011 |
| GDN-078 | Other seats' last actions | Partial log only; A30 | Show current trick's last play/pass per seat | A late reconnect reconstructs the same current trick display | P1 / M1 / WI-011 |
| GDN-079 | Profile-correct card-count display | Partial always exact; A01 | Render only authorized counts with threshold badges | Hidden count cannot be discovered through accessibility semantics or layout text | P0 / M1 / WI-011 |
| GDN-080 | Sound, vibration and reduced motion | Sound only; A26 | Independent cues for turn and accepted action with opt-in vibration and reduced effects | Muted play remains understandable; reduced-motion mode removes bomb flashes | P1 / M1 / WI-013 |
| GDN-081 | Settings during a table | Missing; A22 | Change sound, accessibility and visual preferences without leaving session | Opening settings and returning does not disconnect or advance a turn | P1 / M1 / WI-013 |
| GDN-082 | Readable settlement and continuation | Existing basic; KEEP-06 | Show finishing order, level changes and the rule explaining them | Result explains A failures and tribute next steps; spectators receive neutral result wording | P1 / M1 / WI-011 |

### Recovery, persistence and interruptions

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-083 | Atomic durable checkpoints | Unsafe direct overwrite; A12 | Commit checkpoints through atomic replace and retain last valid generation | Process kill at every write stage restores either current or previous valid checkpoint | P0 / M0 / WI-005 |
| GDN-084 | Versioned saved session | Missing; A13 | Store schema version, profile, session epoch and checkpoint revision | Unsupported future schema is preserved with explanation; supported old schema migrates explicitly | P0 / M0 / WI-005 |
| GDN-085 | Reconnect to exact room and seat | Partial; A14,A15 | Rejoin using session credential and epoch; no name-based takeover | Duplicate names, nearby rooms and changed host port do not change seat ownership | P0 / M1 / WI-005 |
| GDN-086 | Consistent restore policy | Defective/incomplete; A11 | Pause for recovery grace then apply agreed substitution policy | Restored disconnected human does not stall indefinitely; resume and bot takeover are explicit | P0 / M1 / WI-005 |
| GDN-087 | Grace before bot substitution | Missing; A11 | Short room-configured reconnect grace with visible countdown | Transient interruption does not cause an immediate irreversible bot play | P1 / M1 / WI-005 |
| GDN-088 | Connection liveness and retry UX | Partial read-loop only; A10 | Heartbeat, explicit connection state and retry without duplicated attempts | Blackholed peer is detected within configured limit; controls disable while state is stale | P1 / M1 / WI-005 |
| GDN-089 | Session ownership beyond Activity | Partial empty service; A34 | Session manager owns engine and transport independently of screens | Activity recreation preserves table; service/session shutdown cancels all associated work | P0 / M0 / WI-005 |
| GDN-090 | Suspend, retention and delete | Partial 12-hour expiry; A22 | Make suspended-session retention clear and allow explicit deletion | Recoverable table isn't deleted by accidental exit; expiry is stated before choosing suspension | P1 / M1 / WI-005 |

### History and learning within the digital game

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-091 | Structured local event history | Missing text log only; A30 | Record typed events with deal/turn/revision and visibility classification | Every accepted action can be replayed without parsing translated text | P0 / M0 / WI-003 |
| GDN-092 | Complete hand-by-hand replay | Missing; A30 | Replay complete deals locally with observer permissions respected | Replayed state hashes equal recorded checkpoints; viewer cannot access unauthorized live hands | P2 / M2 / WI-012 |
| GDN-093 | Resume history continuity | Missing; A30 | Keep committed history when host process restarts | Restore loses no committed action and never duplicates a previously acknowledged action | P1 / M1 / WI-005 |
| GDN-094 | Personal match summary | Missing; A30 | Track local sessions and explain results without global ranking | Stats label human/AI composition and rule profile; bot games are not mixed into unsupported competitive claims | P2 / M2 / WI-012 |
| GDN-095 | Retry a digital position | Missing; A09 | Fork a completed digital position for practice against AI | Practice branch cannot mutate the original game or expose private live data | P2 / M2 / WI-012 |
| GDN-096 | Export and deletion controls | Missing; A29 | Export consented replay and erase selected local history | Export previews included private information; deletion removes replay indexes and data together | P2 / M2 / WI-012 |

### Accessibility and device behavior

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-097 | Screen-reader card semantics | Missing explicit semantics; A25 | Expose rank, suit, wildcard and selected state with logical focus order | TalkBack and VoiceOver users can select a legal play and pass without sighted help | P1 / M1 / WI-013 |
| GDN-098 | Touch targets and font scaling | Partial small icons; A25 | Meet platform touch-target guidance and provide large-text layout | Exit/settings controls meet 48dp Android and 44pt iOS targets; no essential text clips at 200% text size | P1 / M1 / WI-013 |
| GDN-099 | Contrast and non-color cues | Partial themes; A25 | Validate contrast and distinguish suits, teams, selection and turns by shape/text | Supported themes pass specified text contrast; grayscale scenario remains playable | P1 / M1 / WI-013 |
| GDN-100 | Battery-aware wake behavior | Always awake; A27 | Keep awake during active participation only with an optional override | Menu and completed table release wake flag; 60-minute thermal test records battery and temperature | P1 / M1 / WI-013 |
| GDN-101 | OEM interruption matrix | Unverified locally; A34 | Test supported Xiaomi, OPPO/vivo, Huawei-compatible Android and iPhone configurations | Background, lock/unlock, radio loss and process-kill scenarios have device-specific recorded results | P1 / M1 / WI-014 |
| GDN-102 | UI-thread latency budget | Unbounded rule work; A27 | Move expensive interpretation, hint and sound preparation off UI thread | p95 selection response below 100ms on declared minimum device; no input-blocking AI work | P0 / M1 / WI-011 |

### Engineering, distribution and release quality

| ID | Feature | Baseline / evidence | Target behavior | Acceptance | Priority / stage / work item |
|---|---|---|---|---|---|
| GDN-103 | Pinned build tooling | Incomplete wrapper; A28 | Complete Gradle wrapper, version pinning and reproducible setup | Fresh supported machine builds without relying on globally installed Gradle | P0 / M0 / WI-001 |
| GDN-104 | Automated core and property tests | Missing; A28 | Add rule, state, generator and restore suites | All audited reproducers pass as regression tests after fixes; illegal action corpus produces zero mutations | P0 / M0 / WI-001 |
| GDN-105 | Protocol and mixed-device tests | Missing; A17,A31 | Maintain language-neutral wire fixtures and fault-injection integration tests | Android/iOS decode same fixtures; fragmentation, stale epoch and duplicate commands are covered | P0 / M0 / WI-001 |
| GDN-106 | Release build and install gates | Build workflows only; A28 | Test debug and minified release on real phones with signatures and upgrade paths | Signed test release passes session restore and mixed-device play after upgrade | P1 / M1 / WI-014 |
| GDN-107 | GPL and third-party asset inventory | GPL present; KEEP-07 | Maintain source notices and verify actual dependency/model distribution terms | Release inventory maps every shipped dependency and AI asset to terms and source/version | P0 / M0 / WI-014 |
| GDN-108 | Local diagnostics | Partial logs; A20 | Opt-in redacted diagnostics with bounded retention and no hidden-hand defaults | Export includes protocol/state revisions and errors but excludes hands, admission secrets and permanent identities | P1 / M1 / WI-014 |
| GDN-109 | Backup privacy policy | Platform backup enabled; A29 | Exclude private hands and rejoin capabilities from platform backup; document preferences policy | Android extraction rules and iOS storage settings match policy; restore does not clone active seat credentials | P0 / M0 / WI-005 |
| GDN-110 | Evidence-based scope claims | Overbroad wildcard wording; A32 | Describe exact tested transports, rules and AI strength | Store descriptions and help match shipped capability matrix; no all-variants or omniscient-AI claim | P1 / M1 / WI-014 |

## Release exit criteria

M0: executable rules specification, complete legal generator, deterministic core, authenticated revisioned protocol, atomic versioned persistence and regression tests. M1: all M1 rows pass, Android/iPhone mixed hosting works, Bluetooth-only 4+2 capacity is proven, rule-profile conformance is reviewed, spectators obey capability boundaries, and human card-selection/accessibility tests pass. If Bluetooth gate fails, the product may release explicitly as LAN-only only after a deliberate scope decision; it cannot be marketed as fulfilling the requested Bluetooth experience. M2 rows are not prerequisites for M1.

Suggested performance targets (to validate, not current benchmarks): p95 touch-to-selection <100ms; p95 action receipt <500ms on LAN and <1s on BLE at 4+2 load; local baseline AI decision <=1s with a bounded fallback; a 60-minute mixed table with no crash, unauthorized private-state access or state divergence. Record results on named minimum and representative midrange devices. Connection recovery within 10s after connectivity returns is a goal, conditional on host availability and granted platform permissions.

## Decisions still requiring evidence

Choose the first fully specified tribute profile; verify how active-level ownership affects passing A; define failed-attempt counting exactly; select minimum iOS version and Android hardware baseline; prove BLE central/peripheral and observer capacity on mainland-market phones; acquire or implement the iOS client; measure acceptable baseline AI strength with human partners. Defaults are proposals and do not pretend these questions have been settled.
