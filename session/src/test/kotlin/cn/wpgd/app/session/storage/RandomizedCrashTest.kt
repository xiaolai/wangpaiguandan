package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Bot
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.RoomStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * 200 seeded matches in progress: SOUTH is a human client (commands with ids), the other seats are
 * AI. The process dies at a random storage step of the next ~60 revisions (record, marker, high-water
 * mark or checkpoint; before or after it; unsynced bytes dropped, kept or torn). The host restores,
 * SOUTH rejoins and retries its last command, and the deal is played to its end. Invariants:
 * - never below the high-water mark, nor below any revision a member holds;
 * - no command id applied twice (checked in the persisted log itself);
 * - 108 cards conserved, the restore validator passes, and a final restart reproduces the live state.
 */
class RandomizedCrashTest {
    @Test fun twoHundredSeededCrashesKeepEveryInvariant() {
        val mark = TimeSource.Monotonic.markNow()
        val kills = HashMap<String, Int>()
        var crashed = 0
        var roundTrips = 0
        var revisions = 0L
        for (seed in 1L..SEEDS) {
            val rng = Random(seed * 31 + 7)
            val saved = SavedTable(seed, config = savedConfig(checkpointEvery = 5 + rng.nextInt(20)))
            Seat.entries.filter { it != Seat.SOUTH }.forEach { saved.host.setAi(it, standIn) }
            var south = saved.t.player(Seat.SOUTH)
            val bot = Bot(saved.t, south)
            saved.host.advance()
            saved.host.advance()
            bot.poke()

            val storage = saved.storage
            storage.torn = InMemoryStorage.Torn.entries[rng.nextInt(3)]
            val k = storage.step + 1 + rng.nextInt(7 * 60)
            if (rng.nextBoolean()) storage.killAfter = k else storage.killBefore = k

            val crash = runCatchingCrash { saved.driveUntil { it.phase == MatchPhase.DEAL_ENDED } }
            if (crash != null) {
                crashed++
                val (op, after) = checkNotNull(storage.crashedAt)
                val key = "${op.kind}/${op.name.substringAfterLast('/').substringBefore('.')}/${if (after) "after" else "before"}"
                kills[key] = (kills[key] ?: 0) + 1
                val liveRevision = saved.host.revision
                val held = south.client.heldRevision
                saved.crash()
                val hwm = checkNotNull(readHwm(saved.storage))
                val r = saved.restored()
                val restored = r.host.revision
                assertTrue(restored >= hwm, "seed $seed: restored $restored below the high-water mark $hwm")
                assertTrue(restored >= held, "seed $seed: restored $restored below revision $held held by a member")
                assertTrue(restored == liveRevision || restored == liveRevision + 1, "seed $seed: restored $restored, live was $liveRevision")
                assertEquals(RoomStatus.Active, r.host.status, "seed $seed")

                south = saved.rejoin(south)
                bot.gameCommands.lastOrNull()?.let { south.client.resend(it) }
                saved.t.run(300)
                bot.poke()
                saved.driveUntil { it.phase == MatchPhase.DEAL_ENDED }
            }
            val live = saved.host.state
            revisions += live.revision
            assertCardsConserved(live)
            assertEquals(emptyList(), RestoreValidator.problems(live, TestProfiles.base.hash, TestProfiles.match.hash, live.revision), "seed $seed")
            val ids = committedCommandIds(saved.storage.fork())
            assertEquals(ids.size, ids.toSet().size, "seed $seed: a command id was applied twice")
            assertTrue(ids.isNotEmpty(), "seed $seed: the human's commands were logged")

            // A clean restart reproduces the live state from the log.
            val liveHash = MatchStateCodec.hash(live)
            saved.storage.torn = InMemoryStorage.Torn.DROP_UNSYNCED
            saved.crash()
            assertEquals(liveHash, MatchStateCodec.hash(saved.restored().host.state), "seed $seed: restart reproduces the live state")
            roundTrips++
        }
        val elapsed = mark.elapsedNow()
        println("RandomizedCrash: $SEEDS seeds, $crashed crashed mid-deal, $roundTrips final restarts, $revisions revisions, wall $elapsed")
        println("RandomizedCrash kill points: ${kills.toSortedMap()}")
        assertTrue(crashed >= SEEDS * 9 / 10, "most seeds crash before the deal ends ($crashed)")
        assertTrue(kills.keys.any { it.startsWith("WRITE/log") } && kills.keys.any { it.startsWith("SYNC/log") } &&
            kills.keys.any { "hwm" in it } && kills.keys.any { "checkpoint" in it }, "kill points cover record, marker, high-water mark and checkpoint: ${kills.keys}")
        // Hang guard only (generous): the measured time is printed above.
        assertTrue(elapsed.inWholeMinutes < 20, "hang guard: $elapsed")
    }

    private companion object {
        const val SEEDS = 200L
    }
}
