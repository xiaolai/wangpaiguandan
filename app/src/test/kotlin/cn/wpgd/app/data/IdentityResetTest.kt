package cn.wpgd.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-008: resetting identity requires a confirmation that states rejoin access to current tables will be lost. */
class IdentityResetTest {
    @Test fun resetWithoutConfirmationChangesNothing() {
        val stores = LocalStores.inMemory()
        val identity = IdentityRepository(stores.identity)
        val id = identity.identity()
        identity.rememberRejoin("room-1", "cap-1", "K".repeat(43))
        assertEquals(IdentityResetResult.ConfirmationRequired, identity.reset(null))
        assertEquals(id, identity.identity())
        assertEquals("cap-1", identity.rejoinCapability("room-1"))
    }

    @Test fun thePromptStatesTheLossAndOnlyAConfirmationOfItResets() {
        val stores = LocalStores.inMemory()
        PreferencesRepository(stores.preferences).update { it.copy(vibration = false) }
        HistoryRepository(stores.history).append(HistoryEntry("solo-1", 1, 1, 2, true, 3, false, 5))
        NicknameRepository(stores.profile).set("阿梅")
        val identity = IdentityRepository(stores.identity)
        val id = identity.identity()
        identity.rememberRejoin("room-1", "cap-1", "K".repeat(43))
        identity.rememberRejoin("room-2", "cap-2", "K".repeat(43))

        val prompt = identity.prepareReset()
        assertEquals(ConfirmationStatement.REJOIN_ACCESS_TO_CURRENT_TABLES_WILL_BE_LOST, prompt.statement)
        assertEquals("identity.reset.rejoin_access_lost", prompt.statement.messageKey)
        assertEquals(2, prompt.tablesLosingRejoin)
        val confirmation = prompt.confirm()
        assertEquals(ConfirmationStatement.REJOIN_ACCESS_TO_CURRENT_TABLES_WILL_BE_LOST, confirmation.statement)

        // A table joined after the prompt was shown makes that confirmation outdated.
        identity.rememberRejoin("room-3", "cap-3", "K".repeat(43))
        assertEquals(IdentityResetResult.ConfirmationOutdated, identity.reset(confirmation))
        assertEquals(id, identity.identity())

        assertEquals(IdentityResetResult.Reset, identity.reset(identity.prepareReset().confirm()))
        assertTrue(identity.rejoinTables().isEmpty())
        assertNotEquals(id, identity.identity())
        // Other stores are untouched.
        assertEquals(false, PreferencesRepository(stores.preferences).preferences.value.vibration)
        assertEquals(1, HistoryRepository(stores.history).entries().size)
        assertEquals("阿梅", NicknameRepository(stores.profile).current()?.value)
    }

    @Test fun replacingATableWithAnotherOutdatesTheConfirmation() {
        val identity = IdentityRepository(LocalStores.inMemory().identity)
        identity.rememberRejoin("room-a", "cap-a", "K".repeat(43))
        val confirmation = identity.prepareReset().confirm()
        identity.forgetRejoin("room-a")
        identity.rememberRejoin("room-b", "cap-b", "K".repeat(43))
        assertEquals(IdentityResetResult.ConfirmationOutdated, identity.reset(confirmation))
        assertEquals("cap-b", identity.rejoinCapability("room-b"))
    }

    /** G2: a ticket keeps the host key it was issued by; a bare capability from before pinning is not used. */
    @Test fun aTicketCarriesItsHostKeyAndAnUnpinnedOldTicketIsIgnored() {
        val store = InMemoryKeyValueStore()
        val identity = IdentityRepository(store)
        identity.rememberRejoin("room-1", "cap-1", "K".repeat(43))
        assertEquals(RejoinTicket("cap-1", "K".repeat(43)), identity.rejoinTicket("room-1"))
        store.put("rejoin.room-2", "cap-old")
        assertEquals(null, identity.rejoinTicket("room-2"))
        assertEquals(null, identity.rejoinCapability("room-2"))
    }
}
