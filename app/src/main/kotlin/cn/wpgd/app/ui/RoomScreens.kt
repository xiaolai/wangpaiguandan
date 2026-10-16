package cn.wpgd.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.wpgd.app.R
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.JoinStatus
import cn.wpgd.app.owner.RoomInfo
import cn.wpgd.app.room.Compatibility
import cn.wpgd.app.room.DeleteState
import cn.wpgd.app.room.LocalVersion
import cn.wpgd.app.room.MediumFallback
import cn.wpgd.app.room.PlayOrWatch
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomFinder
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.SavedTables
import cn.wpgd.app.room.SavedTablesLoad
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.wire.RoomMode
import java.text.DateFormat
import java.util.Date

internal val roomBody @Composable get() = TextStyle(fontSize = Tokens.type("body"))
internal val roomLabel @Composable get() = TextStyle(fontSize = Tokens.type("label"))
internal val roomTitle @Composable get() = TextStyle(fontSize = Tokens.type("title"), fontWeight = FontWeight.Bold)

/** A full-screen page with a back button and a heading; content scrolls. */
@Composable
internal fun RoomPage(title: String, tag: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val c = Wpgd.colors
    Surface(color = c.bg, contentColor = c.ink, modifier = Modifier.fillMaxSize().testTag(tag)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Tokens.space(5)),
            verticalArrangement = Arrangement.spacedBy(Tokens.space(3)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Tokens.touch).testTag("page:back")) {
                        Text(stringResource(R.string.back), style = roomLabel, color = c.ink)
                    }
                }
                Text(title, style = TextStyle(fontSize = Tokens.type("heading"), fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f).semantics { heading() })
            }
            content()
        }
    }
}

@Composable
internal fun RoomPrimary(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Wpgd.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.bg, disabledContainerColor = c.surface2, disabledContentColor = c.ink2),
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag(tag),
    ) { Text(label, style = roomBody.copy(fontWeight = FontWeight.Bold)) }
}

@Composable
internal fun RoomSecondary(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Wpgd.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) c.ink else c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink, disabledContentColor = c.ink2),
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag(tag),
    ) { Text(label, style = roomBody) }
}

/** A bordered panel. */
@Composable
internal fun RoomPanel(tag: String, emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Wpgd.colors
    Surface(
        color = c.surface2,
        shape = RoundedCornerShape(Tokens.radius("control")),
        border = BorderStroke(if (emphasis) 2.dp else 1.dp, if (emphasis) c.accent else c.line),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2)), content = content)
    }
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag(tag).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = roomBody, color = Wpgd.colors.ink)
    }
}

/** The compass name of a seat (南家…): in rooms every seat is named the same on every phone. */
@Composable
internal fun seatName(seat: Seat): String = stringResource(
    when (seat) {
        Seat.SOUTH -> R.string.seat_name_south
        Seat.EAST -> R.string.seat_name_east
        Seat.NORTH -> R.string.seat_name_north
        Seat.WEST -> R.string.seat_name_west
    },
)

@Composable
internal fun teamName(team: Team): String = stringResource(if (team == Team.NORTH_SOUTH) R.string.team_north_south else R.string.team_east_west)

@Composable
internal fun modeText(mode: RoomMode): String = stringResource(if (mode == RoomMode.FRIENDLY) R.string.mode_friendly else R.string.mode_competitive)

/**
 * B1 and D2: the settings a host chooses. [initial] is the current settings; [onSubmit] receives
 * valid settings only (the name is checked here and again by [RoomSettings]).
 */
@Composable
internal fun RoomSettingsForm(initial: RoomSettings, submitLabel: String, submitTag: String, onSubmit: (RoomSettings) -> Unit) {
    val c = Wpgd.colors
    var name by rememberSaveable { mutableStateOf(initial.tableName) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var absence by rememberSaveable { mutableStateOf(initial.absence) }
    var grace by rememberSaveable { mutableIntStateOf(initial.graceSeconds) }
    val valid = RoomSettings.validName(name.trim())
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(RoomSettings.MAX_NAME_CODE_POINTS * 2) },
        label = { Text(stringResource(R.string.create_name), style = roomLabel) },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("create:name"),
    )
    if (!valid) Text(stringResource(R.string.create_name_invalid), style = roomLabel, color = c.danger)
    Text(stringResource(R.string.create_mode), style = roomBody.copy(fontWeight = FontWeight.Bold))
    for (m in RoomMode.entries) ChoiceRow(modeText(m), mode == m, "create:mode:${m.name}") { mode = m }
    Text(stringResource(R.string.create_absence), style = roomBody.copy(fontWeight = FontWeight.Bold))
    for (a in AbsencePolicy.entries) {
        ChoiceRow(stringResource(if (a == AbsencePolicy.SUBSTITUTE) R.string.absence_substitute else R.string.absence_pause), absence == a, "create:absence:${a.name}") { absence = a }
    }
    Text(stringResource(R.string.create_grace), style = roomBody.copy(fontWeight = FontWeight.Bold))
    for (g in RoomSettings.GRACE_CHOICES) ChoiceRow(stringResource(R.string.grace_seconds, g), grace == g, "create:grace:$g") { grace = g }
    RoomPrimary(submitLabel, submitTag, enabled = valid) { onSubmit(RoomSettings(name.trim(), mode, absence, grace)) }
}

/** B1: create a room, over the same Wi-Fi or (WI-10) Bluetooth. */
@Composable
fun CreateRoomScreen(medium: RoomMedium, onCreate: (RoomSettings) -> Unit, onBack: () -> Unit) {
    val default = stringResource(R.string.create_name_default)
    val bluetooth = medium == RoomMedium.BLUETOOTH
    RoomPage(stringResource(if (bluetooth) R.string.create_title_bluetooth else R.string.create_title), "create", onBack) {
        Text(stringResource(if (bluetooth) R.string.rooms_note_bluetooth else R.string.menu_rooms_note), style = roomLabel, color = Wpgd.colors.ink2)
        RoomSettingsForm(RoomSettings(default), stringResource(R.string.create_button), "create:submit", onCreate)
    }
}

/**
 * C1–C4: nearby rooms as they are found (never joined automatically); choosing one asks play (at an
 * open seat) or watch, after showing its host and code to compare.
 */
@Composable
fun FindRoomScreen(finder: RoomFinder, local: LocalVersion, onJoin: (RoomAdvert, Seat?, Boolean) -> Unit, onBack: () -> Unit, medium: RoomMedium = RoomMedium.LAN) {
    val bluetooth = medium == RoomMedium.BLUETOOTH
    DisposableEffect(finder) {
        finder.start()
        onDispose { finder.stop() }
    }
    val state by finder.state.collectAsState()
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = state.rooms.firstOrNull { it.roomId == chosenId }
    // System Back from a chosen room returns to the list, as the page's own back button does.
    BackHandler(enabled = chosen != null) { chosenId = null }
    val c = Wpgd.colors
    RoomPage(stringResource(if (bluetooth) R.string.find_title_bluetooth else R.string.find_title), "find", if (chosen != null) ({ chosenId = null }) else onBack) {
        if (chosen != null) {
            PlayOrWatchContent(PlayOrWatch.of(chosen, local), onJoin)
            return@RoomPage
        }
        if (state.rooms.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
                if (state.searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.heightIn(max = Tokens.space(6)))
                Text(
                    stringResource(
                        when {
                            state.noneFound && bluetooth -> R.string.find_none_bluetooth
                            state.noneFound -> R.string.find_none
                            bluetooth -> R.string.find_searching_bluetooth
                            else -> R.string.find_searching
                        },
                    ),
                    style = roomBody,
                    modifier = Modifier.testTag(if (state.noneFound) "find:none" else "find:searching").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        for (room in state.rooms) {
            val compat = room.compatibility(local)
            Surface(
                color = c.surface2,
                shape = RoundedCornerShape(Tokens.radius("control")),
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag("find:room:${room.roomId}").clickable(onClick = { chosenId = room.roomId }),
            ) {
                Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
                    Text(room.tableName, style = roomTitle)
                    Text(stringResource(R.string.find_room_host, room.hostName, room.tableCode), style = roomBody)
                    val seats = if (room.openSeats.isEmpty()) stringResource(R.string.find_full) else stringResource(R.string.find_open_seats, room.openSeats.size)
                    Text(compatText(compat) ?: seats, style = roomLabel, color = if (compat == Compatibility.COMPATIBLE) c.ink2 else c.danger)
                }
            }
        }
        RoomSecondary(stringResource(R.string.find_retry), "find:retry") { finder.start() }
    }
}

@Composable
private fun compatText(compat: Compatibility): String? = when (compat) {
    Compatibility.COMPATIBLE -> null
    Compatibility.UPDATE_THIS_APP -> stringResource(R.string.compat_update_app)
    Compatibility.HOST_MUST_UPDATE -> stringResource(R.string.compat_host_must_update)
    Compatibility.OTHER_RULES -> stringResource(R.string.compat_other_rules)
}

/** C4: play at an open seat or watch, chosen before asking; the code is shown to compare with the host. */
@Composable
private fun PlayOrWatchContent(choice: PlayOrWatch, onJoin: (RoomAdvert, Seat?, Boolean) -> Unit) {
    val a = choice.advert
    Text(stringResource(R.string.pow_title, a.tableName), style = roomTitle, modifier = Modifier.testTag("pow"))
    Text(stringResource(R.string.pow_check_code, a.hostName, a.tableCode), style = roomBody)
    compatText(choice.compatibility)?.let {
        Text(it, style = roomBody.copy(fontWeight = FontWeight.Bold), color = Wpgd.colors.danger, modifier = Modifier.testTag("pow:incompatible"))
        return
    }
    if (choice.playSeats.isEmpty()) Text(stringResource(R.string.pow_no_seat), style = roomLabel, color = Wpgd.colors.ink2)
    for (seat in choice.playSeats) {
        RoomPrimary(stringResource(R.string.pow_play_at, seatName(seat), teamName(seat.team)), "pow:seat:${seat.name}") { onJoin(a, seat, false) }
    }
    for (seat in choice.replaceSeats) {
        RoomSecondary(stringResource(R.string.pow_replace, seatName(seat), teamName(seat.team)), "pow:replace:${seat.name}") { onJoin(a, seat, true) }
    }
    if (choice.canWatch) RoomSecondary(stringResource(R.string.pow_watch), "pow:watch") { onJoin(a, null, false) }
}

/** C5–C9, H2 and the end of a joined table: what is happening with the connection, and the way out. */
@Composable
fun JoinStatusScreen(info: RoomInfo?, join: JoinStatus?, onFindAgain: () -> Unit, onLeave: () -> Unit, onUseWifi: () -> Unit = {}) {
    val c = Wpgd.colors
    val phase = join?.phase ?: JoinPhase.CONNECTING
    RoomPage(info?.tableName ?: stringResource(R.string.find_title), "join", null) {
        info?.let {
            Text(stringResource(R.string.find_room_host, it.hostName, it.tableCode), style = roomBody)
            Text(mediumText(it.medium == RoomMedium.BLUETOOTH), style = roomLabel, color = c.ink2, modifier = Modifier.testTag("join:medium:${it.medium.name}"))
        }
        val message = when (phase) {
            JoinPhase.CONNECTING -> stringResource(R.string.join_connecting, info?.tableName.orEmpty())
            JoinPhase.AWAITING_APPROVAL -> stringResource(R.string.join_waiting)
            JoinPhase.ADMITTED -> stringResource(R.string.table_starting)
            JoinPhase.DENIED -> stringResource(R.string.join_denied)
            JoinPhase.SEAT_TAKEN -> stringResource(R.string.join_seat_taken)
            JoinPhase.FULL -> stringResource(R.string.join_full)
            JoinPhase.INCOMPATIBLE -> stringResource(R.string.join_incompatible)
            JoinPhase.FAILED -> stringResource(if (info?.medium == RoomMedium.BLUETOOTH) R.string.join_failed_bluetooth else R.string.join_failed)
            JoinPhase.HOST_LOST -> stringResource(R.string.join_host_lost, join?.reconnectAttempts ?: 0)
            JoinPhase.SUSPENDED -> stringResource(R.string.join_suspended)
            JoinPhase.ENDED -> stringResource(R.string.join_ended)
        }
        val waiting = phase == JoinPhase.CONNECTING || phase == JoinPhase.AWAITING_APPROVAL || phase == JoinPhase.HOST_LOST || phase == JoinPhase.ADMITTED
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
            if (waiting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.heightIn(max = Tokens.space(6)))
            Text(message, style = roomBody, modifier = Modifier.testTag("join:phase:${phase.name}").semantics { liveRegion = LiveRegionMode.Polite })
        }
        val code = join?.code
        if (phase == JoinPhase.AWAITING_APPROVAL && code != null) {
            RoomPanel("join:code", emphasis = true) {
                Text(stringResource(R.string.join_code, code), style = TextStyle(fontSize = Tokens.type("hero"), fontWeight = FontWeight.Bold), color = c.ink)
                Text(stringResource(R.string.join_code_hint), style = roomLabel, color = c.ink2)
            }
        }
        if (phase == JoinPhase.SEAT_TAKEN || phase == JoinPhase.FAILED || phase == JoinPhase.DENIED || phase == JoinPhase.SUSPENDED) {
            RoomPrimary(stringResource(R.string.join_find_again), "join:find_again", onClick = onFindAgain)
        }
        // C9 (GDN-021): Bluetooth failed: the same Wi-Fi is offered, never switched to silently.
        if (MediumFallback.offersWifi(info, phase)) RoomSecondary(stringResource(R.string.gate_use_wifi), "join:use_wifi", onClick = onUseWifi)
        RoomSecondary(stringResource(if (waiting) R.string.cancel else R.string.back_to_menu), "join:leave", onClick = onLeave)
    }
}

/**
 * H3, H6, H7: hosted tables saved on this device; restore, or delete after its own confirmation.
 * The list is read off the main thread, again on every resume and when the first shown save expires;
 * a restore is checked against the expiry first. Storage failures are shown with a retry.
 */
@Composable
fun SavedTablesScreen(tables: SavedTables, onRestore: (String) -> Unit, onBack: () -> Unit) {
    val c = Wpgd.colors
    var version by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<SavedTablesLoad>(SavedTablesLoad.Loading) }
    var deletion by remember { mutableStateOf(DeleteState()) }
    var expired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, version) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = SavedTablesLoad.of(tables, Dispatchers.IO)
                load = now
                val wait = (now as? SavedTablesLoad.Loaded)?.let { tables.untilNextExpiry(it.entries) } ?: break
                delay(wait + 1)
            }
        }
    }
    val unnamed = stringResource(R.string.saved_unnamed)
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    RoomPage(stringResource(R.string.saved_title), "saved", onBack) {
        if (expired) Text(stringResource(R.string.saved_expired), style = roomBody, color = c.danger, modifier = Modifier.testTag("saved:expired"))
        when (val l = load) {
            SavedTablesLoad.Loading -> Text(stringResource(R.string.saved_loading), style = roomBody, modifier = Modifier.testTag("saved:loading"))
            SavedTablesLoad.Failed -> {
                Text(stringResource(R.string.saved_failed), style = roomBody, color = c.danger, modifier = Modifier.testTag("saved:failed"))
                RoomSecondary(stringResource(R.string.retry), "saved:retry") { version++ }
            }
            is SavedTablesLoad.Loaded -> {
                if (l.entries.isEmpty()) Text(stringResource(R.string.saved_empty), style = roomBody, modifier = Modifier.testTag("saved:empty"))
                for (e in l.entries) {
                    RoomPanel("saved:entry:${e.tableId}") {
                        Text(e.tableName ?: unnamed, style = roomTitle)
                        val status = when (e.kind) {
                            SavedTables.Kind.RESTORABLE -> e.expiresAt?.let { stringResource(R.string.saved_expires, format.format(Date(it))) } ?: stringResource(R.string.saved_active)
                            SavedTables.Kind.DAMAGED -> stringResource(R.string.saved_damaged)
                            SavedTables.Kind.NEWER_VERSION -> stringResource(R.string.saved_newer)
                        }
                        Text(status, style = roomLabel, color = if (e.kind == SavedTables.Kind.RESTORABLE) c.ink2 else c.danger)
                        if (e.kind == SavedTables.Kind.RESTORABLE) {
                            RoomPrimary(stringResource(R.string.saved_restore), "saved:restore:${e.tableId}") {
                                expired = !tables.restorableNow(e)
                                if (expired) version++ else onRestore(e.tableId)
                            }
                        }
                        if (e.kind != SavedTables.Kind.NEWER_VERSION) {
                            RoomSecondary(stringResource(R.string.saved_delete), "saved:delete:${e.tableId}", enabled = !deletion.busy) {
                                deletion = deletion.open(tables.prepareDelete(e))
                            }
                        }
                    }
                }
            }
        }
        deletion.prompt?.let { prompt ->
            RoomPanel("saved:delete_prompt", emphasis = true) {
                Text(stringResource(R.string.delete_confirm_title, prompt.tableName ?: unnamed), style = roomTitle)
                Text(stringResource(R.string.delete_confirm_body), style = roomBody)
                if (deletion.failed) Text(stringResource(R.string.saved_delete_failed), style = roomBody, color = c.danger, modifier = Modifier.testTag("saved:delete_failed"))
                RoomPrimary(stringResource(R.string.delete_confirm_yes), "saved:delete_confirm", enabled = !deletion.busy) {
                    val (confirmation, running) = deletion.start() ?: return@RoomPrimary
                    deletion = running
                    scope.launch {
                        val ok = SavedTablesLoad.delete(tables, confirmation, Dispatchers.IO)
                        deletion = deletion.finished(confirmation.tableId, ok)
                        version++
                    }
                }
                RoomSecondary(stringResource(R.string.cancel), "saved:delete_cancel", enabled = !deletion.busy) { deletion = deletion.cancel() }
            }
        }
    }
}
