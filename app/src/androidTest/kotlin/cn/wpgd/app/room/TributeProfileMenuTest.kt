package cn.wpgd.app.room

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.ui.BluetoothHubScreen
import cn.wpgd.app.ui.MainMenu
import cn.wpgd.app.ui.WpgdTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `tributeProfile` switch on screen (plan section 3, WI-04 rollback): off, the menu offers no
 * new table (no solo play, no hosting, over Wi-Fi or Bluetooth) and says why; joining, saved
 * tables and an open table stay reachable. The owner refuses as well (`TributeProfileSwitchTest`).
 */
@RunWith(AndroidJUnit4::class)
class TributeProfileMenuTest {
    @get:Rule val rule = createComposeRule()

    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun switchedOffTheMenuOffersNoNewTable() {
        rule.setContent { WpgdTheme { MainMenu(tableOpen = false, onSolo = {}, onSettings = {}, onBluetooth = {}, newTables = false) } }
        assertFalse(has("menu:solo"))
        assertFalse(has("menu:host"))
        assertTrue(has("menu:new_tables_off"))
        assertTrue(has("menu:join"))
        assertTrue(has("menu:saved"))
        assertTrue(has("menu:bluetooth"))
    }

    @Test fun switchedOffAnOpenTableCanStillBeContinued() {
        rule.setContent { WpgdTheme { MainMenu(tableOpen = true, onSolo = {}, onSettings = {}, newTables = false) } }
        assertTrue(has("menu:continue"))
        assertFalse(has("menu:new_tables_off"))
    }

    @Test fun switchedOnTheMenuIsUnchanged() {
        rule.setContent { WpgdTheme { MainMenu(tableOpen = false, onSolo = {}, onSettings = {}, newTables = true) } }
        assertTrue(has("menu:solo"))
        assertTrue(has("menu:host"))
        assertFalse(has("menu:new_tables_off"))
    }

    @Test fun switchedOffBluetoothOffersJoiningOnly() {
        rule.setContent { WpgdTheme { BluetoothHubScreen(onHost = {}, onJoin = {}, onBack = {}, newTables = false) } }
        assertFalse(has("bt:host"))
        assertTrue(has("bt:join"))
    }
}
