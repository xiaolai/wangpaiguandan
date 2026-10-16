package cn.wpgd.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.wpgd.app.R
import cn.wpgd.app.ble.BleEnvironment
import cn.wpgd.app.ble.BleGate
import cn.wpgd.app.ble.BleGateAction
import cn.wpgd.app.ble.BleGateState
import cn.wpgd.app.ble.BlePurpose
import cn.wpgd.app.room.Features
import kotlinx.coroutines.delay

/**
 * WI-10: what the Bluetooth entry needs from the platform (Android: the activity). Absent when the
 * build's `bleTransport` flag is off, and then no Bluetooth entry exists.
 */
interface BluetoothAccess {
    fun environment(): BleEnvironment

    /** Shows the system "Nearby devices" prompt (and remembers that it was asked). */
    fun requestPermissions()

    fun openAppSettings()

    /** Asks the system to switch the radio on. */
    fun enableRadio()
}

/** The Bluetooth hub: an honest note on the trial state, then host or find. */
@Composable
fun BluetoothHubScreen(onHost: () -> Unit, onJoin: () -> Unit, onBack: () -> Unit, newTables: Boolean = Features.TRIBUTE_PROFILE) {
    RoomPage(stringResource(R.string.bt_title), "bt", onBack) {
        Text(stringResource(R.string.bt_intro), style = roomBody)
        RoomPanel("bt:trial") { Text(stringResource(R.string.bt_trial), style = roomLabel, color = Wpgd.colors.ink) }
        // The `tributeProfile` switch off: no new table, so no hosting (joining stays).
        if (newTables) RoomPrimary(stringResource(R.string.bt_host), "bt:host", onClick = onHost)
        RoomSecondary(stringResource(R.string.bt_join), "bt:join", onClick = onJoin)
    }
}

/**
 * B3, B4, B6 and the unsupported cases: one screen per [BleGateState] short of ready, each with the
 * actions [BleGate.actions] lists — the fix first, then the same Wi-Fi and solo play.
 */
@Composable
fun BluetoothGateScreen(state: BleGateState, purpose: BlePurpose, onAction: (BleGateAction) -> Unit, onBack: () -> Unit) {
    val c = Wpgd.colors
    val title = stringResource(if (purpose == BlePurpose.HOST) R.string.bt_host else R.string.bt_join)
    RoomPage(title, "gate:${state.name}", onBack) {
        val (heading, body) = when (state) {
            BleGateState.NEEDS_PERMISSION -> R.string.gate_needs_title to if (purpose == BlePurpose.HOST) R.string.gate_needs_body_host else R.string.gate_needs_body_join
            BleGateState.DENIED -> R.string.gate_denied_title to R.string.gate_denied_body
            BleGateState.PERMANENTLY_DENIED -> R.string.gate_denied_title to R.string.gate_settings_path
            BleGateState.RADIO_OFF -> R.string.gate_radio_off_title to R.string.gate_radio_off_body
            BleGateState.UNSUPPORTED_ANDROID -> R.string.gate_unsupported_title to R.string.gate_unsupported_body
            BleGateState.NO_BLUETOOTH -> R.string.gate_no_ble_title to R.string.gate_no_ble_body
            BleGateState.CANNOT_HOST -> R.string.gate_cannot_host_title to R.string.gate_cannot_host_body
            BleGateState.READY -> return@RoomPage
        }
        RoomPanel("gate:message", emphasis = state != BleGateState.NEEDS_PERMISSION) {
            Text(
                stringResource(heading),
                style = roomTitle,
                color = if (state == BleGateState.NEEDS_PERMISSION) c.ink else c.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(stringResource(body), style = roomBody)
        }
        if (state == BleGateState.NEEDS_PERMISSION) Text(stringResource(R.string.gate_needs_note), style = roomLabel, color = c.ink2)
        BleGate.actions(state).forEachIndexed { i, action ->
            val label = stringResource(
                when (action) {
                    BleGateAction.REQUEST_PERMISSION -> if (state == BleGateState.DENIED) R.string.gate_retry else R.string.gate_continue
                    BleGateAction.OPEN_APP_SETTINGS -> R.string.gate_open_settings
                    BleGateAction.ENABLE_RADIO -> R.string.gate_enable_radio
                    BleGateAction.USE_WIFI -> R.string.gate_use_wifi
                    BleGateAction.PLAY_SOLO -> R.string.gate_solo
                },
            )
            val tag = "gate:action:${action.name}"
            if (i == 0) RoomPrimary(label, tag) { onAction(action) } else RoomSecondary(label, tag) { onAction(action) }
        }
    }
}

/**
 * Runs the gate for [purpose]: re-reads the platform every second while shown and whenever the screen
 * resumes (back from the system prompt, settings or the radio switch), and continues by itself with
 * [onReady] once everything is in place (B6: "打开蓝牙后会自动继续").
 */
@Composable
fun BluetoothGateRoute(
    access: BluetoothAccess,
    purpose: BlePurpose,
    onReady: () -> Unit,
    onUseWifi: () -> Unit,
    onSolo: () -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf(BleGate.state(access.environment(), purpose)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(access, purpose) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state = BleGate.state(access.environment(), purpose)
                if (state == BleGateState.READY) {
                    onReady()
                    return@repeatOnLifecycle
                }
                delay(POLL_MS)
            }
        }
    }
    if (state == BleGateState.READY) return
    BluetoothGateScreen(state, purpose, onAction = { action ->
        when (action) {
            BleGateAction.REQUEST_PERMISSION -> access.requestPermissions()
            BleGateAction.OPEN_APP_SETTINGS -> access.openAppSettings()
            BleGateAction.ENABLE_RADIO -> access.enableRadio()
            BleGateAction.USE_WIFI -> onUseWifi()
            BleGateAction.PLAY_SOLO -> onSolo()
        }
    }, onBack = onBack)
}

/** The medium a room uses, in words (C2, C9): never claims Bluetooth for a Wi-Fi room. */
@Composable
internal fun mediumText(bluetooth: Boolean): String = stringResource(if (bluetooth) R.string.room_medium_bluetooth else R.string.room_medium_lan)

private const val POLL_MS = 1_000L
