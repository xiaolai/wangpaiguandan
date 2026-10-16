package cn.wpgd.app.session

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.match.MatchCommand
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.match.MatchReduceResult
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.channel.plainFrame
import cn.wpgd.app.session.crypto.Hkdf
import cn.wpgd.app.session.crypto.HostKey
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.RejoinProof
import cn.wpgd.app.session.crypto.SessionKeys
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.ReadyBody
import cn.wpgd.app.session.wire.RoomSettingsView
import cn.wpgd.app.session.wire.RoomView
import cn.wpgd.app.session.wire.RosterSeat
import cn.wpgd.app.session.wire.AdmissionDenied
import cn.wpgd.app.session.wire.AdmissionPending
import cn.wpgd.app.session.wire.Admitted
import cn.wpgd.app.session.wire.AskCountBody
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Closing
import cn.wpgd.app.session.wire.CommandMessage
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.Heartbeat
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.HelloRejected
import cn.wpgd.app.session.wire.Hex
import cn.wpgd.app.session.wire.Join
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.Resync
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.RevokeBody
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomMode
import cn.wpgd.app.session.wire.RoomPolicyView
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.Status
import cn.wpgd.app.session.wire.Welcome
import cn.wpgd.app.session.wire.WireAssignment
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.session.wire.WireMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The canonical content of the shared wire fixtures in `core/src/test/resources/fixtures/wire/`.
 * [WireFixtureTest] writes what this produces to `session/build/wire-fixtures/` and fails when the
 * checked-in fixtures differ, so a protocol change shows up as a fixture diff for the iPhone client.
 */
internal object WireSamples {
    private val key = B64.encode(ByteArray(32) { it.toByte() })
    private val cap = B64.encode(ByteArray(32) { (32 + it).toByte() })
    private val nonce = B64.encode(ByteArray(32) { (64 + it).toByte() })
    private val proof = B64.encode(ByteArray(32) { (96 + it).toByte() })
    private const val HASH_A = "1111111111111111111111111111111111111111111111111111111111111111"
    private const val HASH_B = "2222222222222222222222222222222222222222222222222222222222222222"

    val handshake: List<Pair<String, WireMessage>> = listOf(
        "hello-player" to Hello(2, 2, Role.PLAYER, "room-a", HASH_A, HASH_B, key),
        "hello-observer" to Hello(2, 3, Role.OBSERVER, "room-a", HASH_A, HASH_B, key),
        "welcome" to Welcome(2, "room-a", 3, key, cap, nonce, RoomPolicyView(RoomMode.FRIENDLY, hints = true, privateFollow = true)),
        "hello-rejected-version" to HelloRejected(HelloRejectReason.VERSION_UNSUPPORTED, 2, 2, "host speaks 2..2"),
        "join-seat" to Join(3, "south", Seat.SOUTH, false, null),
        "join-rejoin" to Join(3, "south", Seat.SOUTH, false, proof),
        "join-observer" to Join(3, "watcher", null, false, null),
        "admission-pending" to AdmissionPending(3),
        "admitted" to Admitted(3, "m1", Role.PLAYER, Seat.SOUTH, cap, key),
        "admission-denied" to AdmissionDenied(3, AdmissionDenial.SEAT_TAKEN),
        "closing-replaced" to Closing(3, CloseReason.REPLACED),
        "heartbeat-admitted" to Heartbeat(3, 17, 2),
        "heartbeat-unadmitted" to Heartbeat(3, null, null),
        "resync" to Resync(3, cap),
    )

    val commands: List<Pair<String, WireMessage>> = listOf(
        "play-natural" to CommandMessage(3, cap, "c-1", 17, PlayBody(listOf("0S3", "1S3"), emptyList(), null)),
        "play-wildcard" to CommandMessage(3, cap, "c-2", 18, PlayBody(listOf("0H2", "0C7"), listOf(WireAssignment(7, Suit.HEART)), CombinationType.PAIR)),
        "pass" to CommandMessage(3, cap, "c-3", 19, PassBody),
        "give-tribute" to CommandMessage(3, cap, "c-4", 20, GiveTributeBody("1BJ")),
        "return-tribute" to CommandMessage(3, cap, "c-5", 21, ReturnTributeBody("0D4")),
        "resist" to CommandMessage(3, cap, "c-6", 20, ResistBody),
        "ask-count" to CommandMessage(3, cap, "c-7", 22, AskCountBody(Seat.EAST)),
        "follow-request" to CommandMessage(3, cap, "c-8", 22, FollowRequestBody(Seat.NORTH)),
        "consent" to CommandMessage(3, cap, "c-9", 22, ConsentBody("g1", true)),
        "revoke" to CommandMessage(3, cap, "c-10", 22, RevokeBody("g1")),
        "leave" to CommandMessage(3, cap, "c-11", 22, LeaveBody),
    )

    val receipts: List<Pair<String, WireMessage>> = listOf(
        "receipt" to Receipt(3, "c-1", 18, key),
        "snapshot" to Snapshot(3, 18, "{\"schemaVersion\":1}"),
        "follow-snapshot" to FollowSnapshot(3, 18, "g1", 2, Seat.NORTH, "{\"schemaVersion\":1}"),
        "status" to Status(3, 4, listOf(GrantView("g1", "watcher", Seat.NORTH, GrantStatus.ACTIVE, 2)), room(lobby = false, yourSeat = Seat.NORTH)),
    )

    /** WI-09: the room roster and settings every member sees (inside `status`). */
    fun room(lobby: Boolean, yourSeat: Seat?, paused: Boolean = false) = RoomView(
        lobby = lobby,
        paused = paused,
        settings = RoomSettingsView(RoomPolicyView(RoomMode.FRIENDLY, hints = true, privateFollow = true), 30_000, AbsenceMode.SUBSTITUTE, AbsenceMode.SUBSTITUTE),
        settingsRevision = 2,
        seats = listOf(
            RosterSeat(Seat.SOUTH, Occupant.HUMAN, "south", ready = true, graceRemainingMs = null),
            RosterSeat(Seat.EAST, Occupant.ABSENT, "east", ready = false, graceRemainingMs = 12_500),
            RosterSeat(Seat.NORTH, Occupant.STAND_IN, "north", ready = false, graceRemainingMs = null),
            RosterSeat(Seat.WEST, Occupant.AI, null, ready = false, graceRemainingMs = null),
        ),
        observers = listOf("watcher"),
        yourSeat = yourSeat,
    )

    /** WI-09: the lobby: ready, the roster in a lobby status, and the lobby rejections. */
    val lobby: List<Pair<String, WireMessage>> = listOf(
        "ready" to CommandMessage(3, cap, "c-12", 0, ReadyBody(ready = true, settingsRevision = 2)),
        "not-ready" to CommandMessage(3, cap, "c-13", 0, ReadyBody(ready = false, settingsRevision = 2)),
        "status-lobby" to Status(3, 5, emptyList(), room(lobby = true, yourSeat = Seat.SOUTH)),
        "status-observer" to Status(3, 6, emptyList(), room(lobby = true, yourSeat = null)),
        "status-paused" to Status(3, 7, emptyList(), room(lobby = false, yourSeat = Seat.EAST, paused = true)),
        "settings-changed" to RejectionReceipt(3, "c-12", 0, RejectionCode.SETTINGS_CHANGED, "settings 3"),
        "not-in-lobby" to RejectionReceipt(3, "c-13", 9, RejectionCode.NOT_IN_LOBBY, "match started"),
    )

    val rejections: List<Pair<String, WireMessage>> = listOf(
        "rule" to RejectionReceipt(3, "c-3", 18, RejectionCode.RULE, "Deal.WrongSeat"),
        "stale-revision" to RejectionReceipt(3, "c-4", 19, RejectionCode.STALE_REVISION, "now 19"),
        "not-a-player" to RejectionReceipt(3, "c-5", 19, RejectionCode.NOT_A_PLAYER, "observers cannot act"),
        "stale-epoch" to RejectionReceipt(3, "c-6", 19, RejectionCode.STALE_EPOCH, "epoch 2"),
        "one-view-per-deal" to RejectionReceipt(3, "c-7", 19, RejectionCode.ONE_VIEW_PER_DEAL, "viewed SOUTH this deal"),
    )

    /** WI-06: what a paused, quarantined or suspended room sends. */
    val recovery: List<Pair<String, WireMessage>> = listOf(
        "room-paused" to RejectionReceipt(3, "c-8", 19, RejectionCode.ROOM_PAUSED, "STORAGE_FAILURE"),
        "room-quarantined" to RejectionReceipt(3, "c-9", 19, RejectionCode.ROOM_QUARANTINED, "BELOW_HIGH_WATER_MARK"),
        "closing-suspended" to cn.wpgd.app.session.wire.Closing(3, cn.wpgd.app.session.wire.CloseReason.SUSPENDED),
    )

    fun messageFixture(description: String, cases: List<Pair<String, WireMessage>>): JsonObject = buildJsonObject {
        put("schema", 1)
        put("protocolVersion", cn.wpgd.app.session.wire.PROTOCOL_VERSION)
        put("description", description)
        put("cases", JsonArray(cases.map { (name, m) -> JsonObject(mapOf("name" to JsonPrimitive(name), "wire" to WireCodec.json.parseToJsonElement(WireCodec.encodeToString(m)))) }))
    }

    /** Chunking, sealing and key-derivation vectors, all from fixed inputs. */
    fun frameEnvelope(): JsonObject {
        val frame = plainFrame(WireCodec.encode(Heartbeat(3, 17, 2)))
        val chunks = ChunkCodec.split(7, frame, 16)
        val sealKey = ByteArray(32) { it.toByte() }
        val sealedPlain = WireCodec.encode(Heartbeat(3, 17, 2))
        val sealer = Sealer(JcaAesGcm, sealKey, Direction.HOST_TO_CLIENT)
        val sealed1 = sealer.seal(sealedPlain)
        val sealed2 = sealer.seal(sealedPlain)
        val shared = ByteArray(32) { (0xA0 + it).toByte() }
        val roomShared = ByteArray(32) { (0x50 + it).toByte() }
        val clientPub = ByteArray(65) { (it * 3).toByte() }
        val hostPub = ByteArray(65) { (it * 5).toByte() }
        val roomKey = ByteArray(65) { (it * 7).toByte() }
        val nonceBytes = ByteArray(32) { (64 + it).toByte() }
        val keys = SessionKeys.derive(Hkdf(JcaHmacSha256), shared, roomShared, "room-a", 3, clientPub, hostPub, roomKey, nonceBytes)
        val fingerprint = HostKey.fingerprintOf(JcaHmacSha256, roomKey)
        val capability = ByteArray(32) { (32 + it).toByte() }
        val transcript = RejoinProof.transcript("room-a", 3, nonceBytes, fingerprint, clientPub, hostPub)
        return buildJsonObject {
            put("schema", 1)
            put("description", "Chunk layout (u16 length, 0xC7, version 1, u32 frameId, u16 index, u16 count, u32 frameLength, payload); " +
                "PLAIN frame = 0x01 + JSON; SEALED frame = 0x02 + u64 seq + AES-256-GCM(ciphertext||tag), nonce = direction tag + seq, AAD = the 9 header bytes; " +
                "keys = HKDF-SHA256(salt empty, ikm = length-prefixed ECDH(client eph, host eph) and ECDH(client eph, host room key), " +
                "info = length-prefixed label, roomId, epoch u64, client key, host ephemeral key, host room key, nonce) -> c2h 32, h2c 32, code 4. " +
                "Host key fingerprint = HMAC-SHA256(key ASCII label, SPKI room key). Rejoin proof = HMAC-SHA256(capability, length-prefixed label, roomId, epoch u64, nonce, fingerprint, client key, host ephemeral key). " +
                "G2: owner-delegated default, no external cryptography review.")
            put("chunking", buildJsonObject {
                put("maxChunkPayload", 16)
                put("frameId", 7)
                put("frameHex", Hex.encode(frame))
                put("chunksHex", JsonArray(chunks.map { JsonPrimitive(Hex.encode(it)) }))
            })
            put("sealed", buildJsonObject {
                put("keyHex", Hex.encode(sealKey))
                put("direction", Direction.HOST_TO_CLIENT.name)
                put("directionTagHex", Hex.encode(Direction.HOST_TO_CLIENT.tag))
                put("plaintext", sealedPlain.decodeToString())
                put("frames", JsonArray(listOf(sealed1, sealed2).mapIndexed { i, f ->
                    JsonObject(mapOf("seq" to JsonPrimitive(i + 1), "frameHex" to JsonPrimitive(Hex.encode(f))))
                }))
            })
            put("keyDerivation", buildJsonObject {
                put("label", SessionKeys.LABEL)
                put("sharedSecretHex", Hex.encode(shared))
                put("roomKeySharedSecretHex", Hex.encode(roomShared))
                put("roomId", "room-a")
                put("epoch", 3)
                put("clientPublicKeyHex", Hex.encode(clientPub))
                put("hostPublicKeyHex", Hex.encode(hostPub))
                put("hostRoomKeyHex", Hex.encode(roomKey))
                put("nonceHex", Hex.encode(nonceBytes))
                put("clientToHostHex", Hex.encode(keys.clientToHost))
                put("hostToClientHex", Hex.encode(keys.hostToClient))
                put("confirmationCode", keys.confirmationCode)
            })
            put("hostKey", buildJsonObject {
                put("label", HostKey.FINGERPRINT_LABEL)
                put("roomKeyHex", Hex.encode(roomKey))
                put("fingerprintHex", Hex.encode(fingerprint))
                put("fingerprintB64", B64.encode(fingerprint))
            })
            put("rejoinProof", buildJsonObject {
                put("label", RejoinProof.LABEL)
                put("capabilityHex", Hex.encode(capability))
                put("transcriptHex", Hex.encode(transcript))
                put("proofHex", Hex.encode(RejoinProof.of(JcaHmacSha256, capability, transcript)))
            })
        }
    }

    /** The first deal of seed 42, opened: the views every platform must reproduce byte for byte. */
    fun dealState(): MatchState {
        val reducer = MatchReducer(TestProfiles.base, TestProfiles.match)
        var s = reducer.newMatch()
        for (c in listOf(MatchCommand.DealNext(SplitMix64(42)), MatchCommand.OpenPlay)) {
            s = (reducer.reduce(s, c) as MatchReduceResult.Accepted).state
        }
        return s
    }

    fun digestFixture(): JsonObject {
        val projector = MatchProjector(TestProfiles.base, TestProfiles.match)
        val s = dealState()
        val digestKey = ByteArray(32) { (0x40 + it).toByte() }
        fun case(seat: Seat?): JsonObject {
            val bytes = if (seat == null) MatchProjectionCodec.encode(projector.spectatorView(s)) else MatchProjectionCodec.encode(projector.memberView(s, seat))
            return JsonObject(
                mapOf(
                    "seat" to (seat?.name?.let(::JsonPrimitive) ?: JsonNull),
                    "projection" to JsonPrimitive(bytes.decodeToString()),
                    "digest" to JsonPrimitive(B64.encode(JcaHmacSha256.mac(digestKey, bytes))),
                ),
            )
        }
        return buildJsonObject {
            put("schema", 1)
            put("description", "Receipt digest = base64url(HMAC-SHA256(digestKey, projection UTF-8 bytes)). Projections are the " +
                "first deal of SplitMix64 seed 42 after OpenPlay, under P1-base-v1 and M1-match-v1; regenerate when a profile changes.")
            put("dealSeed", 42)
            put("baseProfileHash", TestProfiles.base.hash)
            put("matchProfileHash", TestProfiles.match.hash)
            put("digestKeyHex", Hex.encode(digestKey))
            put("cases", JsonArray(listOf<JsonElement>(case(Seat.SOUTH), case(Seat.EAST), case(null))))
        }
    }

    val files: Map<String, () -> JsonObject> = linkedMapOf(
        "handshake.json" to { messageFixture("Handshake, admission and liveness messages. hello/welcome/hello_rejected travel in PLAIN frames; the rest only sealed.", handshake) },
        "command.json" to { messageFixture("Client commands: capability, idempotent commandId, expectedRevision, typed body. The seat is never in the body.", commands) },
        "receipt.json" to { messageFixture("Host deliveries: receipt (keyed digest of the issuer's own projection), snapshot, follow_snapshot, status.", receipts) },
        "rejection.json" to { messageFixture("Typed rejection receipts (audit A20).", rejections) },
        "lobby.json" to { messageFixture("WI-09: ready (lobby only, for the settings revision the player saw), the room roster and settings inside status, and the lobby rejections.", lobby) },
        "recovery.json" to { messageFixture("WI-06: a paused or quarantined room refuses commands (not cached: a retry is judged again); a suspended room closes connections and keeps capabilities.", recovery) },
        "frame-envelope.json" to { frameEnvelope() },
        "digest.json" to { digestFixture() },
    )
}
