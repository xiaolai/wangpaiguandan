package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.CombinationType
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReducerTest {
    @Test fun sameSeedProfileAndCommandsGiveSameStateHash() {
        for (seed in listOf(1L, 2L, 77L, 20261014L)) {
            val first = ArrayList<String>()
            val second = ArrayList<String>()
            playDeal(p1Reducer(), seed) { first += StateHash.of(it.after) }
            playDeal(p1Reducer(), seed) { second += StateHash.of(it.after) }
            assertTrue(first.size > 4, "deal $seed too short to compare")
            assertEquals(first, second, "seed $seed")
        }
    }

    /** The committed play and its event keep their own copy of the declaration: the caller's list is not aliased. */
    @Test fun anAcceptedDeclarationIsNotAliasedToTheCallersList() {
        val state = playingState(mapOf(Seat.SOUTH to "0S6 0D6 0C6 0H2 0S9", Seat.EAST to "0S3", Seat.NORTH to "0S4", Seat.WEST to "0S5"), Seat.SOUTH)
        val declaration = arrayListOf(cn.wpgd.app.core.rules.WildcardAssignment(6))
        val accepted = p1Reducer().reduce(state, Command.Play(Seat.SOUTH, listOf("0S6", "0D6", "0C6", "0H2"), declaration)).accepted()
        declaration[0] = cn.wpgd.app.core.rules.WildcardAssignment(9)
        assertEquals(listOf(cn.wpgd.app.core.rules.WildcardAssignment(6)), accepted.state.trick.top!!.declaration)
        assertEquals(listOf(cn.wpgd.app.core.rules.WildcardAssignment(6)), accepted.events.filterIsInstance<DealEvent.Played>().single().declaration)
    }

    @Test fun differentSeedGivesDifferentHash() {
        val r = p1Reducer()
        val hashes = (1L..50L).map { StateHash.of(r.started(it).state) }
        assertEquals(hashes.size, hashes.toSet().size, "two seeds dealt identical hands")
        assertNotEquals(StateHash.of(r.started(1).state), StateHash.of(r.started(2).state))
    }

    @Test fun startDealsTwentySevenCardsEachAndOpensWithTheGivenLeader() {
        for (leader in Seat.entries) {
            val s = p1Reducer().started(seed = 9, leader = leader).state
            assertEquals(Phase.PLAYING, s.phase)
            assertEquals(Seat.entries.toSet(), s.hands.keys)
            Seat.entries.forEach { assertEquals(27, s.hands.getValue(it).size, "$it") }
            assertEquals(StandardDeck.ids, s.hands.values.flatten().toSet())
            assertEquals(leader, s.turn)
            assertEquals(leader, s.leader)
            assertEquals(1, s.revision)
        }
    }

    /** Cross-platform contract: SplitMix64 + Fisher–Yates + round-robin deal, values from an independent Python reference. */
    @Test fun dealMatchesIndependentReference() {
        val s = p1Reducer().started(seed = 20261014).state
        assertEquals(
            "0C2 0C9 0CA 0CQ 0D2 0D8 0H6 0H8 0HA 0HK 0S2 0S5 0S7 1C3 1CA 1CK 1D3 1D7 1D9 1DA 1DJ 1H2 1H3 1H7 1LJ 1S10 1S4",
            s.hands.getValue(Seat.SOUTH).sorted().joinToString(" "),
        )
        val canon = Seat.entries.joinToString("\n") { "$it:${s.hands.getValue(it).sorted().joinToString(",")}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("dab95d28c1f1aae4500be883528914a92b587089e8d00cd789853ff3733dada5", digest)
    }

    @Test fun splitMix64MatchesReferenceVectors() {
        val zero = SplitMix64(0)
        assertEquals(listOf(-2152535657050944081L, 7960286522194355700L, 487617019471545679L), List(3) { zero.nextLong() })
        val minusOne = SplitMix64(-1)
        assertEquals(listOf(-1956407806741107680L, -1612297016619662647L), List(2) { minusOne.nextLong() })
        val bounded = SplitMix64(42)
        assertEquals(listOf(90, 9, 9, 54, 53), List(5) { bounded.nextInt(108) })
    }

    @Test fun everyAcceptedCommandAdvancesRevisionByOne() {
        playDeal(p1Reducer(), 5) { step ->
            assertEquals(step.before.revision + 1, step.after.revision)
            assertTrue(step.events.isNotEmpty())
            assertTrue(step.events.all { it.revision == step.after.revision })
        }
    }

    /** D23: a variant where five same-suit cards read as straight flush or straight; the command must pick one. */
    @Test fun ambiguousReadingNeedsACombinationType() {
        val variant = cn.wpgd.app.core.rules.Profiles.with("straightFlush.playableAsStraight" to "true")
        val r = DealReducer(variant)
        val s = playingState(mapOf(Seat.SOUTH to "0S3 0S4 0S5 0S6 0S7 0D9", Seat.EAST to "0D3", Seat.NORTH to "0C3", Seat.WEST to "0H3"), Seat.SOUTH, profile = variant)
        val run = "0S3 0S4 0S5 0S6 0S7"
        val types = r.reduce(s, play(Seat.SOUTH, run)).rejectedWith<Rejection.AmbiguousReading>().types
        assertEquals(setOf(CombinationType.STRAIGHT, CombinationType.STRAIGHT_FLUSH), types.toSet())
        val straight = r.reduce(s, play(Seat.SOUTH, run).copy(combinationType = CombinationType.STRAIGHT)).accepted()
        assertEquals(CombinationType.STRAIGHT, straight.state.trick.top?.combination?.type)
        assertEquals(
            CombinationType.PAIR,
            r.reduce(s, play(Seat.SOUTH, run).copy(combinationType = CombinationType.PAIR)).rejectedWith<Rejection.ReadingNotAvailable>().type,
        )
    }

    @Test fun reducerRefusesAStateFromAnotherProfile() {
        val other = cn.wpgd.app.core.rules.Profiles.with("bomb.maxSize" to "8")
        val state = DealReducer(other).newDeal(2)
        val failure = runCatching { p1Reducer().reduce(state, Command.Start(SplitMix64(1), Seat.SOUTH)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a loud failure, got $failure")
    }
}
