package cn.wpgd.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import cn.wpgd.app.R
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.table.AbsentSeatUi
import cn.wpgd.app.table.GroupKind
import cn.wpgd.app.table.HandArrangement
import cn.wpgd.app.table.HandSort
import cn.wpgd.app.table.PlayPreview
import cn.wpgd.app.table.ResultLine
import cn.wpgd.app.table.RulesSummary
import cn.wpgd.app.table.SeatLastAction
import cn.wpgd.app.table.SeatRelation
import cn.wpgd.app.table.SeatUi
import cn.wpgd.app.table.TableScreen
import cn.wpgd.app.table.TableUiState
import cn.wpgd.app.table.TurnKey
import kotlinx.coroutines.delay

private val bodyStyle @Composable get() = TextStyle(fontSize = Tokens.type("body"))
private val labelStyle @Composable get() = TextStyle(fontSize = Tokens.type("label"))
private val titleStyle @Composable get() = TextStyle(fontSize = Tokens.type("title"), fontWeight = FontWeight.Bold)

private const val HIGHLIGHT_MS = 1_500L
private const val FLASH_IN_MS = 120
private const val FLASH_OUT_MS = 600
private const val FLASH_ALPHA = 0.9f
private const val MOTION_MS = 180
private const val COMPACT_FONT_SCALE = 1.3f

/**
 * The table (design states T1–T5, T8, T12, T13, L1–L3, L6, L8–L10). Stateless: everything shown
 * comes from [ui] and [fx]; every button calls one of [actions].
 */
@Composable
fun TableScreen(ui: TableUiState, rules: RulesSummary, fx: TableEffectsState, actions: TableActions, menuOpen: Boolean, onMenu: (Boolean) -> Unit) {
    val c = Wpgd.colors
    val text = rememberTableText(ui.seat)
    Surface(color = c.bg, contentColor = c.ink, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag("table")) {
            // While the menu is open the table under it is out of reach for a screen reader too, as
            // it is for touch behind the scrim (round 3 #30): the menu is modal.
            Box(Modifier.fillMaxSize().then(if (menuOpen) Modifier.clearAndSetSemantics { } else Modifier)) {
                when (ui.screen) {
                    TableScreen.NO_TABLE -> Notice(stringResource(R.string.table_none), actions.leave)
                    TableScreen.CLOSED -> Notice(stringResource(R.string.table_closed), actions.leave)
                    TableScreen.FAILED -> Notice(stringResource(R.string.table_failed), actions.leave)
                    TableScreen.STARTING -> Starting()
                    TableScreen.TABLE, TableScreen.DEAL_RESULT, TableScreen.MATCH_OVER -> TableBody(ui, text, fx, actions) { onMenu(true) }
                }
            }
            fx.flash?.let { f ->
                BombFlash(f.cueId) { if (fx.flash == f) fx.flash = null }
            }
            if (menuOpen) TableMenu(ui, rules, actions, onClose = { onMenu(false) })
        }
    }
    fx.highlight?.let { h ->
        LaunchedEffect(h.cueId) {
            delay(HIGHLIGHT_MS)
            if (fx.highlight == h) fx.highlight = null
        }
    }
}

@Composable
private fun Notice(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(Tokens.space(5)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = bodyStyle)
        Spacer(Modifier.heightIn(min = Tokens.space(5)))
        Button(onClick = onBack, modifier = Modifier.heightIn(min = Tokens.touch).testTag("button:back")) {
            Text(stringResource(R.string.back_to_menu), style = labelStyle)
        }
    }
}

@Composable
private fun Starting() {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.heightIn(min = Tokens.space(4)))
        Text(stringResource(R.string.table_starting), style = bodyStyle, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun TableBody(ui: TableUiState, text: TableText, fx: TableEffectsState, actions: TableActions, openMenu: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val fontScale = LocalDensity.current.fontScale
        // The alternate layout (GDN-098): seats as a list, stacked buttons, for narrow screens and large text.
        val compact = maxWidth < 360.dp || fontScale >= COMPACT_FONT_SCALE
        val landscape = !compact && maxWidth > maxHeight && maxWidth >= 600.dp
        val pad = Tokens.space(3)
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(pad)) {
                Column(Modifier.weight(0.42f)) {
                    Header(ui, text, openMenu)
                    Banner(ui, text, actions)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(pad)) {
                        TableCentre(ui, text, fx, compact = false)
                    }
                }
                Column(Modifier.weight(0.58f)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(pad)) {
                        PlayerArea(ui, text, actions)
                    }
                    ActionBar(ui, text, actions, compact = false)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
                Header(ui, text, openMenu)
                Banner(ui, text, actions)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(pad)) {
                    TableCentre(ui, text, fx, compact)
                    PlayerArea(ui, text, actions)
                    Spacer(Modifier.heightIn(min = Tokens.space(2)))
                }
                ActionBar(ui, text, actions, compact)
            }
        }
    }
}

@Composable
private fun Header(ui: TableUiState, text: TableText, openMenu: () -> Unit) {
    val c = Wpgd.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Tokens.space(2)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val level = ui.level
            if (level != null) {
                Text(
                    stringResource(R.string.table_level, text.rank(level.dealLevel), ui.dealNumber),
                    style = titleStyle.copy(fontFamily = FontFamily.Serif),
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.table_levels, text.rank(level.ownLevel), text.rank(level.opponentLevel)), style = labelStyle, color = c.ink2)
            }
        }
        TextButton(onClick = openMenu, modifier = Modifier.heightIn(min = Tokens.touch).testTag("button:menu")) {
            Text(stringResource(R.string.table_menu), style = labelStyle, color = c.ink)
        }
    }
}

@Composable
private fun Banner(ui: TableUiState, text: TableText, actions: TableActions) {
    val room = actions.room
    // B8 mid-match (host): a watcher or a replacement asks; the same code comparison as in the lobby.
    if (room != null) ui.pendingJoiners.firstOrNull()?.let { AdmissionPanel(it, room) }
    AbsentSeats(ui, text, actions)
    val hostLost = ui.hostLostAttempts?.let { stringResource(R.string.join_host_lost, it) }
    val message = hostLost ?: text.connection(ui.connection) ?: text.room(ui.room) ?: return
    val c = Wpgd.colors
    Surface(color = c.surface2, shape = RoundedCornerShape(Tokens.radius("control")), modifier = Modifier.fillMaxWidth().padding(bottom = Tokens.space(3)).testTag("banner")) {
        Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
            Text(message, style = bodyStyle, color = c.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (ui.canReconnect) {
                Button(onClick = actions::reconnect, modifier = Modifier.heightIn(min = Tokens.touch).testTag("button:reconnect")) {
                    Text(stringResource(R.string.button_reconnect), style = labelStyle)
                }
            }
            if (ui.canResume) {
                Button(onClick = actions::resume, modifier = Modifier.heightIn(min = Tokens.touch).testTag("button:resume")) {
                    Text(stringResource(R.string.table_menu_resume), style = labelStyle)
                }
            }
        }
    }
}

/**
 * H1 (host): who is away and for how long; the host may let the AI stand in now when the room's
 * absence policy has one. Players see the same facts on the seat panels (T6, T7).
 */
@Composable
private fun AbsentSeats(ui: TableUiState, text: TableText, actions: TableActions) {
    if (ui.absentSeats.isEmpty()) return
    val c = Wpgd.colors
    Surface(color = c.surface2, shape = RoundedCornerShape(Tokens.radius("control")), border = BorderStroke(2.dp, c.accent), modifier = Modifier.fillMaxWidth().padding(bottom = Tokens.space(3)).testTag("host:absent")) {
        Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            for (a in ui.absentSeats) {
                val who = a.nickname ?: text.seat(a.seat)
                val line = when (val res = absentLine(a)) {
                    R.string.host_absent_grace -> stringResource(res, who, checkNotNull(a.graceSeconds))
                    else -> stringResource(res, who)
                }
                Text(line, style = bodyStyle, modifier = Modifier.testTag("host:absent:${a.seat.name}").semantics { liveRegion = LiveRegionMode.Polite })
                if (ui.canStandIn && !a.waitingForPlayer) {
                    Button(onClick = { actions.standIn(a.seat) }, modifier = Modifier.heightIn(min = Tokens.touch).testTag("host:standin:${a.seat.name}")) {
                        Text(stringResource(R.string.host_stand_in), style = labelStyle)
                    }
                }
            }
        }
    }
}

/** The line for one away seat; every line names its seat, so several waiting seats can be told apart (audit #34). */
@StringRes
internal fun absentLine(a: AbsentSeatUi): Int = when {
    a.waitingForPlayer -> R.string.host_awaiting_player
    a.graceSeconds != null -> R.string.host_absent_grace
    else -> R.string.host_absent
}

/** T9: a watcher asks to see this hand. Shown only when it is not this player's turn (G3). */
@Composable
private fun FollowRequests(ui: TableUiState, actions: TableActions) {
    val c = Wpgd.colors
    for (r in ui.followRequests) {
        Surface(color = c.surface, shape = RoundedCornerShape(Tokens.radius("control")), border = BorderStroke(2.dp, c.accent), modifier = Modifier.fillMaxWidth().testTag("follow:request")) {
            Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
                Text(stringResource(R.string.follow_request_title, r.observerName), style = bodyStyle.copy(fontWeight = FontWeight.Bold), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.follow_request_body), style = labelStyle, color = c.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
                    SecondaryButton(stringResource(R.string.follow_deny), true, "follow:deny", Modifier.weight(1f)) { actions.answerFollow(r.grantId, false) }
                    PrimaryButton(stringResource(R.string.follow_allow), true, "follow:allow", Modifier.weight(1f)) { actions.answerFollow(r.grantId, true) }
                }
            }
        }
    }
}

/** The other seats, the trick, the turn line and the latest announcement; or the result. */
@Composable
private fun ColumnScope.TableCentre(ui: TableUiState, text: TableText, fx: TableEffectsState, compact: Boolean) {
    val byRelation = ui.seats.associateBy { it.relation }
    val highlightSeat = fx.highlight?.seat
    @Composable
    fun seat(r: SeatRelation, modifier: Modifier) = byRelation[r]?.let { SeatPanel(it, ui, text, highlighted = highlightSeat == it.seat, modifier = modifier) }

    if (compact) {
        for (r in listOf(SeatRelation.PARTNER, SeatRelation.PREVIOUS, SeatRelation.NEXT, SeatRelation.SELF)) seat(r, Modifier.fillMaxWidth())
        TrickArea(ui, text, fx, Modifier.fillMaxWidth())
    } else {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            seat(SeatRelation.PARTNER, Modifier.weight(1.4f))
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            seat(SeatRelation.PREVIOUS, Modifier.weight(1f))
            TrickArea(ui, text, fx, Modifier.weight(1.6f))
            seat(SeatRelation.NEXT, Modifier.weight(1f))
        }
        seat(SeatRelation.SELF, Modifier.fillMaxWidth())
    }
    TurnLine(ui, text)
    fx.announce?.let { a ->
        Text(
            text.cue(a),
            style = labelStyle,
            color = Wpgd.colors.ink2,
            modifier = Modifier.testTag("event").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    ui.result?.let { ResultPanel(ui, text) }
}

private fun isWild(ui: TableUiState): (ViewCard) -> Boolean {
    val level = ui.level?.dealLevel ?: return { false }
    return { HandArrangement.isWildcard(it, level) }
}

/**
 * One seat: relative name, team, whether it acts now or owns the trick (marker and words), its
 * last action, its finish place, and a card count only when the profile made it public (GDN-079).
 * One node for a screen reader, whose description is exactly the words shown.
 */
@Composable
private fun SeatPanel(seat: SeatUi, ui: TableUiState, text: TableText, highlighted: Boolean, modifier: Modifier) {
    val c = Wpgd.colors
    val owner = ui.turn.trickOwner == seat.seat
    val name = text.relation(seat.relation)
    val team = text.team(seat.team)
    val acting = if (seat.acting) stringResource(R.string.seat_acting) else ""
    val trickOwner = if (owner) stringResource(R.string.seat_trick_owner) else ""
    val last = text.lastAction(seat.lastAction, isWild(ui))
    val finished = seat.finishPlace?.takeIf { seat.lastAction !is SeatLastAction.Finished }?.let { stringResource(R.string.seat_finished, it) }.orEmpty()
    val count = seat.publicCount?.let { stringResource(R.string.seat_public_count, it) }.orEmpty()
    val presence = presenceText(seat)
    val holder = seat.nickname?.takeIf { seat.relation != SeatRelation.SELF }.orEmpty()
    val summary = text.clauses(listOf(name, holder, team, presence, acting, trickOwner, last, finished, count))
    val border = when {
        highlighted -> BorderStroke(3.dp, c.accent)
        seat.acting -> BorderStroke(2.dp, c.accent)
        else -> BorderStroke(1.dp, c.line)
    }
    Surface(
        color = c.surface2,
        shape = RoundedCornerShape(Tokens.radius("control")),
        border = border,
        modifier = modifier.testTag("seat:${seat.seat.name}").clearAndSetSemantics { contentDescription = summary },
    ) {
        Column(Modifier.padding(Tokens.space(3)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
            FlowRow2 {
                Text(name, style = bodyStyle.copy(fontWeight = FontWeight.Bold), color = c.ink)
                if (holder.isNotEmpty()) Text(holder, style = labelStyle, color = c.ink)
                val ownTeam = ui.seats.firstOrNull { it.relation == SeatRelation.SELF }?.team
                Text(team, style = labelStyle, color = if (seat.team == ownTeam) c.teamUs else c.teamThem)
            }
            if (presence.isNotEmpty()) Text(presence, style = labelStyle.copy(fontWeight = FontWeight.Bold), color = c.ink, modifier = Modifier.testTag("presence:${seat.seat.name}"))
            if (seat.acting) Marked(stringResource(R.string.marker_acting), acting, c.accent)
            if (owner) Marked(stringResource(R.string.marker_trick_owner), trickOwner, c.ink)
            Text(last, style = labelStyle, color = c.ink2)
            if (finished.isNotEmpty()) Text(finished, style = labelStyle, color = c.ink2)
            if (count.isNotEmpty()) Text(count, style = labelStyle, color = c.ink)
        }
    }
}

/** T6 away (with the grace left), T7 the AI stands in, or a plain AI seat; empty for a present human. */
@Composable
private fun presenceText(seat: SeatUi): String = when (seat.presence) {
    Occupant.ABSENT -> stringResource(R.string.seat_presence_away, seat.graceSeconds ?: 0)
    Occupant.STAND_IN -> stringResource(R.string.seat_presence_stand_in)
    Occupant.AWAITING_PLAYER -> stringResource(R.string.seat_presence_awaiting)
    Occupant.AI -> stringResource(R.string.seat_presence_ai)
    else -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space(2)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) { content() }
}

/** A word with a shape marker in front (▲ acts, ● owns the trick): not colour alone (GDN-099). */
@Composable
private fun Marked(marker: String, words: String, color: Color) {
    FlowRow2 {
        Text(marker, style = labelStyle, color = color)
        Text(words, style = labelStyle.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

/** The current trick (GDN-078) and the tribute step's public transfers or resistance (T4, T5, D6). */
@Composable
private fun TrickArea(ui: TableUiState, text: TableText, fx: TableEffectsState, modifier: Modifier) {
    val c = Wpgd.colors
    val slide = remember { Animatable(0f) }
    val motion = fx.motion
    LaunchedEffect(motion?.cueId) {
        if (motion != null) {
            if (fx.startMotion(motion)) {
                slide.snapTo(1f)
                slide.animateTo(0f, tween(MOTION_MS))
            }
            // Done, or started by a trick area this one replaced (a resize): never replayed.
            if (fx.motion == motion) fx.motion = null
        }
    }
    val wild = isWild(ui)
    Box(modifier) {
        Surface(color = c.surface, shape = RoundedCornerShape(Tokens.radius("tile")), border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth().testTag("trick")) {
            Column(
                Modifier.padding(Tokens.space(3)).offset { IntOffset(0, (Tokens.space(5).toPx() * slide.value).roundToInt()) },
                verticalArrangement = Arrangement.spacedBy(Tokens.space(2)),
            ) {
                val tribute = ui.tribute
                if (tribute != null && (ui.phase == MatchPhase.TRIBUTE || ui.phase == MatchPhase.RETURN)) {
                    for (t in tribute.given) {
                        val card = text.cardLabel(t.card.suit, t.card.rank)
                        val line = t.to?.let { stringResource(R.string.tribute_given, text.seat(t.from), text.seat(it), card) }
                            ?: stringResource(R.string.tribute_given_unrouted, text.seat(t.from), card)
                        Text(line, style = labelStyle)
                    }
                    for (t in tribute.returned) Text(stringResource(R.string.tribute_returned, text.seat(t.from), text.seat(checkNotNull(t.to) { "a return always has a recipient" }), text.cardLabel(t.card.suit, t.card.rank)), style = labelStyle)
                }
                if (tribute?.resistedBy != null) {
                    Text(stringResource(R.string.tribute_resisted), style = labelStyle.copy(fontWeight = FontWeight.Bold), color = c.ink)
                }
                if (ui.trick.plays.isEmpty()) {
                    Text(stringResource(R.string.trick_empty), style = labelStyle, color = c.ink2)
                } else {
                    for (p in ui.trick.plays) {
                        val line = stringResource(R.string.trick_play, text.seat(p.seat), text.play(p.type, p.cards, p.declaration, wild))
                        if (p.isTop) {
                            Marked(stringResource(R.string.marker_trick_owner), line, c.ink)
                        } else {
                            Text(line, style = labelStyle, color = c.ink2)
                        }
                    }
                }
            }
        }
        if (fx.highlight != null) {
            Box(Modifier.matchParentSize().border(3.dp, c.accent, RoundedCornerShape(Tokens.radius("tile"))).testTag("highlight"))
        }
    }
}

@Composable
private fun TurnLine(ui: TableUiState, text: TableText) {
    val c = Wpgd.colors
    val color = if (ui.turn.youAct) c.accent else c.ink
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
        if (ui.turn.youAct) Text(stringResource(R.string.marker_acting), style = titleStyle, color = color, modifier = Modifier.clearAndSetSemantics { })
        Text(
            text.turn(ui.turn),
            style = titleStyle,
            color = color,
            modifier = Modifier.weight(1f).testTag("turn").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The settled deal explained (GDN-082; R1–R3). */
@Composable
private fun ResultPanel(ui: TableUiState, text: TableText) {
    val result = ui.result ?: return
    val c = Wpgd.colors
    Surface(color = c.surface, shape = RoundedCornerShape(Tokens.radius("tile")), border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth().testTag("result")) {
        Column(Modifier.padding(Tokens.space(5)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            Text(text.headline(result), style = TextStyle(fontSize = Tokens.type("heading"), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif), modifier = Modifier.semantics { heading() })
            for (line in result.lines) {
                Text(text.resultLine(line), style = bodyStyle)
                if (line is ResultLine.TributeNext) line.yourRole?.let { Text(text.tributeRole(it), style = bodyStyle.copy(fontWeight = FontWeight.Bold), color = c.ink) }
            }
            if (ui.screen == TableScreen.DEAL_RESULT && !ui.actions.continueToNextDeal) Text(stringResource(R.string.result_waiting_next), style = labelStyle, color = c.ink2)
        }
    }
}

/** Own hand, the play preview, pending and rejection feedback. */
@Composable
private fun PlayerArea(ui: TableUiState, text: TableText, actions: TableActions) {
    if (ui.screen != TableScreen.TABLE) return
    val c = Wpgd.colors
    FollowRequests(ui, actions)
    Feedback(ui, actions)
    PreviewArea(ui, text, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space(4)), modifier = Modifier.testTag("hand:count")) {
        Text(stringResource(R.string.hand_count, ui.hand.cardCount), style = labelStyle, color = c.ink2)
        if (ui.hand.selectedIds.isNotEmpty()) Text(stringResource(R.string.hand_selected, ui.hand.selectedIds.size), style = labelStyle, color = c.ink)
    }
    HandView(
        hand = ui.hand,
        describe = text::cardDescription,
        fourColor = ui.preferences.fourColorSuits,
        largeCards = ui.preferences.largeCards,
        callbacks = actions.hand,
    )
}

@Composable
private fun Feedback(ui: TableUiState, actions: TableActions) {
    val c = Wpgd.colors
    val rejection = ui.rejection
    if (rejection != null) {
        Surface(color = c.surface, shape = RoundedCornerShape(Tokens.radius("control")), border = BorderStroke(2.dp, c.danger), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
                Text(
                    stringResource(R.string.rejection_title, stringResource(Messages.of(rejection.messageKey))),
                    style = bodyStyle.copy(fontWeight = FontWeight.Bold),
                    color = c.danger,
                    modifier = Modifier.testTag("rejection").semantics { liveRegion = LiveRegionMode.Assertive },
                )
                TextButton(onClick = actions::dismissRejection, modifier = Modifier.heightIn(min = Tokens.touch).testTag("button:dismiss")) {
                    Text(stringResource(R.string.button_dismiss), style = labelStyle, color = c.ink)
                }
            }
        }
    }
    if (ui.pending) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
            CircularProgressIndicator(modifier = Modifier.width(Tokens.space(6)), strokeWidth = 2.dp)
            Text(stringResource(R.string.pending), style = labelStyle, color = c.ink, modifier = Modifier.testTag("pending").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** What the selection would play (GDN-076): the readings to choose from, and the exact payload. */
@Composable
private fun PreviewArea(ui: TableUiState, text: TableText, actions: TableActions) {
    val c = Wpgd.colors
    val cards = remember(ui.hand) { ui.hand.rows.flatten().flatMap { it.cards }.associate { it.id to ViewCard(it.id, it.suit, it.rank) } }
    val wildIds = remember(ui.hand) { ui.hand.rows.flatten().flatMap { it.cards }.filter { it.wildcard }.map { it.id }.toSet() }
    val wild: (ViewCard) -> Boolean = { it.id in wildIds }
    Column(Modifier.fillMaxWidth().testTag("preview"), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
        when (val p = ui.preview) {
            PlayPreview.Empty -> Unit
            is PlayPreview.NotPlayable -> Text(stringResource(Messages.of(p.problem.messageKey)), style = labelStyle, color = c.ink2, modifier = Modifier.testTag("preview:problem"))
            is PlayPreview.Options -> {
                val chosen = p.chosen
                if (chosen != null) {
                    Text(
                        stringResource(R.string.preview_play, text.payload(chosen.payload, cards, wild)),
                        style = bodyStyle.copy(fontWeight = FontWeight.Bold),
                        color = c.ink,
                        modifier = Modifier.testTag("preview:payload"),
                    )
                }
                if (p.options.size > 1) {
                    if (p.needsChoice) Text(stringResource(R.string.preview_choose, p.options.size), style = labelStyle, color = c.ink)
                    for (o in p.options) {
                        val selected = o.key == chosen?.key
                        Surface(
                            color = if (selected) c.selFill else c.surface2,
                            contentColor = c.suitBlack.takeIf { selected } ?: c.ink,
                            shape = RoundedCornerShape(Tokens.radius("chip")),
                            border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) c.sel else c.line),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.touch)
                                .testTag("option:${o.key}")
                                .selectable(selected = selected, enabled = ui.controlsEnabled, role = Role.RadioButton, onClick = { actions.chooseReading(o.key) }),
                        ) {
                            Box(Modifier.padding(Tokens.space(3)), contentAlignment = Alignment.CenterStart) {
                                Text(text.payload(o.payload, cards, wild), style = labelStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Buttons enabled only from the state's legality (GDN-050, GDN-088). The play button is twice as wide. */
@Composable
private fun ActionBar(ui: TableUiState, text: TableText, actions: TableActions, compact: Boolean) {
    val c = Wpgd.colors
    Surface(color = c.bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Tokens.space(3)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            when (ui.screen) {
                TableScreen.DEAL_RESULT -> PrimaryButton(stringResource(R.string.button_next_deal), ui.actions.continueToNextDeal, "button:next_deal", Modifier.fillMaxWidth(), actions::nextDeal)
                TableScreen.MATCH_OVER -> MatchOverActions(ui, actions)
                TableScreen.TABLE -> {
                    HandTools(ui, text, actions)
                    when {
                        ui.phase == MatchPhase.TRIBUTE && ui.turn.key == TurnKey.YOU_GIVE_TRIBUTE -> TributeActions(ui, actions)
                        ui.phase == MatchPhase.RETURN && ui.turn.key == TurnKey.YOU_RETURN_TRIBUTE ->
                            PrimaryButton(stringResource(R.string.button_return_tribute), ui.actions.returnTribute, "button:return", Modifier.fillMaxWidth(), actions::returnTribute)
                        ui.phase == MatchPhase.PLAYING -> PlayControls(ui, actions, compact)
                    }
                }
                else -> Unit
            }
        }
    }
}

/** The match is over: the host may start a rematch; a joined member leaves its seat; the host ends the table (nothing to resume). */
@Composable
private fun MatchOverActions(ui: TableUiState, actions: TableActions) {
    val c = Wpgd.colors
    if (ui.canRematch) PrimaryButton(stringResource(R.string.button_rematch), true, "button:rematch", Modifier.fillMaxWidth()) { actions.rematch() }
    if (ui.kind == TableKind.JOINED) Text(stringResource(R.string.rematch_waiting), style = labelStyle, color = c.ink2)
    SecondaryButton(stringResource(R.string.back_to_menu), true, "button:back", Modifier.fillMaxWidth()) {
        when (ui.kind) {
            TableKind.JOINED -> actions.leaveJoined()
            TableKind.HOSTED -> actions.prepareLeave()?.let { actions.leaveHosted(it.end()) } ?: actions.leave()
            TableKind.SOLO -> actions.leave()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TributeActions(ui: TableUiState, actions: TableActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space(3)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
        PrimaryButton(stringResource(R.string.button_give_tribute), ui.actions.giveTribute, "button:tribute", Modifier, actions::giveTribute)
        if (ui.actions.resist) SecondaryButton(stringResource(R.string.button_resist), true, "button:resist", Modifier, actions::resist)
    }
}

@Composable
private fun PlayControls(ui: TableUiState, actions: TableActions, compact: Boolean) {
    if (compact) {
        PrimaryButton(stringResource(R.string.button_play), ui.actions.play, "button:play", Modifier.fillMaxWidth(), actions::play)
        SecondaryButton(stringResource(R.string.button_pass), ui.actions.pass, "button:pass", Modifier.fillMaxWidth(), actions::pass)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space(3))) {
            SecondaryButton(stringResource(R.string.button_pass), ui.actions.pass, "button:pass", Modifier.weight(1f), actions::pass)
            PrimaryButton(stringResource(R.string.button_play), ui.actions.play, "button:play", Modifier.weight(2f), actions::play)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandTools(ui: TableUiState, text: TableText, actions: TableActions) {
    val grouped = ui.hand.rows.any { r -> r.any { it.kind == GroupKind.MANUAL } }
    val hasSelection = ui.hand.selectedIds.isNotEmpty()
    val next = HandSort.entries[(ui.preferences.handSort.ordinal + 1) % HandSort.entries.size]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space(2)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
        ToolButton(stringResource(R.string.button_sort, text.sort(ui.preferences.handSort)), true, "button:sort") { actions.sort(next) }
        if (hasSelection || !grouped) {
            ToolButton(stringResource(R.string.button_group), ui.selectionToolsEnabled, "button:group", actions::group)
        } else {
            ToolButton(stringResource(R.string.button_ungroup), true, "button:ungroup", actions::ungroup)
        }
        ToolButton(stringResource(R.string.button_clear), ui.selectionToolsEnabled, "button:clear", actions::clearSelection)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Wpgd.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.bg, disabledContainerColor = c.surface2, disabledContentColor = c.ink2),
        modifier = modifier.heightIn(min = Tokens.touch).testTag(tag),
    ) { Text(label, style = bodyStyle.copy(fontWeight = FontWeight.Bold)) }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Wpgd.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink, disabledContentColor = c.ink2),
        border = BorderStroke(1.dp, if (enabled) c.ink else c.line),
        modifier = modifier.heightIn(min = Tokens.touch).testTag(tag),
    ) { Text(label, style = bodyStyle) }
}

@Composable
private fun ToolButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val c = Wpgd.colors
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = Tokens.touch).testTag(tag)) {
        Text(label, style = labelStyle, color = if (enabled) c.ink else c.ink2)
    }
}

/** The bomb flash (T3). Never shown in reduced motion: the router sends a static highlight instead. */
@Composable
private fun BombFlash(key: String, onDone: () -> Unit) {
    val c = Wpgd.colors
    val alpha = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        alpha.animateTo(FLASH_ALPHA, tween(FLASH_IN_MS))
        alpha.animateTo(0f, tween(FLASH_OUT_MS))
        onDone()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.testTag("flash"), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.bomb_flash),
            style = TextStyle(fontSize = Tokens.type("hero"), fontWeight = FontWeight.Bold),
            color = c.bg,
            modifier = Modifier.background(c.accent, RoundedCornerShape(Tokens.radius("panel"))).padding(Tokens.space(6)),
        )
    }
}
