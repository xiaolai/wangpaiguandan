package cn.wpgd.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.wpgd.app.R
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.PendingJoiner
import cn.wpgd.app.owner.RoomSession
import cn.wpgd.app.room.LeaveChoice
import cn.wpgd.app.room.LobbySeatUi
import cn.wpgd.app.room.LobbyUi
import cn.wpgd.app.room.LobbyUiState
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.RulesSheet
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The lobby (B7–B10, D1, D2): who sits where and who is ready, the rules everyone readies for, and —
 * for the host — admission (B8, comparing the six-digit code), seats and AI (B10), settings (D2) and
 * start. Every button sends a request the host checks again.
 */
@Composable
fun LobbyScreen(
    lobby: LobbyUiState,
    rules: RulesSheet,
    session: RoomSession,
    onRulesSeen: (Long) -> Unit,
    onLeave: () -> Unit,
    /** H9: false when the choice was refused (a suspend whose shown expiry passed); the prompt is shown again. */
    onLeaveHosted: (LeaveChoice) -> Boolean,
    /** Join by address: this phone's Wi-Fi or hotspot addresses (read off the main thread, refreshed while shown). */
    hostAddresses: () -> List<String> = { emptyList() },
) {
    val c = Wpgd.colors
    var showRules by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var seatSheet by rememberSaveable { mutableStateOf<Seat?>(null) }
    RoomPage(stringResource(R.string.lobby_title, lobby.info.tableName, lobby.info.tableCode), "lobby", null) {
        Text(stringResource(R.string.lobby_host, lobby.info.hostName), style = roomBody, color = c.ink2)
        if (lobby.hostingFailed) {
            Text(stringResource(R.string.lobby_hosting_failed), style = roomBody.copy(fontWeight = FontWeight.Bold), color = c.danger, modifier = Modifier.testTag("lobby:hosting_failed").semantics { liveRegion = LiveRegionMode.Assertive })
        }
        lobby.pending.firstOrNull()?.let { AdmissionPanel(it, session) }
        if (lobby.listenPort != null) JoinAddressPanel(lobby.listenPort, hostAddresses)

        for (s in lobby.seats) {
            SeatRow(s, lobby.hosting) { seatSheet = if (seatSheet == s.seat) null else s.seat }
            if (lobby.hosting && seatSheet == s.seat) SeatSheet(s, lobby.seats, session) { seatSheet = null }
        }
        Text(
            if (lobby.observers.isEmpty()) stringResource(R.string.lobby_no_watchers) else stringResource(R.string.lobby_watchers, lobby.observers.joinToString(stringResource(R.string.list_separator))),
            style = roomLabel,
            color = c.ink2,
            modifier = Modifier.testTag("lobby:watchers"),
        )

        if (lobby.rulesChanged) {
            Text(stringResource(R.string.lobby_rules_changed), style = roomBody.copy(fontWeight = FontWeight.Bold), color = c.danger, modifier = Modifier.testTag("lobby:rules_changed").semantics { liveRegion = LiveRegionMode.Polite })
        }
        RoomSecondary(stringResource(R.string.lobby_rules_button), "lobby:rules") {
            showRules = !showRules
            onRulesSeen(lobby.settingsRevision)
        }
        if (showRules) RulesSheetView(rules)

        if (lobby.yourSeat != null && !lobby.hosting) {
            RoomPrimary(stringResource(if (lobby.youReady) R.string.lobby_unready_button else R.string.lobby_ready_button), "lobby:ready", enabled = lobby.canReady) {
                onRulesSeen(lobby.settingsRevision)
                session.setReady(!lobby.youReady)
            }
        }
        if (lobby.hosting) HostControls(lobby, session) else Text(stringResource(R.string.lobby_waiting_host), style = roomLabel, color = c.ink2)
        if (lobby.hosting && leaving) {
            HostLeaveChoice(session, onLeaveHosted) { leaving = false }
        } else {
            RoomSecondary(stringResource(R.string.lobby_leave), "lobby:leave") { if (lobby.hosting) leaving = true else onLeave() }
        }
    }
}

/** The host's part of the lobby: what still blocks the start, the start itself, and the settings (D2). */
@Composable
private fun HostControls(lobby: LobbyUiState, session: RoomSession) {
    val c = Wpgd.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    if (lobby.startBlockers.isNotEmpty()) {
        Column(Modifier.testTag("lobby:blockers")) {
            for (r in lobby.startBlockers.map { it.reason }.distinct()) Text(blockerText(r), style = roomLabel, color = c.ink2)
        }
    }
    RoomPrimary(stringResource(R.string.lobby_start), "lobby:start", enabled = lobby.canStart) { session.startMatch() }
    RoomSecondary(stringResource(R.string.lobby_settings), "lobby:settings") { editing = !editing }
    if (editing) {
        RoomPanel("lobby:settings_form") {
            RoomSettingsForm(currentSettings(lobby), stringResource(R.string.lobby_settings_save), "lobby:settings_save") {
                session.updateSettings(it)
                editing = false
            }
        }
    }
}

/** The room's settings as the form edits them; a grace the form does not offer shows as the default. */
private fun currentSettings(lobby: LobbyUiState) = RoomSettings(
    lobby.info.tableName,
    lobby.settings.policy.mode,
    if (lobby.settings.afterGrace == AbsenceMode.SUBSTITUTE) AbsencePolicy.SUBSTITUTE else AbsencePolicy.PAUSE,
    (lobby.settings.graceMs / 1_000).toInt().takeIf { it in RoomSettings.GRACE_CHOICES } ?: RoomSettings.DEFAULT_GRACE_SECONDS,
)

/** H9 in the lobby: the host suspends (kept until the time shown) or ends the table, after reading what each means. */
@Composable
private fun HostLeaveChoice(session: RoomSession, onChoice: (LeaveChoice) -> Boolean, cancel: () -> Unit) {
    val prompt = rememberLeavePrompt(session::prepareLeave)
    if (prompt.value == null) return
    RoomPanel("menu:leave_prompt", emphasis = true) {
        Text(stringResource(R.string.table_leave_confirm_title), style = roomTitle)
        HostLeaveOptions(prompt, session::prepareLeave, onChoice, roomBody) { label, tag, onClick -> RoomPrimary(label, tag, onClick = onClick) }
        RoomSecondary(stringResource(R.string.cancel), "menu:leave_cancel", onClick = cancel)
    }
}

@Composable
private fun blockerText(r: LobbyGate.Reason): String = stringResource(
    when (r) {
        LobbyGate.Reason.EMPTY_SEAT -> R.string.lobby_block_empty_seat
        LobbyGate.Reason.NOT_READY -> R.string.lobby_block_not_ready
        LobbyGate.Reason.AWAY -> R.string.lobby_block_away
    },
)

/** One seat; one node for a screen reader whose description is the words shown. */
@Composable
private fun SeatRow(s: LobbySeatUi, hosting: Boolean, onTap: () -> Unit) {
    val c = Wpgd.colors
    val holder = when (s.occupant) {
        Occupant.EMPTY -> stringResource(R.string.lobby_seat_empty)
        Occupant.AI -> stringResource(R.string.lobby_seat_ai)
        else -> s.nickname ?: stringResource(R.string.lobby_seat_empty)
    } + if (s.isYou) stringResource(R.string.lobby_you) else ""
    val state = when {
        s.graceSeconds != null -> stringResource(R.string.lobby_away, s.graceSeconds)
        s.occupant == Occupant.HUMAN -> stringResource(if (s.ready) R.string.lobby_ready else R.string.lobby_not_ready)
        else -> ""
    }
    val line = stringResource(R.string.lobby_seat, seatName(s.seat), teamName(s.team))
    val summary = listOf(line, holder, state).filter { it.isNotEmpty() }.joinToString(stringResource(R.string.clause_separator))
    Surface(
        color = c.surface2,
        shape = RoundedCornerShape(Tokens.radius("control")),
        border = BorderStroke(if (s.isYou) 2.dp else 1.dp, if (s.isYou) c.accent else c.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touch)
            .testTag("lobby:seat:${s.seat.name}")
            .then(if (hosting) Modifier.clickable(onClick = onTap) else Modifier)
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        Column(Modifier.padding(Tokens.space(3))) {
            Text(line, style = roomLabel, color = c.ink2)
            Text(holder, style = roomBody.copy(fontWeight = FontWeight.Bold))
            if (state.isNotEmpty()) Text(state, style = roomLabel, color = if (s.ready) c.ink else c.ink2)
        }
    }
}

/** B10 (host): put or remove an AI, or swap this seat with another (partners are chosen here). */
@Composable
private fun SeatSheet(s: LobbySeatUi, all: List<LobbySeatUi>, session: RoomSession, done: () -> Unit) {
    RoomPanel("seat:sheet:${s.seat.name}") {
        if (s.occupant == Occupant.EMPTY) RoomSecondary(stringResource(R.string.seat_put_ai), "seat:ai:${s.seat.name}") { session.setAiSeat(s.seat); done() }
        if (s.occupant == Occupant.AI) RoomSecondary(stringResource(R.string.seat_remove_ai), "seat:clear:${s.seat.name}") { session.clearAiSeat(s.seat); done() }
        for (other in all.filter { it.seat != s.seat }) {
            RoomSecondary(stringResource(R.string.seat_swap, seatName(other.seat)), "seat:swap:${s.seat.name}:${other.seat.name}") { session.swapSeats(s.seat, other.seat); done() }
        }
    }
}

/**
 * B8: someone asks to join. G2 (owner-delegated default): on a first join the host compares the six-digit code with the joiner's
 * screen before approving; the code shown is the one this connection's key exchange produced.
 */
@Composable
internal fun AdmissionPanel(p: PendingJoiner, session: RoomSession) {
    val c = Wpgd.colors
    RoomPanel("admit:dialog", emphasis = true) {
        Text(stringResource(R.string.admit_title, p.nickname), style = roomTitle, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        val what = when {
            p.role == Role.OBSERVER -> stringResource(R.string.admit_as_watcher)
            p.replace -> stringResource(R.string.admit_replace, p.seat?.let { seatName(it) }.orEmpty())
            else -> stringResource(R.string.admit_as_player, p.seat?.let { seatName(it) }.orEmpty())
        }
        Text(what, style = roomBody)
        Text(stringResource(R.string.admit_code, p.code), style = TextStyle(fontSize = Tokens.type("hero"), fontWeight = FontWeight.Bold), modifier = Modifier.testTag("admit:code"))
        Text(stringResource(R.string.admit_code_hint), style = roomLabel, color = c.ink2)
        RoomPrimary(stringResource(R.string.admit_yes), "admit:yes") { session.approve(p.connectionId) }
        RoomSecondary(stringResource(R.string.admit_no), "admit:no") { session.deny(p.connectionId) }
    }
}

/** D1: every rule a player readies for, from the frozen profiles and the host's settings. */
@Composable
internal fun RulesSheetView(r: RulesSheet) {
    val c = Wpgd.colors
    val text = rememberTableText(null)
    RoomPanel("rules:sheet") {
        Text(stringResource(R.string.rules_title), style = roomTitle)
        Text(stringResource(R.string.rules_base, r.summary.baseId, r.summary.baseVersion), style = roomLabel)
        Text(stringResource(R.string.rules_match, r.summary.matchId, r.summary.matchVersion), style = roomLabel)
        Text(stringResource(R.string.rules_hash, r.summary.baseHash, r.summary.matchHash), style = roomLabel, color = c.ink2)
        Text(r.returnMaxRank?.let { stringResource(R.string.rules_return_max, text.rank(it)) } ?: stringResource(R.string.rules_return_any), style = roomLabel)
        Text(stringResource(if (r.resistanceClaimed) R.string.rules_resistance_claimed else R.string.rules_resistance_automatic), style = roomLabel)
        if (r.resistanceSplitBetweenPartners) Text(stringResource(R.string.rules_resistance_split), style = roomLabel)
        Text(stringResource(R.string.rules_pass_a, r.minGainToPassA), style = roomLabel)
        Text(r.maxAFailures?.let { stringResource(R.string.rules_a_failures, it) } ?: stringResource(R.string.rules_a_failures_none), style = roomLabel)
        if (r.maxAFailures != null) Text(stringResource(R.string.rules_reset, text.rank(r.resetLevel)), style = roomLabel)
        Text(r.reportingThreshold?.let { stringResource(R.string.rules_reporting, it) } ?: stringResource(R.string.rules_reporting_off), style = roomLabel)
        Text(modeText(r.mode), style = roomLabel.copy(fontWeight = FontWeight.Bold))
        val after = stringResource(if (r.absence == AbsenceMode.SUBSTITUTE) R.string.absence_short_substitute else R.string.absence_short_pause)
        Text(stringResource(R.string.rules_absence, r.graceSeconds, after), style = roomLabel)
        if (r.summary.provisional) Text(stringResource(R.string.rules_provisional), style = roomLabel.copy(fontWeight = FontWeight.Bold), color = c.danger)
    }
}

/**
 * Join by address: where others can reach this room when discovery cannot (hotspot, isolated guest
 * Wi-Fi). Hidden while this phone has no Wi-Fi or hotspot address. Addresses change when the network
 * does, so they are read again every few seconds while the lobby is shown.
 */
@Composable
private fun JoinAddressPanel(port: Int, hostAddresses: () -> List<String>) {
    var addresses by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(port) {
        while (true) {
            addresses = withContext(Dispatchers.IO) { hostAddresses() }
            delay(ADDRESS_REFRESH_MS)
        }
    }
    val shown = LobbyUi.joinAddresses(port, addresses)
    if (shown.isEmpty()) return
    RoomPanel("lobby:addresses") {
        Text(stringResource(R.string.lobby_addresses_title), style = roomLabel, color = Wpgd.colors.ink2)
        for ((i, a) in shown.withIndex()) {
            Text(stringResource(R.string.lobby_address_line, a.host, a.port), style = roomBody.copy(fontWeight = FontWeight.Bold), color = Wpgd.colors.ink, modifier = Modifier.testTag("lobby:address:$i"))
        }
    }
}

private const val ADDRESS_REFRESH_MS = 5_000L
