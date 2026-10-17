# G2 — admission, host authentication and rejoin

**Status: OWNER-DELEGATED DEFAULT IMPLEMENTED — no external cryptography review**

| Field | Value |
|---|---|
| Gate | G2 (`dev-docs/implementation-plan.md` section 3) |
| Decider | project owner, who delegated the design to the implementer on 2026-10-16 |
| Date | 2026-10-16 |
| Review | None. No cryptographer reviewed it, and the Codex refute pass the plan asks for was not run. This is not a signature of G2 in the sense of the other gates; it records what the owner asked to be built. |
| Protocol | Wire protocol version 2 (`PROTOCOL_VERSION`), advert format 2, storage schema 3 |

## What the owner asked for

1. A rejoin never sends the raw capability.
2. The host sends a fresh random nonce in its welcome; the client proves possession with
   HMAC-SHA256(capability, transcript = room id ‖ epoch ‖ nonce ‖ host key fingerprint) and sends only the MAC.
3. The client pins the host key fingerprint it first saw (stored with the rejoin ticket) and refuses a
   welcome with a different key.
4. A member that lost its host follows a new address only when the advert (and then the welcome)
   carries the pinned fingerprint.
5. First-time joins keep the six-digit confirmation code comparison.

## What was built (session/src/main/kotlin/cn/wpgd/app/session/)

| Part | Where | Detail |
|---|---|---|
| Room key | `crypto/SessionKeys.kt` (`HostKey`), `host/SessionHost.kt` | One static P-256 key per room. A saved table generates it at creation and stores both halves in `tables/<id>/hostkey` (CRC-checked, host-private, excluded from backup) before anything else; a restore imports it and checks that the halves belong together. An unreadable key quarantines the table (`HOST_KEY_UNREADABLE`). |
| Fingerprint | `HostKey.fingerprintOf` | HMAC-SHA256(key = ASCII `wpgd-host-key-v1`, data = SPKI DER public key), base64url (43 characters). A fixed-key HMAC is used as the hash so the platform seam needs no new primitive. |
| Key agreement | `SessionKeys.derive` | HKDF-SHA256 over ECDH(client ephemeral, host ephemeral) and ECDH(client ephemeral, room key), info binding label `wpgd-session-v2`, room id, epoch, both ephemeral keys, the room key and the nonce. A peer that copies the room key's public half into its welcome cannot derive the keys, so it cannot open the joiner's sealed join. |
| Welcome | `wire/Messages.kt` | Adds `hostKey` (room public key) and `nonce` (32 random bytes, new for every connection). |
| Rejoin proof | `crypto/SessionKeys.kt` (`RejoinProof`), `client/SessionClient.kt`, `SessionHost.provenMember` | `Join.rejoinProof` = HMAC-SHA256(capability, label `wpgd-rejoin-v1` ‖ room id ‖ epoch ‖ nonce ‖ fingerprint ‖ client ephemeral key ‖ host ephemeral key), each length-prefixed. The host checks every member in constant time. The field `Join.capability` is gone. |
| Pinning | `SessionClient.onWelcome`, `ClientConfig.pinnedHostKey`, app `RejoinTicket` | A ticket is (capability, fingerprint). A welcome with another room key closes the connection with `HOST_KEY_MISMATCH` before any join is sent. A client also pins the first key it meets, so its own reconnects are held to it. A ticket stored before pinning existed is ignored (the member asks to join afresh). |
| Following a moved host | app `SessionOwner.roomFound`, `RoomAdvert.hostKey`, `AdvertCodec` (`k`) | Only an advert with the lost room's id **and** the pinned fingerprint is followed; others are counted (`owner.room_moves_refused`). |
| First join | unchanged | Six-digit code from the key derivation; the host user approves only with the code on the joiner's screen. The code now also covers the room key. |

## Decisions the implementer made (beyond the request)

- **Channel binding in the proof transcript.** Both ephemeral public keys are added to the
  transcript the owner specified. Mechanism: without them, a relay that could see a proof could
  forward it on its own connection to the real host while the nonce is still live. With them, the
  proof only verifies on the connection it was made for.
- **The room key takes part in the key agreement** (static-ephemeral DH) instead of signing the
  welcome. Mechanism: a spoofer that shows the pinned public key cannot derive the session keys, so
  the joiner's sealed join, and the proof in it, never opens for it. This needs no new primitive
  (no signature algorithm), which keeps the iPhone port to the same CryptoKit primitives.
- **Commands and heartbeats still carry the capability**, inside the sealed channel, after the host
  has been authenticated by the room key (rejoin) or by the confirmation code (first join). The
  request covered the rejoin; removing the capability from every command is a further protocol change
  that was not asked for.
- **A host-key mismatch is not final.** The member keeps its ticket and keeps retrying, so a spoofer
  cannot make it forget its seat.
- **Pre-release reset.** Saves of storage schema 1 and 2 are quarantined and kept (they have no room
  key); rejoin tickets without a pin are ignored. Nothing was released with the older formats.

## Tests

`session/src/test/kotlin/cn/wpgd/app/session/HostAuthenticationTest.kt` (proof instead of capability;
another key refused before a join is sent; a spoofer showing the pinned key without its private half
opens nothing; an old or forged proof is refused on a new connection; first join still needs the code),
`CryptoTest` (both ends agree; the room key's private half is needed; export and import),
`WireFixtureTest` (key-derivation, fingerprint and proof vectors in `core/src/test/resources/fixtures/wire/frame-envelope.json`),
`storage/SchemaVersionTest` (missing or corrupt room key quarantines; a restored table keeps its key; schema 1 and 2 quarantined),
app `HostPortChangeRejoinTest` (an advert with the room id and another key is not followed; the pinned host is),
`AdvertCodecTest`, `IdentityResetTest` (ticket format).

## Not covered

- No external review of the construction or the code.
- The iPhone client does not exist; the Swift port must implement the same derivation (vectors in
  `frame-envelope.json`).
- A device that loses its stored tickets (identity reset, reinstall) rejoins only through a fresh,
  host-approved join, as before.
