# `:session` — session protocol (WI-05), persistence and recovery (WI-06), AI seats and hints (WI-07), lobby (WI-09), diagnostics (WI-12)

Transport-independent session for one host and its members (players and observers). Plain Kotlin/JVM; depends on `:core` only. No Android, no Compose. Platform crypto (JCA) is used only in `crypto/JcaCrypto.kt`, behind the interfaces in `crypto/Crypto.kt`.

## Entry points

| Class | Role |
|---|---|
| `host.SessionHost` | Authoritative host. `accept(transport)`, `pendingAdmissions()`, `approve(connectionId, code)`, `deny`, `setAi(seat, policy)`, `advance()`, `restartRoom()`. Wraps the WI-04 `MatchReducer` and the WI-03/WI-04 projectors. |
| `client.SessionClient` | One member. `connect(transport)`, `submit(body)`, views (`memberView`, `spectatorView`, `follow`), `grants`, `confirmationCode`, `stale`. |
| `transport.Transport` | One end of a peer connection (BLE, LAN, in-memory). |
| `transport.InMemoryNetwork` | Seeded in-memory links with delay, jitter, drop, duplicate, reorder, truncate, disconnect, blackhole and backpressure. |
| `transport.Scheduler` / `VirtualScheduler` | Time and deferred work; tests run in deterministic virtual time. |
| `host.SessionHost.create` / `restore` | WI-06. A saved table: `create(…, storage, tableId, now)` starts one; `restore(…)` returns `Restored`, `Quarantined` (still answers rejoining members, applies nothing), `FutureSchema`, `Missing` or `StorageFailed`. Host UI: `pause`, `resume`, `suspend(now, expiresAt)`, `end(now, expiresAt)`, `status`, `seatControl(seat)`. |
| `host.RecoveryPolicy`, `SeatRecovery` | WI-06. Grace, then substitution or pause (`AbsencePolicy`), set by the lobby (`HostConfig.recovery`, no default). `SeatRecovery.next` is the one transition, used live and after restore. |
| `storage.Storage` | `append`, `sync`, `replace` (atomic), `read`, `delete`, `list`. `FileStorage` (java.nio, `FileChannel.force`, `ATOMIC_MOVE`) for devices; `InMemoryStorage` for tests: numbered steps, kill before or after any step, torn unsynced tails, injected failures, corruption tools. |
| `storage.TableJournal` | Write side of one table: `commit` (record, sync; marker, sync; high-water mark by atomic replace), `writeCheckpoint`, `writeMembers`, `writeMeta`. |
| `storage.TableRecovery` | Read side: replays the log to the last committed revision, from the newest verified checkpoint, checking every state hash; quarantines instead of going below the high-water mark. |
| `storage.TableRepository` | `list`, `sweep(now)` (expired suspended/ended tables only), `delete(tableId, CONFIRMED_BY_USER)`. |
| `storage.RestoreValidator` | Card count, duplicate ids, turn, phase, revision and profile hash of a restored state. |
| `ai.AiSeatDriver` | WI-07. `policy` goes to `setAi` (or `RecoveryPolicy.substitute`). Builds the core `PolicyInput` from the seat's observation and options, picks the seat's settings for the deal in progress, runs the core `DecisionRunner` on a `DecisionExecutor` and arms a deadline on the scheduler; the first of answer or deadline (deterministic fallback) is delivered, once. `request(seat, settings, dealNumber)` changes a seat from the next deal. `onDecision` and `metrics` record policy, source and steps (no cards). |
| `ai.MemberHints` | WI-07. `request(client)`: a hint for this device's own member from its own projection, same generator and policy as the AI; respects the room policy; never for a spectator; never sent over the wire. |
| `diagnostics.DiagnosticsReport`, `SessionDiagnostics` | WI-12. Local diagnostics export (P8, GDN-108). `Builder` takes only `DiagnosticField`s with counts, revisions, flags, enum tokens from the field's own list, or a `RuleProfile`'s id/version/hash; `SessionDiagnostics.host`/`client` write a host's and a client's state and counters (never the quarantine detail, room or member ids, nicknames, capabilities, keys, seeds or cards). `render()` re-validates every line; `validate(text)` is the allow-list check. Pure in-memory: no file, no socket. |
| `host.DeferredSeatPolicy` | WI-07. A seat policy whose answer may arrive later; the host applies it only while (epoch, turn revision, controller) still hold, and ignores a second delivery. |

## Layers

1. **Chunks** (`framing/`): length-prefixed chunks (`u16 length`, magic `0xC7`, version, `u32 frameId`, `u16 index`, `u16 count`, `u32 frameLength`). Bounded reassembly: max frame size, max chunk payload, max partial frames, remembered frame ids. A frame is delivered only when every chunk is present and the lengths agree; a partial frame is never delivered.
2. **Frames** (`channel/`): byte 0 is `PLAIN` (handshake JSON only) or `SEALED` (`u64 seq` + AES-256-GCM, per-direction keys, replay window of 64).
3. **Messages** (`wire/`): strict JSON with a `type` discriminator. Unknown fields, missing fields, wrong JSON types and nesting deeper than 8 are refused. Field bounds are checked after decoding (`WireValidation`).
4. **Per-peer queue** (`channel/PeerLink`): one ordered, bounded queue per peer. Snapshots and follow snapshots coalesce (newest wins); heartbeats coalesce; receipts and control messages are never dropped; overflow closes the peer. A slow peer only fills its own queue.

## Messages

Plain: `hello` (version range, role, room id, both profile hashes, public key), `welcome` (ephemeral key, room key, nonce, room policy), `hello_rejected`.
Sealed: `join` (a returning member sends a rejoin proof, never its capability), `admission_pending`, `admitted` (capability, digest key), `admission_denied`, `command` (epoch, capability, command id, expected revision, body), `receipt` (committed revision, keyed digest), `rejection` (typed code), `snapshot`, `follow_snapshot` (grant id and grant revision), `status` (grants), `heartbeat`, `resync`, `closing`.
Command bodies: `play`, `pass`, `give_tribute`, `return_tribute`, `resist`, `ask_count` (game; players only), `follow_request`, `consent`, `revoke`, `leave` (session).

Fixtures for every message, the chunk and sealed-frame layouts, key derivation and the receipt digest are in `core/src/test/resources/fixtures/wire/`. `WireFixtureTest` fails when they differ from the code and writes the expected files to `session/build/wire-fixtures/`.

## Rules the code enforces

- **Receipts**: the digest is `HMAC-SHA256(member digest key, the member's own canonical projection bytes)` — never a hash of the full state (P1). The client recomputes it; a mismatch is counted and forces a resync.
- **Idempotency**: replies are cached per member by command id; a retry gets the original reply byte for byte and applies nothing.
- **Revisions**: the host commits only strictly increasing revisions; a client never applies an older snapshot. Game commands must name the current revision.
- **Identity**: every transport callback and timer is bound to (epoch, connection id); AI tasks to (epoch, revision, controller). Stale ones do nothing. A deferred AI decision still running when its revision or controller changes is cancelled; an AI task that fires while the room is paused is re-armed when the room is active again.
- **Admission**: nothing but handshake and admission messages reaches an unadmitted peer (P6). Version, room and profile-hash mismatches are refused at hello, before any game data.
- **Host authentication (G2)**: a rejoin carries HMAC-SHA256(capability, transcript of room id, epoch, the host's fresh nonce, the room key fingerprint and both ephemeral keys), never the capability; the client refuses a welcome whose room key is not the one it pinned (`HOST_KEY_MISMATCH`), and the session keys depend on the room key's private half.
- **Liveness**: heartbeats each second; no valid frame for 5 s closes the peer (host) or marks the view stale (client).

## Decisions where the plan was silent (defaults)

| Name | Default |
|---|---|
| Room mode | `FRIENDLY` (ledger default policy 7: friendly permits hints; competitive disables hints and private follow) |
| Observer cap | 2 (admitted plus pending) |
| Grant lifetime | Persists across deals until revoked, departure or replacement; switching seats only at the next deal, with new consent |
| Re-follow same seat in a deal | Allowed with a new consent; a different seat in the same deal is refused (`ONE_VIEW_PER_DEAL`) |
| Disconnect vs departure | A dropped connection keeps the seat and its grants; only `leave` or replacement is departure |
| Expected revision | Strict equality for game commands; session commands (follow, consent, revoke, leave) are not revision-checked |
| Rejection detail | The rule's type name only (`Deal.WrongSeat`), never card or seat data |
| Hello deadline | 10 s for hello and join together |
| Limits | 64 KiB frame, 160-byte chunk payload, 4 partial frames, 64 queued messages, 200 frames/s, 8 unadmitted connections |
| Retries | Hello, join and commands resend with the same ids, 400 ms doubling to 3.2 s |
| Resync trigger | Still below the revision or status revision the *previous* heartbeat announced |
| Receipt digest projection | The match-level view (`MatchMemberView` / `MatchSpectatorView` via `MatchProjectionCodec`), which embeds the WI-03 deal view |
| AI decision deadline (WI-07) | `AiDriverConfig.deadlineMs` = 1,000 ms of scheduler time after the AI task starts (after `aiDelayMs`); then the deterministic fallback |
| AI work budget (WI-07) | `DecisionLimits.DEFAULT` = 60,000 steps, about 3.5 times the largest generator output measured (17,334 plays) |
| AI executor (WI-07) | `DecisionExecutor.INLINE` (host thread); the Android session owner (WI-08) supplies a worker executor that posts results back to the scheduler thread |
| AI tie-break seed (WI-07) | `AiDriverConfig.tieBreakSeed` (room level) mixed with deal number, deal revision, phase and seat; breaks exact score ties only |
| Hint location (WI-07) | Computed on the requesting member's device from its own projection; no hint message exists on the wire |
| Resistance claim by an AI (WI-07) | Offered to a policy only when its own hand holds both big jokers, so the offer never reveals a partner's card; a split-joker claim is left to humans |

## Persistence and recovery (WI-06)

Table directory `tables/<id>/`: `meta` (schema, epoch, profile hashes, status and expiry), `hwm` (highest acknowledged revision), `members` (capabilities and digest keys for rejoin, AI seats), `log.<segment>` (records and commit markers), `checkpoint.<revision>` (newest two), and `quarantine` / `deleting` markers. Every file carries a CRC-32; layouts are in `storage/LogFormat.kt`.

- **Write order** per revision: record, sync; marker (CRC of that record), sync; high-water mark (atomic replace). Only then does any member see the revision: snapshots and the receipt. Members are saved before `admitted` is sent. A failed write pauses the room (`PauseReason.STORAGE_FAILURE`); the issuer gets `rejection ROOM_PAUSED`, never a receipt, and only a restart from storage resumes.
- **Recovery** replays to the last revision whose record and marker both verify. Below the high-water mark, or with an unreadable mark: quarantine. A committed revision above the mark is kept, the mark is raised before anything is revealed, and a retry of its command returns the original receipt (the record stores issuer, command id and receipt digest). A torn or unmarked tail is ignored and appends continue in a new segment; old segments are never truncated.
- **Member ahead**: a member whose heartbeat (`heldRevision`: the highest receipt or snapshot it holds) or command names a revision above the host's quarantines the room (`MEMBER_AHEAD`).
- **Checkpoints** are written every `HostConfig.checkpointEvery` revisions and after every settlement; a checkpoint is used only as the start of replay and only when its hash equals the hash in the log record of the same revision.

| Decision where the plan was silent | Default |
|---|---|
| Checksum | CRC-32 per frame and file (corruption detection, not tamper resistance; files are host-private and excluded from backup) |
| Per-record state hash | Each record stores the host-internal SHA-256 of the state after it; replay verifies every one |
| Grace and policy | No default: `HostConfig.recovery` is required (plan section 3 leaves the value to the lobby) |
| Rejoin during substitution | The human reclaims the seat at once; the stand-in's queued task becomes inert |
| Leave | No grace: `afterLeave` applies at once; a vacated seat may be taken by a newcomer with host approval |
| Paused-room rejection | `ROOM_PAUSED` / `ROOM_QUARANTINED`, not cached, so a retry after the pause is judged afresh |
| Grants after restore | Not saved: private follow needs new consent after a restart |
| Session commands after restore | Only game commands are deduplicated across a restart (follow, consent, revoke, leave are not logged) |
| Checkpoint failure | Counted, does not pause (a cache) |
| Restored AI seats | Use `RecoveryPolicy.substitute`; without one they come back empty |
| Storage schema | 3 since 2026-10-16: adds `hostkey` (the room key, G2) and match state format 2 (with the match number). Schemas 1 and 2 were never released: such a save is quarantined (`UNSUPPORTED_OLD_SCHEMA`) and kept until the user deletes it (pre-release reset, no migration) |
| Delete | Tombstone first, then every file, tombstone last; an interrupted delete is finished by the next `list` or `sweep` |

## Lobby, roster and rematch (WI-09)

The room's lobby lives in the host like everything else; the app only asks.

- **Roster** travels in `Status.room` (`RoomView`: lobby flag, paused flag, settings and their revision, the four seats with occupant, nickname, ready and grace left, watcher names, and the recipient's own seat). A lost update is detected through the status revision like every other status change.
- **Ready** is a session command (`ReadyBody(ready, settingsRevision)`): refused with `NOT_IN_LOBBY` once dealt, and with `SETTINGS_CHANGED` when the player readied for older settings. Changing settings clears every ready flag.
- **Start** (`SessionHost.start`) applies the same `LobbyGate` the screens show: an empty seat (it must be given to an AI explicitly), a human not ready, or an away human blocks.
- **Seats** move only in the lobby (`swapSeats`), ending grants on the moved seats; the moved member's client clears its view and resyncs.
- **Rematch** (`MatchCommand.Rematch`, event `match_restarted`) is accepted only from `OVER`: same members, seats, capabilities and connections; revision order continues; the room returns to the lobby with ready cleared and the one-view-per-deal count reset.
- **Stand-in now** (`standInNow`, H1): the host may let the room's substitute AI take an away seat before the grace ends.

| Decision where the plan was silent | Default |
|---|---|
| Wire version | Protocol and event schema stayed at version 1 for WI-09: the additions (ready, room view, `match_restarted`, two rejection codes) were pre-release and additive; fixtures regenerated (`wire/lobby.json`). **2026-10-16: protocol version 2** (G2 welcome and join; `matchNumber` in every match view, match projection schema 2); version 1 was never released and is refused at hello |
| Empty seats | Never filled by an AI automatically; the host chooses |
| Host's own player | Ready automatically for each settings revision (it chose them) and again after a rematch |
| Grace choices | 10, 30 (default) or 60 s, chosen by the host; the absence policy after the grace is substitute (default) or pause |
| Suspended table retention | 12 h from the moment the host suspends; the expiry stored is the one the host was shown |
| Ending a table | Deletes its save, settings and rejoin ticket on the host's device; members are closed with `EPOCH_ENDED` |
| Rejoin after the host is lost | Every 5 s with the stored ticket (a proof, never the capability; G2), following only an advert with the pinned host key, until the host closes the table for good |
| G3 "request shown after the current action" | The consent card is not shown during the player's own turn |
| Not built | "Watch only" admission of a seat request (B8), AI seats placed by consent of the joiners (G2 screen), AI difficulty choice |

## Gates G2 and G4

**G2 (admission, host authentication and private transport)** — owner-delegated default implemented 2026-10-16; **no external cryptography review** (`dev-docs/decisions/G2-host-authentication.md`):

- Ephemeral ECDH on P-256 per connection, mixed with ECDH against the host's static room key; keys from HKDF-SHA256 bound to room id, epoch, both ephemeral keys, the room key and the host's per-connection nonce (`crypto/SessionKeys.kt`).
- The room key is stored with a saved table (`tables/<id>/hostkey`) so a restored table keeps it; its fingerprint (HMAC-SHA256 under a fixed label) is in every advert and pinned by members with their rejoin ticket.
- Six-digit confirmation code from the same derivation for a first join; the host approves only with the code shown by the joiner (`SessionHost.approve`). No BLE pairing is relied on.
- AES-256-GCM sealed frames with counter nonces and per-direction keys; 64-frame replay window (`channel/SecureChannel.kt`).
- Capabilities: 32 random bytes per member, valid for one epoch, rotated on replacement. A rejoin sends a proof keyed with the capability over this connection's transcript (`RejoinProof`); the capability itself is never sent by a joiner. Commands and heartbeats still carry it inside the sealed channel to the authenticated host.
- Per-member 32-byte receipt digest key sent in `admitted`.

**G4 (spectator policy)** — PROVISIONAL, deferred by the project owner (devices later); follows the G4 proposed default (reference D6, ledger default 7):

- One private viewpoint per deal; consent by the followed player; revocation boundary (no new follow frames, queued ones dropped, client discards stale grant revisions); departure and replacement revoke; replacement needs new consent.
- Competitive rooms disable hints and private follow (`RoomPolicy.COMPETITIVE`); friendly is the default.
- WI-07 hints follow it: `MemberHints` offers a hint only when the room policy received in `welcome` says `hints = true`, never to an observer (with or without a follow grant). A modified client can always compute advice from its own hand; no protocol prevents that, and none is claimed.
