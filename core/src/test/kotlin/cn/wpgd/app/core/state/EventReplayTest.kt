package cn.wpgd.app.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventReplayTest {
    @Test fun everyAcceptedActionReplaysFromTypedEventsAlone() {
        for (seed in 1L..30L) {
            val log = EventLog()
            // Replay starts from a fresh state made by a different reducer instance: nothing but the events carries over.
            val fresh = p1Reducer().newDeal(2)
            var replays = 0
            val final = playDeal(p1Reducer(), seed) { step ->
                log.append(step.events)
                assertEquals(StateHash.of(step.after), StateHash.of(log.replay(fresh)), "seed $seed revision ${step.after.revision}")
                replays++
            }
            assertEquals(StateHash.of(final), StateHash.of(log.replay(fresh)))
            assertEquals(final.revision.toInt(), replays)
        }
    }

    @Test fun eachEventsBatchAloneTakesTheStateForward() {
        playDeal(p1Reducer(), 11) { step ->
            assertEquals(step.after, DealEvents.replay(step.before, step.events))
        }
    }

    @Test fun eventsCarryOnlyTypedDataAndTheCurrentSchema() {
        val seen = HashSet<Class<*>>()
        playDeal(p1Reducer(), 3) { step ->
            for (e in step.events) {
                assertEquals(EVENT_SCHEMA_VERSION, e.schemaVersion)
                seen += e.javaClass
            }
        }
        // No free text: the only String-typed fields are ids and the profile hash.
        for (c in seen) for (f in c.declaredFields) {
            if (f.type == String::class.java) assertTrue(f.name in setOf("profileHash"), "${c.simpleName}.${f.name} is free text")
        }
        assertTrue(seen.size >= 6, "deal 3 exercised only ${seen.map { it.simpleName }}")
    }

    @Test fun replayRejectsAnUnknownSchemaVersion() {
        val opened = p1Reducer().started(1)
        val forged = (opened.events.first() as DealEvent.DealOpened).copy(schemaVersion = EVENT_SCHEMA_VERSION + 1)
        assertFailsWith<IllegalArgumentException> { DealEvents.replay(p1Reducer().newDeal(2), listOf(forged)) }
    }

    /** A dealt hand is 27 distinct deck cards that no other seat holds; anything else is a corrupt log. */
    @Test fun replayRejectsAMalformedDealtHand() {
        val opened = p1Reducer().started(1)
        val dealOpened = opened.events.first() as DealEvent.DealOpened
        val hands = opened.events.filterIsInstance<DealEvent.HandDealt>()
        val fresh = p1Reducer().newDeal(2)
        DealEvents.replay(fresh, listOf(dealOpened) + hands) // the genuine log replays
        val south = hands.first { it.seat == Seat.SOUTH }
        val forged = listOf(
            south.copy(cardIds = listOf("BOGUS", "BOGUS")),
            south.copy(cardIds = south.cardIds.dropLast(1) + south.cardIds.first()),
            south.copy(cardIds = south.cardIds.dropLast(1) + "BOGUS"),
            south.copy(cardIds = south.cardIds.dropLast(1)),
        )
        for (bad in forged) assertFailsWith<IllegalArgumentException>("${bad.cardIds.takeLast(2)}") { DealEvents.replay(fresh, listOf(dealOpened, bad)) }
        // East's hand overlapping South's (same size, valid ids) is refused too.
        val east = hands.first { it.seat == Seat.EAST }.copy(cardIds = south.cardIds)
        assertFailsWith<IllegalArgumentException> { DealEvents.replay(fresh, listOf(dealOpened, south, east)) }
    }

    /** D21: a deal ends only once its end condition holds, naming exactly the seats still holding cards. */
    @Test fun replayRejectsAnEarlyOrMislabelledDealEnd() {
        val opened = p1Reducer().started(1)
        val rev = opened.state.revision + 1
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(opened.state, DealEvent.DealEnded(rev, emptyList(), emptyList())) }
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(opened.state, DealEvent.DealEnded(rev, emptyList(), Seat.entries)) }
        val doubleUp = opened.state.copy(finishOrder = listOf(Seat.NORTH, Seat.SOUTH))
        assertEquals(Phase.ENDED, DealEvents.apply(doubleUp, DealEvent.DealEnded(rev, doubleUp.finishOrder, listOf(Seat.EAST, Seat.WEST))).phase)
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(doubleUp, DealEvent.DealEnded(rev, doubleUp.finishOrder, listOf(Seat.EAST))) }
    }

    @Test fun logRefusesAGapOrARepeatedRevision() {
        val r = p1Reducer()
        val opened = r.started(1)
        val log = EventLog()
        log.append(opened.events)
        assertFailsWith<IllegalArgumentException> { log.append(opened.events) }
        val next = r.reduce(opened.state, SeededBot(1).choose(r, opened.state)).accepted()
        val skipped = r.reduce(next.state, SeededBot(2).choose(r, next.state)).accepted()
        assertFailsWith<IllegalArgumentException> { log.append(skipped.events) }
        log.append(next.events)
        assertEquals(next.state, log.replay(r.newDeal(2)))
    }

    /** Logged history is the log's own: neither the appender's lists nor a reader can change it. */
    @Test fun logDoesNotAliasTheEventsLists() {
        val r = p1Reducer()
        val opened = r.started(1)
        val dealt = opened.events.filterIsInstance<DealEvent.HandDealt>().first()
        val ids = dealt.cardIds.toMutableList()
        val log = EventLog()
        log.append(opened.events.map { if (it === dealt) dealt.copy(cardIds = ids) else it })
        ids.clear()
        assertEquals(opened.state, log.replay(r.newDeal(2)))
        val logged = log.events.filterIsInstance<DealEvent.HandDealt>().first().cardIds
        // Not mutable even through a cast (the cast itself, or the mutation, fails).
        kotlin.test.assertFails { (logged as MutableList<String>).clear() }
        assertEquals(dealt.cardIds, logged)
        assertEquals(opened.state, log.replay(r.newDeal(2)))
    }

    /** #47, round 2: a play's combination is logged history too; its lists are not the caller's. */
    @Test fun logDoesNotAliasAPlaysCombination() {
        val r = p1Reducer()
        val opened = r.started(1)
        val step = r.reduce(opened.state, SeededBot(1).choose(r, opened.state)).accepted()
        val played = step.events.filterIsInstance<DealEvent.Played>().single()
        val log = EventLog()
        log.append(opened.events)
        log.append(step.events)
        val before = played.combination.resolvedRanks.toList()
        // The appender keeps its event and changes the combination's lists through a cast.
        val callerRanks = played.combination.resolvedRanks
        if (callerRanks is MutableList<Int>) runCatching { callerRanks[0] = 99 }
        val logged = log.events.filterIsInstance<DealEvent.Played>().single().combination
        assertEquals(before, logged.resolvedRanks, "the appender changed logged history")
        kotlin.test.assertFails { (logged.resolvedRanks as MutableList<Int>)[0] = 98 }
        kotlin.test.assertFails { (logged.sequence as MutableList<Int>).add(98) }
        assertEquals(before, log.events.filterIsInstance<DealEvent.Played>().single().combination.resolvedRanks, "a reader changed logged history")
    }
}
