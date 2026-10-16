package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.untilMyPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-081: changing settings at the table does not disconnect, advance a turn or change the revision. */
class InTableSettingsTest {
    @Test fun settingsChangeWithoutTouchingTheSession() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        val revision = solo.table!!.revision
        val epoch = solo.table!!.epoch
        val turn = vm.uiState.value.turn
        val sent = solo.owner.metrics.commandsSent.get()
        val selected = vm.uiState.value.hand.rows.first().first().cards.first().id
        vm.tap(selected)

        // Open settings (the table leaves the screen), change everything, come back.
        vm.setTableVisible(false)
        vm.updatePreferences { it.copy(sound = false, vibration = false, reducedMotion = true, handSort = HandSort.SUIT, largeCards = true) }
        solo.run(3_000)
        vm.updatePreferences { it.copy(handSort = HandSort.COMBINATION) }
        vm.setTableVisible(true)
        solo.run(3_000)

        val ui = vm.uiState.value
        assertEquals(revision, solo.table!!.revision)
        assertEquals(epoch, solo.table!!.epoch)
        assertEquals(ConnectionState.CONNECTED, ui.connection)
        assertEquals("no reconnect happened", 1L, solo.owner.metrics.connections.get())
        assertEquals("no command was sent", sent, solo.owner.metrics.commandsSent.get())
        assertEquals(turn, ui.turn)
        assertTrue(ui.controlsEnabled)
        assertEquals(listOf(selected), ui.hand.selectedIds)
        assertEquals(HandSort.COMBINATION, ui.hand.sort)
        assertFalse(ui.preferences.sound)
        assertEquals(MotionMode.REDUCED, ui.motion)
        // Persisted: a new repository over the same store reads them back.
        assertEquals(ui.preferences, PreferencesRepository(solo.preferenceStore).preferences.value)
    }
}
