package cn.wpgd.app.ble

import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.AppRule
import cn.wpgd.app.BuildConfig
import cn.wpgd.app.MainActivity
import cn.wpgd.app.appGraph
import cn.wpgd.app.exists
import cn.wpgd.app.startSoloFromMenu
import cn.wpgd.app.ui.BluetoothAccess
import cn.wpgd.app.ui.BluetoothGateRoute
import cn.wpgd.app.ui.BluetoothGateScreen
import cn.wpgd.app.ui.MainMenu
import cn.wpgd.app.ui.WpgdTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-004 on screen (WI-10): the denied, permanently denied and radio-off states each show their fix
 * and the way out (same Wi-Fi, solo play); the gate continues by itself once ready; the menu shows the
 * Bluetooth entry only with the flag on. Solo play needs no permission: it starts with none granted.
 */
@RunWith(AndroidJUnit4::class)
class PermissionStateTest {
    @get:Rule val rule = createComposeRule()

    private val ready = BleEnvironment(sdkInt = 34, hasBle = true, missing = emptySet(), rationale = false, askedBefore = true, radioOn = true, canAdvertise = true)

    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(state: BleGateState, purpose: BlePurpose = BlePurpose.HOST): MutableList<BleGateAction> {
        val clicked = ArrayList<BleGateAction>()
        rule.setContent { WpgdTheme { BluetoothGateScreen(state, purpose, onAction = { clicked += it }, onBack = {}) } }
        return clicked
    }

    private fun click(action: BleGateAction) = rule.onNodeWithTag("gate:action:${action.name}").performScrollTo().performClick()

    @Test fun deniedOffersTryAgainWifiAndSolo() {
        val clicked = show(BleGateState.DENIED)
        assertTrue(has("gate:DENIED") && has("gate:message"))
        for (a in listOf(BleGateAction.REQUEST_PERMISSION, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO)) click(a)
        assertEquals(listOf(BleGateAction.REQUEST_PERMISSION, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO), clicked)
    }

    @Test fun permanentlyDeniedOffersTheAppSettingsWifiAndSolo() {
        val clicked = show(BleGateState.PERMANENTLY_DENIED)
        assertFalse("the system will not prompt again: no retry", has("gate:action:REQUEST_PERMISSION"))
        for (a in listOf(BleGateAction.OPEN_APP_SETTINGS, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO)) click(a)
        assertEquals(listOf(BleGateAction.OPEN_APP_SETTINGS, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO), clicked)
    }

    @Test fun radioOffOffersToSwitchItOnWifiAndSolo() {
        val clicked = show(BleGateState.RADIO_OFF, BlePurpose.JOIN)
        for (a in listOf(BleGateAction.ENABLE_RADIO, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO)) click(a)
        assertEquals(listOf(BleGateAction.ENABLE_RADIO, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO), clicked)
    }

    @Test fun everyStateShortOfReadyHasAWayOut() {
        var state by mutableStateOf(BleGateState.NEEDS_PERMISSION)
        rule.setContent { WpgdTheme { BluetoothGateScreen(state, BlePurpose.HOST, onAction = {}, onBack = {}) } }
        for (s in BleGateState.entries - BleGateState.READY) {
            state = s
            rule.waitForIdle()
            assertTrue("$s screen", has("gate:${s.name}"))
            for (a in BleGate.actions(s)) assertTrue("$s offers $a", has("gate:action:${a.name}"))
            assertTrue("$s offers Wi-Fi", has("gate:action:USE_WIFI"))
            assertTrue("$s offers solo", has("gate:action:PLAY_SOLO"))
        }
    }

    @Test fun theGateContinuesByItselfOnceTheRadioIsOn() {
        var env = ready.copy(radioOn = false, canAdvertise = null)
        var readyCalls = 0
        var prompts = 0
        val access = object : BluetoothAccess {
            override fun environment() = env
            override fun requestPermissions() { prompts++ }
            override fun openAppSettings() = Unit
            override fun enableRadio() { env = ready }
        }
        rule.setContent { WpgdTheme { BluetoothGateRoute(access, BlePurpose.HOST, onReady = { readyCalls++ }, onUseWifi = {}, onSolo = {}, onBack = {}) } }
        rule.waitUntil(5_000) { has("gate:RADIO_OFF") }
        assertEquals(0, readyCalls)
        click(BleGateAction.ENABLE_RADIO)
        rule.waitUntil(5_000) { readyCalls == 1 }
        assertEquals("no permission prompt when already granted", 0, prompts)
    }

    @Test fun theMenuShowsTheBluetoothEntryOnlyWhenTheFlagIsOn() {
        var bluetooth by mutableStateOf<(() -> Unit)?>(null)
        rule.setContent { WpgdTheme { MainMenu(tableOpen = false, onSolo = {}, onSettings = {}, onBluetooth = bluetooth) } }
        assertFalse(has("menu:bluetooth"))
        bluetooth = {}
        rule.waitForIdle()
        assertTrue(has("menu:bluetooth"))
    }
}

/** The real app: the entry follows the build flag, and solo play starts with no Bluetooth permission granted. */
@RunWith(AndroidJUnit4::class)
class SoloNeedsNoBluetoothPermissionTest {
    @get:Rule val rule: AppRule = createAndroidComposeRule<MainActivity>()

    @After fun closeTable() = appGraph.closeTable()

    @Test fun soloStartsWithoutAnyBluetoothPermissionAndTheEntryFollowsTheFlag() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (p in BlePermissions.RUNTIME) assertEquals("$p is not granted", PackageManager.PERMISSION_DENIED, ctx.checkSelfPermission(p))
        appGraph.closeTable()
        rule.waitUntil(10_000) { rule.exists("menu:solo") }
        assertEquals(BuildConfig.BLE_TRANSPORT, rule.exists("menu:bluetooth"))
        assertEquals(BuildConfig.BLE_TRANSPORT, appGraph.bluetoothEnabled)
        rule.startSoloFromMenu()
        for (p in BlePermissions.RUNTIME) assertEquals(PackageManager.PERMISSION_DENIED, ctx.checkSelfPermission(p))
    }
}
