package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleProfileLoader
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.testCrypto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-052, audit A13: a restored state with a bad card count, a duplicate card id, a turn on a seat
 * that cannot act, or another rule profile is quarantined, never repaired. The bytes stay as found.
 */
class RestoreValidationTest {
    private val at = 40L

    /** An all-AI table stopped exactly at a checkpoint, so restore starts from (and validates) that checkpoint. */
    private fun tableAtCheckpoint(seed: Long): SavedTable {
        val saved = SavedTable(seed, config = savedConfig(checkpointEvery = at.toInt()))
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= at }
        saved.crash()
        return saved
    }

    private fun quarantinedBy(seed: Long, edit: (JsonObject) -> JsonObject): SessionHost.RestoreResult.Quarantined {
        val saved = tableAtCheckpoint(seed)
        saved.storage.editCheckpoint(at, edit)
        val before = saved.storage.snapshot()
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r, "$r")
        // Nothing repaired: every file is byte for byte as it was; only the quarantine marker is new.
        val after = saved.storage.snapshot()
        for ((name, bytes) in before) assertEquals(bytes, after[name], "$name was changed")
        assertEquals(setOf(TableFiles(TABLE).quarantine), after.keys - before.keys)
        // A later start stays quarantined.
        saved.crash()
        assertIs<SessionHost.RestoreResult.Quarantined>(saved.restore())
        return r
    }

    private fun JsonObject.deal() = getValue("deal").jsonObject
    private fun JsonObject.hand(seat: Seat) = deal().getValue("hands").jsonObject.getValue(seat.name).jsonArray
    private fun JsonObject.withDeal(edit: (JsonObject) -> JsonObject) = with("deal", edit(deal()))
    private fun JsonObject.withHand(seat: Seat, cards: List<String>) =
        withDeal { d -> d.with("hands", d.getValue("hands").jsonObject.with(seat.name, JsonArray(cards.sorted().map(::JsonPrimitive)))) }
    private fun JsonArray.ids() = map { it.jsonPrimitive.content }

    @Test fun badCardCountIsQuarantined() {
        val r = quarantinedBy(701) { s -> s.withHand(Seat.EAST, s.hand(Seat.EAST).ids().drop(1)) }
        assertEquals(QuarantineReason.VALIDATION_FAILED, r.reason)
        assertTrue("107" in r.detail, r.detail)
    }

    @Test fun duplicateCardIdIsQuarantined() {
        val r = quarantinedBy(702) { s -> s.withHand(Seat.WEST, s.hand(Seat.WEST).ids() + s.hand(Seat.EAST).ids().first()) }
        assertEquals(QuarantineReason.VALIDATION_FAILED, r.reason)
        assertTrue("held twice" in r.detail, r.detail)
    }

    @Test fun wrongTurnIsQuarantined() {
        // The seat to act holds no cards and has not finished: nobody could ever act again.
        val r = quarantinedBy(703) { s ->
            val turn = Seat.valueOf(s.deal().getValue("turn").jsonPrimitive.content)
            val moved = s.hand(turn).ids()
            s.withHand(turn, emptyList()).withDeal { d ->
                d.with("played", JsonArray((d.getValue("played").jsonArray.ids() + moved).sorted().map(::JsonPrimitive)))
            }
        }
        assertEquals(QuarantineReason.VALIDATION_FAILED, r.reason)
        assertTrue("no cards" in r.detail, r.detail)
    }

    @Test fun profileHashMismatchInTheStateIsQuarantined() {
        val r = quarantinedBy(704) { s -> s.with("baseProfileHash", str("0".repeat(64))) }
        assertEquals(QuarantineReason.PROFILE_MISMATCH, r.reason)
    }

    @Test fun aTableSavedUnderAnotherProfileIsQuarantined() {
        val saved = tableAtCheckpoint(705)
        val text = File("../rules/profiles/M1-match-v1.json").readText().replace("\"version\": \"1.0.0\"", "\"version\": \"1.0.99\"").also { check("1.0.99" in it) { "test edit did not apply" } }
        val other = RuleProfileLoader.parse(text, MatchOptions.registry).getOrThrow()
        check(other.hash != TestProfiles.match.hash)
        val r = SessionHost.restore(saved.config, TestProfiles.base, other, testCrypto(1), saved.t.scheduler, saved.storage, TABLE, T0)
        assertIs<SessionHost.RestoreResult.Quarantined>(r)
        assertEquals(QuarantineReason.PROFILE_MISMATCH, r.reason)
    }

    /** Without a checkpoint to compare, the table's own profile record (meta) still decides. */
    @Test fun aTableSavedUnderAnotherProfileIsQuarantinedEvenWithoutACheckpoint() {
        val saved = tableAtCheckpoint(708)
        val files = TableFiles(TABLE)
        saved.storage.list(files.dir).filter { files.checkpointRevision(it) != null }.forEach { saved.storage.delete(it) }
        val text = File("../rules/profiles/M1-match-v1.json").readText().replace("\"version\": \"1.0.0\"", "\"version\": \"1.0.99\"").also { check("1.0.99" in it) { "test edit did not apply" } }
        val other = RuleProfileLoader.parse(text, MatchOptions.registry).getOrThrow()
        val r = SessionHost.restore(saved.config, TestProfiles.base, other, testCrypto(1), saved.t.scheduler, saved.storage, TABLE, T0)
        assertIs<SessionHost.RestoreResult.Quarantined>(r)
        assertEquals(QuarantineReason.PROFILE_MISMATCH, r.reason, r.detail)
    }

    @Test fun aLogRecordThatDoesNotReplayIsQuarantined() {
        val saved = tableAtCheckpoint(706)
        val files = TableFiles(TABLE)
        saved.storage.list(files.dir).filter { files.checkpointRevision(it) != null }.forEach { saved.storage.delete(it) }
        // Re-checksummed, so it passes the frame check: the events claim a card the seat never held.
        val play = recordPayloads(saved.storage).last { "\"t\":\"played\"" in it.second.events.toString() }.first
        saved.storage.editRecord(play) { p -> p.copy(events = kotlinx.serialization.json.Json.parseToJsonElement(p.events.toString().replace(Regex("\"cardIds\":\\[\"[^\"]+\""), "\"cardIds\":[\"0-H-99\""))) }
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r, "$r")
        assertTrue(r.reason == QuarantineReason.REPLAY_FAILED || r.reason == QuarantineReason.LOG_INCONSISTENT, "${r.reason}: ${r.detail}")
    }

    @Test fun everyStateOfTwoPlayedDealsPassesTheValidator() {
        val saved = SavedTable(707)
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        var checked = 0
        saved.driveUntil { s ->
            val problems = RestoreValidator.problems(s, TestProfiles.base.hash, TestProfiles.match.hash, s.revision)
            assertEquals(emptyList(), problems, "revision ${s.revision}")
            assertEquals(s, MatchStateCodec.decode(MatchStateCodec.encode(s)))
            checked++
            s.dealNumber == 3
        }
        assertTrue(checked > 300, "checked $checked states")
    }

    @Test fun aStateWithoutADealMustBeWaitingForTheFirstDeal() {
        val fresh = cn.wpgd.app.core.match.MatchReducer(TestProfiles.base, TestProfiles.match).newMatch()
        assertEquals(emptyList(), RestoreValidator.problems(fresh, TestProfiles.base.hash, TestProfiles.match.hash, fresh.revision))
        val playing = MatchStateCodec.decode(JsonObject(MatchStateCodec.encode(fresh) + ("phase" to JsonPrimitive("PLAYING"))))
        val problems = RestoreValidator.problems(playing, TestProfiles.base.hash, TestProfiles.match.hash, playing.revision)
        assertTrue(problems.any { it.kind == RestoreValidator.Kind.PHASE }, "$problems")
    }
}
