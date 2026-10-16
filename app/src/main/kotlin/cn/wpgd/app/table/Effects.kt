package cn.wpgd.app.table

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.PublicTable
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.TablePreferences

/** What happened at the table that deserves a cue. */
enum class CueKind(val messageKey: String) {
    DEAL_STARTED("cue.deal_started"),
    TRIBUTE_STARTED("cue.tribute_started"),
    PLAY("cue.play"),
    BOMB("cue.bomb"),
    PASS("cue.pass"),
    YOUR_TURN("cue.your_turn"),
    SEAT_FINISHED("cue.seat_finished"),
    COUNT_REPORTED("cue.count_reported"),
    DEAL_SETTLED("cue.deal_settled"),
    MATCH_OVER("cue.match_over"),
    COMMAND_REJECTED("cue.command_rejected"),
}

/**
 * One cue, keyed by a stable [id] made of the room epoch, the committed revision it first appeared
 * at, and what it is. The same event always has the same id, so a reconnect or resync can never
 * replay it ([EffectTracker]). [count] is a public card count (a profile report) or a finish place.
 */
data class TableCue(val id: String, val kind: CueKind, val seat: Seat?, val revision: Long, val count: Int? = null)

/**
 * Derives cues from consecutive projections of one member and never emits an id twice.
 *
 * - Only a projection whose revision is above every revision seen before in this epoch produces
 *   cues; a reconnect or resync delivers the same or an older revision and produces none.
 * - The first projection (start, rejoin after the owner was recreated) is a baseline: no cues.
 * - Ids are also remembered (bounded), as a second guard.
 */
class EffectTracker {
    private var epoch: Long? = null
    private var last: MatchMemberView? = null
    private var highWater = Long.MIN_VALUE
    private val emitted = LinkedHashSet<String>()

    fun observe(epoch: Long, revision: Long, view: MatchMemberView): List<TableCue> {
        if (epoch != this.epoch) {
            this.epoch = epoch
            last = null
            highWater = Long.MIN_VALUE
        }
        val previous = last
        if (revision <= highWater) return emptyList()
        highWater = revision
        last = view
        if (previous == null) return emptyList()
        return derive(epoch, revision, previous, view).filter(::remember)
    }

    /** A rejection of command [commandId]: one cue per command, ever. */
    fun rejected(epoch: Long, revision: Long, commandId: String): TableCue? =
        TableCue("e$epoch:c$commandId:rejected", CueKind.COMMAND_REJECTED, null, revision).takeIf(::remember)

    private fun remember(cue: TableCue): Boolean {
        if (!emitted.add(cue.id)) return false
        while (emitted.size > MEMORY) emitted.remove(emitted.first())
        return true
    }

    private fun derive(epoch: Long, rev: Long, prev: MatchMemberView, next: MatchMemberView): List<TableCue> {
        val out = ArrayList<TableCue>()
        fun cue(kind: CueKind, seat: Seat? = null, count: Int? = null, extra: String = "") =
            out.add(TableCue("e$epoch:r$rev:${kind.name}:${seat?.name ?: "-"}$extra", kind, seat, rev, count))

        val sameDeal = prev.match.dealNumber == next.match.dealNumber
        if (!sameDeal) cue(CueKind.DEAL_STARTED)
        if (next.match.phase == MatchPhase.TRIBUTE && (prev.match.phase != MatchPhase.TRIBUTE || !sameDeal)) cue(CueKind.TRIBUTE_STARTED)

        val prevTable = prev.deal?.table.takeIf { sameDeal }
        val nextTable = next.deal?.table
        if (nextTable != null) {
            val before = prevTable?.trick.orEmpty()
            val after = nextTable.trick
            val continues = before.size <= after.size && after.subList(0, before.size) == before
            val newPlays = if (continues) after.drop(before.size) else after
            // The pass that closes a trick commits together with the trick's end (D19), so the next
            // projection no longer lists it: cue every pass the closed trick still lacked.
            if (prevTable != null && before.isNotEmpty() && !continues) closingPasses(prevTable).forEach { cue(CueKind.PASS, it, extra = ":close") }
            newPlays.forEachIndexed { i, p -> cue(if (p.isBomb) CueKind.BOMB else CueKind.PLAY, p.seat, extra = ":${after.size - newPlays.size + i}") }
            val passesBefore = if (continues && newPlays.isEmpty()) prevTable?.passedSinceLastPlay.orEmpty() else emptyList()
            nextTable.passedSinceLastPlay.drop(passesBefore.size).forEach { cue(CueKind.PASS, it) }
            val finishedBefore = prevTable?.finishOrder.orEmpty()
            nextTable.finishOrder.forEachIndexed { i, s -> if (s !in finishedBefore) cue(CueKind.SEAT_FINISHED, s, count = i + 1) }
        }
        if (next.deal?.yourTurn == true && prev.deal?.yourTurn != true) cue(CueKind.YOUR_TURN, next.seat)

        val reportsBefore = if (sameDeal) prev.match.reports else emptyList()
        next.match.reports.filter { it !in reportsBefore }.forEach { cue(CueKind.COUNT_REPORTED, it.seat, count = it.count) }

        val settled = next.match.lastResult
        if (settled != null && settled.dealNumber != prev.match.lastResult?.dealNumber) cue(CueKind.DEAL_SETTLED, extra = ":d${settled.dealNumber}")
        if (next.match.winner != null && prev.match.winner == null) cue(CueKind.MATCH_OVER)
        return out
    }

    private val PublicPlay.isBomb: Boolean get() = type.isBombClass

    /** The seats whose passes ended [table]'s trick: every seat still holding cards, other than the winner, that had not passed yet, in play order. */
    private fun closingPasses(table: PublicTable): List<Seat> {
        val winner = table.trick.last().seat
        return generateSequence(winner.next) { it.next }.take(Seat.entries.size - 1)
            .filter { it !in table.finishOrder && it !in table.passedSinceLastPlay }
            .toList()
    }

    private companion object {
        const val MEMORY = 1_024
    }
}

enum class SoundId { DEAL, CARD_PLAY, BOMB, PASS, YOUR_TURN, FINISHED, DEAL_END, MATCH_END, ERROR }

enum class HapticPattern { TICK, HEAVY, ERROR }

enum class MotionMode { FULL, REDUCED }

/** One output of a cue. The UI layer performs it ([EffectSink]). */
sealed interface EffectOutput {
    val cueId: String

    /** Always present: the text (and screen-reader announcement) that makes muted play understandable. */
    data class Announce(override val cueId: String, val messageKey: String, val seat: Seat?, val count: Int?) : EffectOutput

    data class Sound(override val cueId: String, val sound: SoundId) : EffectOutput

    data class Haptic(override val cueId: String, val pattern: HapticPattern) : EffectOutput

    /** A full-table flash (bombs). Never produced in reduced motion. */
    data class Flash(override val cueId: String, val seat: Seat?) : EffectOutput

    /** Cards sliding onto the table. Never produced in reduced motion. */
    data class Motion(override val cueId: String, val seat: Seat?) : EffectOutput

    /** A static highlight of a seat's play: the reduced-motion replacement for [Flash] and [Motion]. */
    data class Highlight(override val cueId: String, val seat: Seat?) : EffectOutput
}

fun interface EffectSink {
    fun perform(output: EffectOutput)
}

/**
 * One table's memory of the sounds and haptics already performed: a cue id seen again is dropped.
 * A second guard behind the owner's own (it never emits an id twice, and its cue flow has no
 * replay). Scoped to one table, because cue ids repeat across tables (a new host starts at epoch 1).
 * Visual outputs pass through: the screen keys them by cue id itself.
 */
class CueGuard(private val memory: Int = MEMORY) {
    private val performed = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(output: EffectOutput): Boolean {
        if (output !is EffectOutput.Sound && output !is EffectOutput.Haptic) return true
        if (!performed.add(output.cueId + "/" + output::class.simpleName)) return false
        while (performed.size > memory) performed.remove(performed.first())
        return true
    }

    private companion object {
        const val MEMORY = 1_024
    }
}

/**
 * Turns a cue into outputs under the preferences (GDN-080). Pure.
 * - Muted sound or vibration removes only those outputs; [EffectOutput.Announce] is always there,
 *   plus a visual output for plays, so muted play stays understandable.
 * - Reduced motion removes every [EffectOutput.Flash] and [EffectOutput.Motion] and uses a static
 *   [EffectOutput.Highlight] instead.
 */
object EffectRouter {
    fun route(cue: TableCue, prefs: TablePreferences): List<EffectOutput> {
        val id = cue.id
        val out = ArrayList<EffectOutput>()
        out += EffectOutput.Announce(id, cue.kind.messageKey, cue.seat, cue.count)
        if (prefs.sound) sound(cue.kind)?.let { out += EffectOutput.Sound(id, it) }
        if (prefs.vibration) haptic(cue.kind)?.let { out += EffectOutput.Haptic(id, it) }
        when (cue.kind) {
            CueKind.BOMB -> out += if (prefs.reducedMotion) EffectOutput.Highlight(id, cue.seat) else EffectOutput.Flash(id, cue.seat)
            CueKind.PLAY -> out += if (prefs.reducedMotion) EffectOutput.Highlight(id, cue.seat) else EffectOutput.Motion(id, cue.seat)
            else -> Unit
        }
        return out
    }

    private fun sound(kind: CueKind): SoundId? = when (kind) {
        CueKind.DEAL_STARTED -> SoundId.DEAL
        CueKind.PLAY -> SoundId.CARD_PLAY
        CueKind.BOMB -> SoundId.BOMB
        CueKind.PASS -> SoundId.PASS
        CueKind.YOUR_TURN -> SoundId.YOUR_TURN
        CueKind.SEAT_FINISHED -> SoundId.FINISHED
        CueKind.DEAL_SETTLED -> SoundId.DEAL_END
        CueKind.MATCH_OVER -> SoundId.MATCH_END
        CueKind.COMMAND_REJECTED -> SoundId.ERROR
        CueKind.TRIBUTE_STARTED, CueKind.COUNT_REPORTED -> null
    }

    private fun haptic(kind: CueKind): HapticPattern? = when (kind) {
        CueKind.YOUR_TURN -> HapticPattern.TICK
        CueKind.BOMB -> HapticPattern.HEAVY
        CueKind.COMMAND_REJECTED -> HapticPattern.ERROR
        else -> null
    }
}
