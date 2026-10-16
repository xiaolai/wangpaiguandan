package cn.wpgd.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-004 (logic half of `PermissionStateTest`): which Bluetooth screen a user sees, and that every
 * screen short of ready can be acted on — fix it, use the same Wi-Fi, or play solo.
 */
class BleGateTest {
    private val ready = BleEnvironment(sdkInt = 34, hasBle = true, missing = emptySet(), rationale = false, askedBefore = true, radioOn = true, canAdvertise = true)

    private fun state(env: BleEnvironment, purpose: BlePurpose = BlePurpose.HOST) = BleGate.state(env, purpose)

    @Test fun grantedWithTheRadioOnIsReady() {
        assertEquals(BleGateState.READY, state(ready))
        assertEquals(BleGateState.READY, state(ready, BlePurpose.JOIN))
        assertEquals(emptyList<BleGateAction>(), BleGate.actions(BleGateState.READY))
    }

    @Test fun androidBefore12IsNotOfferedBecauseScanningWouldNeedLocation() {
        for (sdk in 26..30) assertEquals(BleGateState.UNSUPPORTED_ANDROID, state(ready.copy(sdkInt = sdk)))
        assertEquals(BleGateState.READY, state(ready.copy(sdkInt = 31)))
    }

    @Test fun noBluetoothHardware() {
        assertEquals(BleGateState.NO_BLUETOOTH, state(ready.copy(hasBle = false)))
    }

    @Test fun neverAskedExplainsFirst() {
        val env = ready.copy(missing = BlePermissions.RUNTIME, askedBefore = false)
        assertEquals(BleGateState.NEEDS_PERMISSION, state(env))
        assertEquals(BleGateAction.REQUEST_PERMISSION, BleGate.actions(BleGateState.NEEDS_PERMISSION).first())
    }

    @Test fun deniedOnceCanAskAgain() {
        val env = ready.copy(missing = BlePermissions.RUNTIME, rationale = true)
        assertEquals(BleGateState.DENIED, state(env))
        assertEquals(BleGateAction.REQUEST_PERMISSION, BleGate.actions(BleGateState.DENIED).first())
    }

    @Test fun deniedForGoodSendsToTheAppSettings() {
        val env = ready.copy(missing = setOf(BlePermissions.CONNECT), rationale = false)
        assertEquals(BleGateState.PERMANENTLY_DENIED, state(env))
        assertEquals(BleGateAction.OPEN_APP_SETTINGS, BleGate.actions(BleGateState.PERMANENTLY_DENIED).first())
    }

    @Test fun anyMissingPermissionBlocksBothPurposes() {
        for (p in BlePermissions.RUNTIME) {
            val env = ready.copy(missing = setOf(p), rationale = true)
            assertEquals(BleGateState.DENIED, state(env, BlePurpose.HOST))
            assertEquals(BleGateState.DENIED, state(env, BlePurpose.JOIN))
        }
    }

    @Test fun radioOffIsItsOwnStateAfterThePermission() {
        assertEquals(BleGateState.RADIO_OFF, state(ready.copy(radioOn = false, canAdvertise = null)))
        // Permission comes first: turning the radio on from the app needs BLUETOOTH_CONNECT.
        assertEquals(BleGateState.NEEDS_PERMISSION, state(ready.copy(radioOn = false, missing = BlePermissions.RUNTIME, askedBefore = false)))
        assertEquals(BleGateAction.ENABLE_RADIO, BleGate.actions(BleGateState.RADIO_OFF).first())
    }

    @Test fun aRadioThatCannotAdvertiseCanJoinButNotHost() {
        val env = ready.copy(canAdvertise = false)
        assertEquals(BleGateState.CANNOT_HOST, state(env, BlePurpose.HOST))
        assertEquals(BleGateState.READY, state(env, BlePurpose.JOIN))
    }

    @Test fun everyStateShortOfReadyIsActionableAndOffersWifiAndSolo() {
        for (s in BleGateState.entries - BleGateState.READY) {
            val actions = BleGate.actions(s)
            assertTrue("$s offers the same Wi-Fi", BleGateAction.USE_WIFI in actions)
            assertTrue("$s offers solo play", BleGateAction.PLAY_SOLO in actions)
            assertEquals("$s lists each action once", actions.distinct(), actions)
        }
        // The three states the plan names each have a fix of their own, offered first.
        assertEquals(BleGateAction.REQUEST_PERMISSION, BleGate.actions(BleGateState.DENIED)[0])
        assertEquals(BleGateAction.OPEN_APP_SETTINGS, BleGate.actions(BleGateState.PERMANENTLY_DENIED)[0])
        assertEquals(BleGateAction.ENABLE_RADIO, BleGate.actions(BleGateState.RADIO_OFF)[0])
    }

    @Test fun theRuntimePermissionsAreExactlyTheThreeAndroid12Ones() {
        assertEquals(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"),
            BlePermissions.RUNTIME,
        )
        assertTrue(runCatching { ready.copy(missing = setOf("android.permission.ACCESS_FINE_LOCATION")) }.isFailure)
    }
}
