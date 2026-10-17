package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.DecisionOutcome
import cn.wpgd.app.core.ai.Fallback
import cn.wpgd.app.core.ai.MemberSeatInputs
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.WireAssignment
import kotlin.test.assertTrue

/** Stands for a worker thread: the decision completes [delayMs] of scheduler time after it starts. */
internal class DelayedExecutor(private val scheduler: Scheduler, private val delayMs: Long) : DecisionExecutor {
    var started = 0
    var completed = 0
    var lastStartedAt = -1L

    override fun execute(work: () -> DecisionOutcome, done: (DecisionOutcome) -> Unit): Cancellable {
        started++
        lastStartedAt = scheduler.now
        var cancelled = false
        val timer = scheduler.schedule(delayMs) {
            if (!cancelled) {
                completed++
                done(work())
            }
        }
        return Cancellable { cancelled = true; timer.cancel() }
    }
}

internal val memberInputs = MemberSeatInputs(TestProfiles.base, TestProfiles.match)

internal fun AiAction.toBody(): CommandBody = when (this) {
    is AiAction.Play -> PlayBody(action.cards.map { it.id }, action.declaration?.map { WireAssignment(it.rank, it.suit) }, action.combination.type)
    AiAction.Pass -> PassBody
    is AiAction.GiveTribute -> GiveTributeBody(cardId)
    is AiAction.ReturnTribute -> ReturnTributeBody(cardId)
    AiAction.Resist -> ResistBody
}

/**
 * A scripted human at a client: whenever its own view shows a decision (play, tribute, return) and
 * nothing is pending, it waits [thinkMs] and submits the simplest legal action ([Fallback]) computed
 * from its own view alone, for the revision it saw.
 */
internal class ScriptedHuman(private val table: Table, val peer: Table.Peer, private val thinkMs: Long = 20) {
    private val client get() = peer.client
    private var scheduledFor = -2L
    var submitted = 0

    /** Refusals other than a stale revision (two humans acting at once in a tribute is a normal race). */
    var rejected = 0
    var staleRetries = 0
    val rejections = ArrayList<String>()

    init {
        val previous = client.onUpdate
        client.onUpdate = { previous?.invoke(); poke() }
    }

    fun poke() {
        if (client.phase != ClientPhase.ADMITTED || client.pendingCommands > 0) return
        val view = client.memberView ?: return
        val rev = client.revision
        if (rev == scheduledFor) return
        memberInputs.of(view)?.takeIf { it.hasChoice } ?: return
        scheduledFor = rev
        table.scheduler.schedule(thinkMs) {
            if (client.revision != rev || client.pendingCommands > 0 || client.phase != ClientPhase.ADMITTED) return@schedule
            val input = client.memberView?.let(memberInputs::of)?.takeIf { it.hasChoice } ?: return@schedule
            val id = client.submit(Fallback.choose(input).toBody()) ?: return@schedule
            submitted++
            watch(id)
        }
    }

    private fun watch(id: String) {
        table.scheduler.schedule(5) {
            val reply = client.replies[id]
            when {
                reply == null -> watch(id)
                reply is RejectionReceipt -> {
                    if (reply.code == RejectionCode.STALE_REVISION) {
                        staleRetries++
                        scheduledFor = -2L
                    } else {
                        rejected++
                        rejections += "${reply.code} ${reply.reason}"
                    }
                    poke()
                }
                else -> poke()
            }
        }
    }
}

/**
 * Drives the host's own steps (deal, tribute start, open play, settle) and the virtual clock until
 * [done] holds. Every AI and scripted-human decision happens through the session in between.
 */
internal fun Table.driveUntil(maxVirtualMs: Long = 50_000_000, done: (SessionHost) -> Boolean) {
    val end = scheduler.now + maxVirtualMs
    while (!done(host)) {
        assertTrue(scheduler.now < end, "not done within $maxVirtualMs virtual ms: phase ${host.state.phase} deal ${host.state.dealNumber} r${host.revision} status ${host.status}")
        when (host.state.phase) {
            MatchPhase.AWAITING_DEAL, MatchPhase.DEALT, MatchPhase.READY, MatchPhase.DEAL_ENDED -> host.advance()
            else -> Unit
        }
        run(50)
    }
}
