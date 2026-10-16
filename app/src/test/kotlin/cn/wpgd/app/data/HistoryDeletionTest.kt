package cn.wpgd.app.data

import cn.wpgd.app.table.HandSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-008: deleting history does not erase preferences (nor nickname or identity). */
class HistoryDeletionTest {
    private fun entry(deal: Int, table: String = "solo-ab12", match: Int = 1) = HistoryEntry(table, match, deal, 2 + deal, deal % 2 == 0, 2, false, 1_000L * deal)

    @Test fun deletingHistoryLeavesEveryOtherStoreIntact() {
        val stores = LocalStores.inMemory()
        val prefs = PreferencesRepository(stores.preferences)
        prefs.update { it.copy(sound = false, handSort = HandSort.SUIT, reducedMotion = true) }
        val nickname = NicknameRepository(stores.profile).also { it.set("老王") }
        val identity = IdentityRepository(stores.identity)
        val id = identity.identity()
        identity.rememberRejoin("room-1", "cap", "K".repeat(43))
        val history = HistoryRepository(stores.history)
        (1..5).forEach { history.append(entry(it)) }
        assertEquals(5, history.entries().size)

        history.deleteAll()

        assertEquals(emptyList<HistoryEntry>(), history.entries())
        assertEquals(emptyList<HistoryEntry>(), HistoryRepository(stores.history).entries())
        val reread = PreferencesRepository(stores.preferences).preferences.value
        assertEquals(TablePreferences(sound = false, handSort = HandSort.SUIT, reducedMotion = true), reread)
        assertEquals("老王", nickname.current()?.value)
        assertEquals(id, IdentityRepository(stores.identity).identity())
        assertEquals(setOf("room-1"), identity.rejoinTables())
    }

    @Test fun historyRoundTripsIsBoundedAndSkipsUnreadableEntries() {
        val store = InMemoryKeyValueStore()
        val history = HistoryRepository(store)
        history.append(entry(1))
        history.append(entry(1)) // the same deal of the same table is kept once
        history.append(entry(2, table = "solo-cd34").copy(ownTeamWon = null))
        assertEquals(listOf(entry(1), entry(2, table = "solo-cd34").copy(ownTeamWon = null)), HistoryRepository(store).entries())
        store.put("deal.999", "v1|bad")
        assertEquals(2, history.entries().size)
        assertEquals(1, history.unreadable)
        repeat(HistoryRepository.MAX_ENTRIES + 10) { history.append(entry(100 + it)) }
        assertTrue(history.entries().size <= HistoryRepository.MAX_ENTRIES + 1)
        assertEquals(100 + HistoryRepository.MAX_ENTRIES + 9, history.entries().last().dealNumber)
    }

    @Test fun aRematchKeepsItsDealsAlthoughTheirNumbersRepeat() {
        val history = HistoryRepository(InMemoryKeyValueStore())
        (1..6).forEach { history.append(entry(it)) }
        history.append(entry(6)) // seen again: kept once
        // A rematch at the same table numbers its deals from 1 again, under the next match number.
        history.append(entry(1, match = 2).copy(settledAtMillis = 99_000))
        history.append(entry(2, match = 2).copy(settledAtMillis = 100_000))
        history.append(entry(2, match = 2).copy(settledAtMillis = 100_000))
        // Deal 1 of the first match seen again (a player that rejoins late): still kept once.
        history.append(entry(1))
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to 6, 2 to 1, 2 to 2), history.entries().map { it.matchNumber to it.dealNumber })
    }

    /** History format v1 (before match numbers) still reads, with no match number rather than a guessed one. */
    @Test fun aV1EntryReadsWithoutAMatchNumber() {
        val store = InMemoryKeyValueStore()
        store.put("deal.0", "v1|solo-ab12|3|5|true|2|false|3000")
        store.put("next", "1")
        val history = HistoryRepository(store)
        assertEquals(listOf(HistoryEntry("solo-ab12", null, 3, 5, true, 2, false, 3000)), history.entries())
        history.append(entry(3))
        assertEquals("match 1 deal 3 is not the unnumbered old entry", listOf(null to 3, 1 to 3), history.entries().map { it.matchNumber to it.dealNumber })
        store.put("deal.9", "v2|solo-ab12|-|3|5|true|2|false|3000")
        assertEquals("a v2 entry must carry its match number", 1, history.entries().size.let { history.unreadable })
    }

    @Test fun storesMustBeSeparate() {
        val one = InMemoryKeyValueStore()
        val failed = runCatching { LocalStores(one, one, InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore()) }
        assertTrue(failed.isFailure)
    }
}
