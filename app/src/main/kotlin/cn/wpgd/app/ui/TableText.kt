package cn.wpgd.app.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import cn.wpgd.app.R
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.ViewAssignment
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.table.CardUi
import cn.wpgd.app.table.EffectOutput
import cn.wpgd.app.table.HandSort
import cn.wpgd.app.table.ResultExplanation
import cn.wpgd.app.table.ResultHeadline
import cn.wpgd.app.table.ResultLine
import cn.wpgd.app.table.SeatLabel
import cn.wpgd.app.table.SeatLastAction
import cn.wpgd.app.table.SeatRelation
import cn.wpgd.app.table.TeamLabel
import cn.wpgd.app.table.TributeRole
import cn.wpgd.app.table.TurnClarity
import cn.wpgd.app.table.TurnKey

/**
 * Turns the table's typed state into Chinese text from the string resources. Pure formatting: it
 * decides nothing, it only names what [cn.wpgd.app.table.TableUiState] already says. [me] is the
 * viewer's seat, so seats are named relatively (上家, 下家, 对家), never by compass.
 */
class TableText(private val res: Resources, private val me: Seat?) {
    private fun s(id: Int, vararg args: Any): String = res.getString(id, *args)

    private val sep: String get() = s(R.string.list_separator)

    fun clauses(parts: List<String>): String = parts.filter { it.isNotEmpty() }.joinToString(s(R.string.clause_separator))

    fun relation(r: SeatRelation): String = when (r) {
        SeatRelation.SELF -> s(R.string.seat_self)
        SeatRelation.NEXT -> s(R.string.seat_next)
        SeatRelation.PARTNER -> s(R.string.seat_partner)
        SeatRelation.PREVIOUS -> s(R.string.seat_previous)
    }

    fun seat(seat: Seat): String = if (me != null) {
        relation(SeatRelation.of(me, seat))
    } else {
        when (seat) {
            Seat.SOUTH -> s(R.string.seat_name_south)
            Seat.EAST -> s(R.string.seat_name_east)
            Seat.NORTH -> s(R.string.seat_name_north)
            Seat.WEST -> s(R.string.seat_name_west)
        }
    }

    fun seats(list: List<Seat>): String = list.joinToString(sep) { seat(it) }

    fun seatLabel(l: SeatLabel): String = when (l) {
        is SeatLabel.Relative -> relation(l.relation)
        is SeatLabel.Named -> seat(l.seat)
    }

    fun team(t: Team): String = when {
        me != null && t == me.team -> s(R.string.team_own)
        me != null -> s(R.string.team_opponents)
        t == Team.NORTH_SOUTH -> s(R.string.team_north_south)
        else -> s(R.string.team_east_west)
    }

    fun teamLabel(l: TeamLabel): String = when (l) {
        TeamLabel.Own -> s(R.string.team_own)
        TeamLabel.Opponents -> s(R.string.team_opponents)
        is TeamLabel.Named -> if (l.team == Team.NORTH_SOUTH) s(R.string.team_north_south) else s(R.string.team_east_west)
    }

    fun rank(rank: Int): String = when (rank) {
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        Ranks.ACE -> "A"
        Ranks.SMALL_JOKER -> s(R.string.rank_small_joker)
        Ranks.BIG_JOKER -> s(R.string.rank_big_joker)
        else -> rank.toString()
    }

    fun suitName(suit: Suit): String = when (suit) {
        Suit.SPADE -> s(R.string.suit_spade)
        Suit.HEART -> s(R.string.suit_heart)
        Suit.CLUB -> s(R.string.suit_club)
        Suit.DIAMOND -> s(R.string.suit_diamond)
        Suit.JOKER -> ""
    }

    /** A card by glyph and rank (`♥2`, `大王`), as the preview and the trick name it. */
    fun cardLabel(suit: Suit, rank: Int): String = if (suit == Suit.JOKER) rank(rank) else suit.glyph + rank(rank)

    /** What a screen reader says for a card of the hand: suit, rank and wildcard role. */
    fun cardDescription(card: CardUi): String = when {
        card.suit == Suit.JOKER -> rank(card.rank)
        card.wildcard -> s(R.string.card_description_wild, suitName(card.suit), rank(card.rank), s(R.string.card_wildcard))
        else -> s(R.string.card_description, suitName(card.suit), rank(card.rank))
    }

    fun combo(type: CombinationType): String = when (type) {
        CombinationType.SINGLE -> s(R.string.combo_single)
        CombinationType.PAIR -> s(R.string.combo_pair)
        CombinationType.TRIPLE -> s(R.string.combo_triple)
        CombinationType.TRIPLE_WITH_PAIR -> s(R.string.combo_triple_with_pair)
        CombinationType.STRAIGHT -> s(R.string.combo_straight)
        CombinationType.CONSECUTIVE_PAIRS -> s(R.string.combo_consecutive_pairs)
        CombinationType.CONSECUTIVE_TRIPLES -> s(R.string.combo_consecutive_triples)
        CombinationType.STRAIGHT_FLUSH -> s(R.string.combo_straight_flush)
        CombinationType.BOMB -> s(R.string.combo_bomb)
        CombinationType.FOUR_JOKERS -> s(R.string.combo_four_jokers)
    }

    private fun assigned(rank: Int, suit: Suit?): String = (suit?.glyph ?: "") + rank(rank)

    /**
     * A play as text: its type, the ranks of its natural cards, and each wildcard with what it was
     * declared as (`顺子 5 6 7 8 ♥2 当 9`). [isWild] tells which cards are wildcards at the deal level.
     */
    fun play(type: CombinationType, cards: List<ViewCard>, declaration: List<ViewAssignment>?, isWild: (ViewCard) -> Boolean): String {
        val decl = declaration.orEmpty()
        val wild = cards.filter(isWild).take(decl.size)
        val parts = cards.filter { it !in wild }.map { if (isWild(it)) cardLabel(it.suit, it.rank) else rank(it.rank) } +
            wild.zip(decl).map { (c, a) -> s(R.string.preview_wild_as, cardLabel(c.suit, c.rank), assigned(a.rank, a.suit)) }
        return s(R.string.play_text, combo(type), parts.joinToString(" "))
    }

    /**
     * The exact command body that will be sent, as text (GDN-076): built from the payload itself,
     * so the preview cannot describe anything but what [PlayBody] carries.
     */
    fun payload(body: PlayBody, cards: Map<String, ViewCard>, isWild: (ViewCard) -> Boolean): String {
        val type = checkNotNull(body.combinationType) { "a previewed play always states its type" }
        val faces = body.cardIds.map { checkNotNull(cards[it]) { "card $it is not in the hand" } }
        return play(type, faces, body.declaration?.map { ViewAssignment(it.rank, it.suit) }, isWild)
    }

    fun lastAction(a: SeatLastAction, isWild: (ViewCard) -> Boolean): String = when (a) {
        SeatLastAction.None -> s(R.string.seat_last_none)
        SeatLastAction.Passed -> s(R.string.seat_last_passed)
        is SeatLastAction.Played -> s(R.string.seat_last_played, play(a.type, a.cards, a.declaration, isWild))
        is SeatLastAction.Finished -> s(R.string.seat_finished, a.place)
    }

    fun turn(t: TurnClarity): String {
        // Relative names for a player (上家, 对家), compass names for a watcher.
        val owner = t.trickOwner?.let(::seat).orEmpty()
        val actor = t.actor?.let(::seat).orEmpty()
        val top = t.topType?.let(::combo).orEmpty()
        return when (t.key) {
            TurnKey.YOU_BEAT_OR_PASS -> s(R.string.turn_you_beat_or_pass, owner, top)
            TurnKey.OTHER_LEADS -> s(R.string.turn_other_leads, actor)
            TurnKey.OTHER_FOLLOWS -> s(R.string.turn_other_follows, actor, owner, top)
            TurnKey.WAITING_FOR_TRIBUTE, TurnKey.WAITING_FOR_RETURN -> s(Messages.of(t.key.messageKey), seats(t.waitingFor))
            else -> s(Messages.of(t.key.messageKey))
        }
    }

    fun cue(a: EffectOutput.Announce): String {
        val id = Messages.of(a.messageKey)
        val who = a.seat?.let(::seat).orEmpty()
        return when (a.messageKey) {
            "cue.play", "cue.bomb", "cue.pass" -> s(id, who)
            "cue.seat_finished", "cue.count_reported" -> s(id, who, a.count ?: 0)
            else -> s(id)
        }
    }

    fun connection(c: ConnectionState?): String? = when (c) {
        ConnectionState.CONNECTING -> s(R.string.banner_connecting)
        ConnectionState.STALE -> s(R.string.banner_stale)
        ConnectionState.DISCONNECTED -> s(R.string.banner_disconnected)
        ConnectionState.CONNECTED, null -> null
    }

    fun room(r: RoomCondition?): String? = when (r) {
        RoomCondition.PAUSED -> s(R.string.banner_paused)
        RoomCondition.SUSPENDED -> s(R.string.banner_suspended)
        RoomCondition.ENDED -> s(R.string.banner_ended)
        RoomCondition.QUARANTINED -> s(R.string.banner_quarantined)
        RoomCondition.UNKNOWN -> s(R.string.banner_unknown)
        RoomCondition.ACTIVE, null -> null
    }

    fun sort(sort: HandSort): String = when (sort) {
        HandSort.RANK -> s(R.string.sort_rank)
        HandSort.SUIT -> s(R.string.sort_suit)
        HandSort.COMBINATION -> s(R.string.sort_combination)
    }

    fun tributeKind(k: TributeKind): String = if (k == TributeKind.SINGLE) s(R.string.tribute_single) else s(R.string.tribute_double)

    fun tributeRole(r: TributeRole): String = when (r) {
        TributeRole.YOU_GIVE -> s(R.string.tribute_role_you_give)
        TributeRole.YOU_RECEIVE -> s(R.string.tribute_role_you_receive)
        TributeRole.PARTNER_GIVES -> s(R.string.tribute_role_partner_gives)
        TributeRole.PARTNER_RECEIVES -> s(R.string.tribute_role_partner_receives)
    }

    /** The settled deal's headline; a won deal or match names the winning team. */
    fun headline(r: ResultExplanation): String = when (r.headline) {
        ResultHeadline.TEAM_WON_DEAL, ResultHeadline.TEAM_WON_MATCH -> s(Messages.of(r.headline.messageKey), teamLabel(r.winner))
        else -> s(Messages.of(r.headline.messageKey))
    }

    fun resultLine(line: ResultLine): String {
        val id = Messages.of(line.messageKey)
        return when (line) {
            is ResultLine.Placing -> if (line.bestPlace == line.worstPlace) {
                s(id, seatLabel(line.seat), line.bestPlace)
            } else {
                s(R.string.result_placing_range, seatLabel(line.seat), line.bestPlace, line.worstPlace)
            }
            ResultLine.PlacesInferred -> s(id)
            is ResultLine.LevelChange -> s(id, teamLabel(line.team), rank(line.from), rank(line.to), line.gain)
            is ResultLine.APassed -> s(id, teamLabel(line.team))
            is ResultLine.AFailed -> if (line.limit != null) {
                s(id, teamLabel(line.team), line.failures, line.limit)
            } else {
                s(R.string.result_a_failed_no_limit, teamLabel(line.team), line.failures)
            }
            is ResultLine.AReset -> s(id, teamLabel(line.team), rank(line.toLevel))
            is ResultLine.AInterrupted -> s(id, teamLabel(line.team))
            is ResultLine.TributeNext -> s(id, line.givers.joinToString(sep, transform = ::seatLabel), line.receivers.joinToString(sep, transform = ::seatLabel), tributeKind(line.kind))
        }
    }
}

@Composable
fun rememberTableText(me: Seat?): TableText {
    val res = LocalResources.current
    return remember(res, me) { TableText(res, me) }
}
