package cn.wpgd.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import cn.wpgd.app.R
import cn.wpgd.app.ble.BleGate
import cn.wpgd.app.ble.BleGateState
import cn.wpgd.app.ble.BlePurpose
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.Features
import cn.wpgd.app.room.LocalVersion
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomFinder
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.SavedTables
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.table.WakeLockSink
import kotlinx.coroutines.flow.StateFlow

/** The app's places. Saved across recreation; the table itself lives in the owner. */
enum class Route { MENU, SETTINGS, TABLE, CREATE, FIND, SAVED, BLUETOOTH, BLUETOOTH_GATE }

/** WI-09: what the rooms part of the shell needs. Each action starts (or restores) a table in the owner. */
class RoomShell(
    /** The room search over a medium (WI-10: Bluetooth only after the gate said ready). */
    val finder: (RoomMedium) -> RoomFinder,
    val localVersion: () -> LocalVersion,
    val savedTables: () -> SavedTables,
    val host: (RoomSettings, RoomMedium) -> Unit,
    /** Joins as a player at the seat, or as a watcher when the seat is null (C4); or asks to take a seat over. The advert's medium is used. */
    val join: (RoomAdvert, Seat?, Boolean) -> Unit,
    val restore: (String, RoomMedium) -> Unit,
    /** WI-10: the medium a saved table is restored on. */
    val savedMedium: (String) -> RoomMedium = { RoomMedium.LAN },
    /** WI-10: null when the build's `bleTransport` flag is off: no Bluetooth entry is shown. */
    val bluetooth: BluetoothAccess? = null,
)

/** What the shell needs from the activity: navigation targets and the platform sinks. */
class AppShell(
    val preferences: PreferencesRepository,
    val openTable: StateFlow<SessionOwner?>,
    /** Starts (or rejoins) the solo table; idempotent. */
    val startSolo: () -> Unit,
    /** The table screen's view model (survives recreation). */
    val tableViewModel: () -> TableViewModel,
    /** Ends the table and forgets its view model. */
    val leaveTable: () -> Unit,
    val effects: EffectSink,
    val wake: WakeLockSink,
    val rooms: RoomShell,
    /** WI-12: the local diagnostics text (built on request; shown, never sent). */
    val diagnostics: () -> String = { "" },
)

@Composable
fun AppRoot(shell: AppShell) {
    var route by rememberSaveable { mutableStateOf(Route.MENU) }
    // WI-10: the medium of the create/find screens, why the Bluetooth gate is shown, and a saved
    // Bluetooth table waiting for the gate before it is restored.
    var medium by rememberSaveable { mutableStateOf(RoomMedium.LAN) }
    var purpose by rememberSaveable { mutableStateOf(BlePurpose.HOST) }
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }
    val bluetooth = shell.rooms.bluetooth
    val open by shell.openTable.collectAsState()
    // After process death the saved route may point at a table that no longer exists.
    LaunchedEffect(route, open) { if (route == Route.TABLE && open == null) route = Route.MENU }
    when (route) {
        Route.MENU -> MainMenu(
            tableOpen = open != null,
            onSolo = {
                shell.startSolo()
                route = Route.TABLE
            },
            onSettings = { route = Route.SETTINGS },
            onHost = {
                medium = RoomMedium.LAN
                route = Route.CREATE
            },
            onJoin = {
                medium = RoomMedium.LAN
                route = Route.FIND
            },
            onSaved = { route = Route.SAVED },
            onBluetooth = if (bluetooth != null) ({ route = Route.BLUETOOTH }) else null,
        )
        Route.BLUETOOTH -> {
            BackHandler { route = Route.MENU }
            BluetoothHubScreen(
                onHost = {
                    purpose = BlePurpose.HOST
                    pendingRestore = null
                    route = Route.BLUETOOTH_GATE
                },
                onJoin = {
                    purpose = BlePurpose.JOIN
                    pendingRestore = null
                    route = Route.BLUETOOTH_GATE
                },
                onBack = { route = Route.MENU },
            )
        }
        Route.BLUETOOTH_GATE -> {
            val back = { route = if (pendingRestore != null) Route.SAVED else Route.BLUETOOTH }
            BackHandler(onBack = back)
            if (bluetooth == null) {
                LaunchedEffect(Unit) { route = Route.MENU }
            } else {
                // Past the gate over [chosen]: restore the waiting saved table, or go on to create or find.
                fun proceed(chosen: RoomMedium) {
                    val restoring = pendingRestore
                    pendingRestore = null
                    medium = chosen
                    if (restoring != null) {
                        shell.rooms.restore(restoring, chosen)
                        route = Route.TABLE
                    } else {
                        route = if (purpose == BlePurpose.HOST) Route.CREATE else Route.FIND
                    }
                }
                BluetoothGateRoute(
                    access = bluetooth,
                    purpose = purpose,
                    onReady = { proceed(RoomMedium.BLUETOOTH) },
                    onUseWifi = { proceed(RoomMedium.LAN) },
                    onSolo = {
                        pendingRestore = null
                        shell.startSolo()
                        route = Route.TABLE
                    },
                    onBack = back,
                )
            }
        }
        Route.SETTINGS -> {
            BackHandler { route = Route.MENU }
            SettingsScreen(shell.preferences, shell.diagnostics) { route = Route.MENU }
        }
        // A Bluetooth create or find screen restored after the process died (for example because a
        // permission was revoked in the system settings) goes through the gate again before any radio use.
        Route.CREATE, Route.FIND -> if (medium == RoomMedium.BLUETOOTH && !bluetoothReady(bluetooth, if (route == Route.CREATE) BlePurpose.HOST else BlePurpose.JOIN)) {
            LaunchedEffect(route) {
                purpose = if (route == Route.CREATE) BlePurpose.HOST else BlePurpose.JOIN
                pendingRestore = null
                route = if (bluetooth == null) Route.MENU else Route.BLUETOOTH_GATE
                if (bluetooth == null) medium = RoomMedium.LAN
            }
        } else if (route == Route.CREATE) {
            val back = { route = if (medium == RoomMedium.BLUETOOTH) Route.BLUETOOTH else Route.MENU }
            BackHandler(onBack = back)
            CreateRoomScreen(medium, onCreate = { settings ->
                shell.rooms.host(settings, medium)
                route = Route.TABLE
            }, onBack = back)
        } else {
            val back = { route = if (medium == RoomMedium.BLUETOOTH) Route.BLUETOOTH else Route.MENU }
            BackHandler(onBack = back)
            FindRoomScreen(shell.rooms.finder(medium), shell.rooms.localVersion(), medium = medium, onJoin = { advert, seat, replace ->
                shell.rooms.join(advert, seat, replace)
                route = Route.TABLE
            }, onBack = back)
        }
        Route.SAVED -> {
            BackHandler { route = Route.MENU }
            SavedTablesScreen(shell.rooms.savedTables(), onRestore = { id ->
                if (shell.rooms.savedMedium(id) == RoomMedium.BLUETOOTH && bluetooth != null) {
                    // A Bluetooth table comes back over Bluetooth: through the gate first.
                    pendingRestore = id
                    purpose = BlePurpose.HOST
                    route = Route.BLUETOOTH_GATE
                } else {
                    shell.rooms.restore(id, RoomMedium.LAN)
                    route = Route.TABLE
                }
            }, onBack = { route = Route.MENU })
        }
        Route.TABLE -> open?.let { owner ->
            BackHandler { route = Route.MENU }
            OpenTableRoute(
                owner = owner,
                vm = shell.tableViewModel,
                effects = shell.effects,
                wake = shell.wake,
                onLeave = {
                    route = Route.MENU
                    shell.leaveTable()
                },
                onFindAgain = { route = Route.FIND },
                onUseWifi = {
                    medium = RoomMedium.LAN
                    route = Route.FIND
                },
            )
        }
    }
}

/** The main menu (A1): solo play, or back to the open table; rooms (B1, C1, H3); settings. */
/** Whether the Bluetooth gate is open now for [purpose] (false when the build has no Bluetooth). */
private fun bluetoothReady(access: BluetoothAccess?, purpose: BlePurpose): Boolean =
    access != null && BleGate.state(access.environment(), purpose) == BleGateState.READY

@Composable
fun MainMenu(
    tableOpen: Boolean,
    onSolo: () -> Unit,
    onSettings: () -> Unit,
    onHost: () -> Unit = {},
    onJoin: () -> Unit = {},
    onSaved: () -> Unit = {},
    /** WI-10: the Bluetooth entry; null (the `bleTransport` flag off) hides it. */
    onBluetooth: (() -> Unit)? = null,
    /** The `tributeProfile` switch: off, solo play and hosting are hidden and the menu says why. */
    newTables: Boolean = Features.TRIBUTE_PROFILE,
) {
    val c = Wpgd.colors
    Surface(color = c.bg, contentColor = c.ink, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Tokens.space(7)),
            verticalArrangement = Arrangement.spacedBy(Tokens.space(5), Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.menu_title),
                style = TextStyle(fontSize = Tokens.type("hero"), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif),
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.menu_subtitle), style = TextStyle(fontSize = Tokens.type("body")), color = c.ink2)
            val wide = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = Tokens.touch + Tokens.space(2))
            if (tableOpen || newTables) {
                Button(
                    onClick = onSolo,
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.bg),
                    modifier = wide.testTag(if (tableOpen) "menu:continue" else "menu:solo"),
                ) {
                    Text(stringResource(if (tableOpen) R.string.menu_continue else R.string.menu_solo), style = TextStyle(fontSize = Tokens.type("title"), fontWeight = FontWeight.Bold))
                }
            } else {
                Text(stringResource(R.string.menu_new_tables_off), style = TextStyle(fontSize = Tokens.type("body")), color = c.ink, modifier = Modifier.testTag("menu:new_tables_off"))
            }
            if (!tableOpen) {
                for ((label, tag, action) in listOfNotNull(
                    Triple(R.string.menu_host, "menu:host", onHost).takeIf { newTables },
                    Triple(R.string.menu_join, "menu:join", onJoin),
                    Triple(R.string.menu_saved, "menu:saved", onSaved),
                )) {
                    OutlinedButton(
                        onClick = action,
                        border = BorderStroke(1.dp, c.ink),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
                        modifier = wide.testTag(tag),
                    ) {
                        Text(stringResource(label), style = TextStyle(fontSize = Tokens.type("title")))
                    }
                }
                Text(stringResource(R.string.menu_rooms_note), style = TextStyle(fontSize = Tokens.type("label")), color = c.ink2)
                if (onBluetooth != null) {
                    OutlinedButton(
                        onClick = onBluetooth,
                        border = BorderStroke(1.dp, c.ink),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
                        modifier = wide.testTag("menu:bluetooth"),
                    ) {
                        Text(stringResource(R.string.menu_bluetooth), style = TextStyle(fontSize = Tokens.type("title")))
                    }
                }
            }
            OutlinedButton(
                onClick = onSettings,
                border = BorderStroke(1.dp, c.ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
                modifier = wide.testTag("menu:settings"),
            ) {
                Text(stringResource(R.string.menu_settings), style = TextStyle(fontSize = Tokens.type("title")))
            }
        }
    }
}

/** Settings from the menu (A4): the same content as the in-table menu. */
@Composable
fun SettingsScreen(preferences: PreferencesRepository, diagnostics: () -> String, onBack: () -> Unit) {
    val c = Wpgd.colors
    val prefs by preferences.preferences.collectAsState()
    Surface(color = c.bg, contentColor = c.ink, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Tokens.space(5)), verticalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Tokens.touch).testTag("settings:back")) {
                    Text(stringResource(R.string.back), style = TextStyle(fontSize = Tokens.type("label")), color = c.ink)
                }
                Text(
                    stringResource(R.string.settings_title),
                    style = TextStyle(fontSize = Tokens.type("heading"), fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { heading() },
                )
            }
            SettingsContent(prefs, preferences::update)
            DiagnosticsSection(diagnostics)
        }
    }
}

/**
 * WI-12 (GDN-108): local diagnostics, shown only when the user asks. The text is selectable so the
 * user may copy it; the app itself sends it nowhere. It holds counts, revisions and the rule
 * profiles, never cards, codes or identities (`DiagnosticsRedactionTest`).
 */
@Composable
private fun DiagnosticsSection(diagnostics: () -> String) {
    val c = Wpgd.colors
    var requested by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    // Built off the main thread: the export waits (briefly) for the session loop. A failed export is
    // shown with a retry; it never crashes the screen.
    LaunchedEffect(requested) {
        if (requested && text == null) {
            val built = buildDiagnostics(diagnostics, Dispatchers.Default)
            if (built == null) requested = false else text = built
            failed = built == null
        }
    }
    Text(
        stringResource(R.string.diagnostics_title),
        style = TextStyle(fontSize = Tokens.type("title"), fontWeight = FontWeight.Bold),
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.diagnostics_note), style = TextStyle(fontSize = Tokens.type("label")), color = c.ink)
    val shown = text
    if (failed) Text(stringResource(R.string.diagnostics_failed), style = TextStyle(fontSize = Tokens.type("label")), color = c.danger, modifier = Modifier.testTag("settings:diagnostics_failed"))
    if (shown == null) {
        OutlinedButton(
            onClick = { requested = true },
            enabled = !requested,
            border = BorderStroke(1.dp, c.ink),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
            modifier = Modifier.heightIn(min = Tokens.touch).testTag("settings:diagnostics"),
        ) {
            Text(stringResource(R.string.diagnostics_show), style = TextStyle(fontSize = Tokens.type("label")))
        }
    } else {
        SelectionContainer {
            Text(shown, style = TextStyle(fontSize = Tokens.type("label"), fontFamily = FontFamily.Monospace), color = c.ink, modifier = Modifier.testTag("settings:diagnostics_text"))
        }
    }
}

/**
 * Builds the diagnostics text on [dispatcher]. Null when the export failed (the screen offers a
 * retry); cancellation still propagates.
 */
internal suspend fun buildDiagnostics(export: () -> String, dispatcher: CoroutineDispatcher): String? = try {
    withContext(dispatcher) { export() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
