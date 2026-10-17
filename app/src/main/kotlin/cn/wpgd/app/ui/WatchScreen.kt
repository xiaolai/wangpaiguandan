package cn.wpgd.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.wpgd.app.R
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.owner.RoomSession
import cn.wpgd.app.room.WatchNotice
import cn.wpgd.app.room.WatchSeatUi
import cn.wpgd.app.room.WatchUiState
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.table.HandArrangement
import cn.wpgd.app.table.SeatLastAction

/**
 * The watcher's screen (G1–G7): the public table only, with compass seat names; a request to see one
 * player's hand (G2, G3), that hand read-only while consent lasts (G4), and what ended it (G5, G6).
 * A watcher has no game control at all.
 */
@Composable
fun WatchScreen(w: WatchUiState, session: RoomSession, onDismissNotice: () -> Unit, onLeave: () -> Unit) {
    val c = Wpgd.colors
    val text = rememberTableText(null)
    val title = w.info?.tableName.orEmpty()
    RoomPage(stringResource(R.string.watch_title, title), "watch", null) {
        text.connection(w.connection)?.let { Text(it, style = roomBody, modifier = Modifier.testTag("banner")) }
        text.room(w.room)?.let { Text(it, style = roomBody, modifier = Modifier.testTag("banner:room")) }
        w.dealLevel?.let { Text(stringResource(R.string.table_level, text.rank(it), w.dealNumber), style = roomTitle) }
        Text(stringResource(R.string.watch_public_only), style = roomLabel, color = c.ink2)

        for (s in w.seats) WatchSeat(s, w, text, session)
        Surface(color = c.surface, shape = RoundedCornerShape(Tokens.radius("tile")), border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth().testTag("trick")) {
            Column(Modifier.padding(Tokens.space(3)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
                val wild: (ViewCard) -> Boolean = { card -> w.dealLevel?.let { HandArrangement.isWildcard(card, it) } ?: false }
                if (w.trick.plays.isEmpty()) Text(stringResource(R.string.trick_empty), style = roomLabel, color = c.ink2)
                for (p in w.trick.plays) Text(stringResource(R.string.trick_play, text.seat(p.seat), text.play(p.type, p.cards, p.declaration, wild)), style = roomLabel)
            }
        }
        Text(text.turn(w.turn), style = roomTitle, modifier = Modifier.testTag("turn").semantics { liveRegion = LiveRegionMode.Polite })

        w.follow?.let { f ->
            RoomPanel("watch:follow", emphasis = true) {
                val who = f.nickname ?: seatName(f.seat)
                Text(stringResource(R.string.watch_following, who), style = roomBody.copy(fontWeight = FontWeight.Bold))
                FollowedHand(f.hand.map { text.cardLabel(it.suit, it.rank) })
                RoomSecondary(stringResource(R.string.watch_stop), "watch:stop") { session.stopFollow(f.grantId) }
            }
        }
        w.waitingFor?.let { seat ->
            val who = w.seats.first { it.seat == seat }.nickname ?: seatName(seat)
            Text(stringResource(R.string.watch_waiting, who), style = roomBody, modifier = Modifier.testTag("watch:waiting").semantics { liveRegion = LiveRegionMode.Polite })
        }
        w.notice?.let { n ->
            RoomPanel("watch:notice") {
                Text(noticeText(n), style = roomBody, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                RoomSecondary(stringResource(R.string.button_dismiss), "watch:dismiss", onClick = onDismissNotice)
            }
        }
        if (w.followDisabled) Text(stringResource(R.string.watch_follow_disabled), style = roomLabel, color = c.ink2, modifier = Modifier.testTag("watch:disabled"))

        w.result?.let { r ->
            RoomPanel("result") {
                Text(text.headline(r), style = roomTitle)
                for (line in r.lines) Text(text.resultLine(line), style = roomLabel)
            }
        }
        Text(
            if (w.observers.isEmpty()) stringResource(R.string.lobby_no_watchers) else stringResource(R.string.lobby_watchers, w.observers.joinToString(stringResource(R.string.list_separator))),
            style = roomLabel,
            color = c.ink2,
        )
        RoomSecondary(stringResource(R.string.lobby_leave), "watch:leave", onClick = onLeave)
    }
}

@Composable
private fun noticeText(n: WatchNotice): String = when (n) {
    is WatchNotice.Denied -> stringResource(R.string.watch_denied, n.nickname ?: seatName(n.seat))
    is WatchNotice.Revoked -> stringResource(R.string.watch_revoked, n.nickname ?: seatName(n.seat))
    is WatchNotice.PlayerLeft -> stringResource(R.string.watch_player_left, n.nickname ?: seatName(n.seat))
    is WatchNotice.Ended -> stringResource(R.string.watch_ended, n.nickname ?: seatName(n.seat))
    is WatchNotice.Refused -> stringResource(R.string.watch_refused)
}

/** How a watcher's seat summary names the seat's holder: a resource ([withName]: formatted with the nickname), or the nickname itself. */
internal data class HolderText(@StringRes val res: Int?, val withName: Boolean)

/** A player who is away is named as away, never as if present (audit #33). */
internal fun holderText(occupant: Occupant?): HolderText = when (occupant) {
    Occupant.AI -> HolderText(R.string.seat_presence_ai, withName = false)
    Occupant.STAND_IN -> HolderText(R.string.seat_presence_stand_in, withName = false)
    Occupant.AWAITING_PLAYER -> HolderText(R.string.seat_presence_awaiting, withName = false)
    Occupant.ABSENT -> HolderText(R.string.watch_holder_away, withName = true)
    Occupant.HUMAN, Occupant.EMPTY, null -> HolderText(null, withName = true)
}

@Composable
private fun WatchSeat(s: WatchSeatUi, w: WatchUiState, text: TableText, session: RoomSession) {
    val c = Wpgd.colors
    val holder = holderText(s.occupant).let { h ->
        when {
            h.res == null -> s.nickname.orEmpty()
            h.withName -> stringResource(h.res, s.nickname.orEmpty())
            else -> stringResource(h.res)
        }
    }
    val wild: (ViewCard) -> Boolean = { card -> w.dealLevel?.let { HandArrangement.isWildcard(card, it) } ?: false }
    val acting = if (s.acting) stringResource(R.string.seat_acting) else ""
    // A seat whose last action is its finish already names the place (as on the player's seat panel).
    val finished = s.finishPlace?.takeIf { s.lastAction !is SeatLastAction.Finished }?.let { stringResource(R.string.seat_finished, it) }.orEmpty()
    val count = s.publicCount?.let { stringResource(R.string.seat_public_count, it) }.orEmpty()
    val name = stringResource(R.string.watch_seat, seatName(s.seat), holder)
    val summary = text.clauses(listOf(name, teamName(s.team), acting, text.lastAction(s.lastAction, wild), finished, count))
    Surface(
        color = c.surface2,
        shape = RoundedCornerShape(Tokens.radius("control")),
        border = BorderStroke(if (s.acting) 2.dp else 1.dp, if (s.acting) c.accent else c.line),
        modifier = Modifier.fillMaxWidth().testTag("watch:seat:${s.seat.name}"),
    ) {
        Column(Modifier.padding(Tokens.space(3)), verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
            Text(summary, style = roomLabel, modifier = Modifier.clearAndSetSemantics { contentDescription = summary })
            if (s.canAsk) {
                RoomSecondary(stringResource(R.string.watch_ask, s.nickname ?: seatName(s.seat)), "watch:ask:${s.seat.name}") { session.requestFollow(s.seat) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowedHand(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space(2)), modifier = Modifier.testTag("watch:hand")) {
        for (l in labels) Text(l, style = roomBody.copy(fontWeight = FontWeight.Bold))
    }
}
