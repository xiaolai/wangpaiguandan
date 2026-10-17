package cn.wpgd.app

import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.DecisionOutcome
import cn.wpgd.app.core.ai.MemberSeatInputs
import cn.wpgd.app.core.match.LeadReason
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchPublicView
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TeamView
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.state.MemberView
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.PublicTable
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.data.HistoryRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.AiWorker
import cn.wpgd.app.owner.CommandRejection
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.PendingCommand
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.SoloConfig
import cn.wpgd.app.owner.SubmitRefusal
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.owner.TableSession
import cn.wpgd.app.owner.TableSnapshot
import cn.wpgd.app.owner.VirtualSessionLoop
import cn.wpgd.app.session.ai.DecisionExecutor
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.JcaP256KeyAgreement
import cn.wpgd.app.session.crypto.SecureBytes
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.table.PlayPreview
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.table.TableCue
import cn.wpgd.app.table.TableRules
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The packaged (signed) profiles, read from the repository's `rules/profiles/` (the files the app packages). */
object TestProfiles {
    private fun read(name: String): String {
        val f = File("../rules/profiles/$name.json")
        check(f.isFile) { "profile not found at ${f.absolutePath}" }
        return f.readText()
    }

    val profiles: RuleProfiles by lazy { RuleProfiles.parse(read("P1-base-v1"), read("M1-match-v1")) }
    val rules: TableRules by lazy { TableRules(profiles) }
    val memberInputs: MemberSeatInputs by lazy { MemberSeatInputs(profiles.base, profiles.match) }
}

/** Seeded bytes for tests only: reproducible deals, table ids and command ids. */
class SeededBytes(seed: Long) : SecureBytes {
    private val random = Random(seed)
    override fun next(size: Int): ByteArray = random.nextBytes(size)
}

fun testCrypto(seed: Long): CryptoSuite = CryptoSuite(SeededBytes(seed), JcaP256KeyAgreement(SecureRandom()), JcaAesGcm, JcaHmacSha256)

/**
 * Deterministic virtual-time executor for AI decisions: the decision still runs on the AI worker's
 * thread (never the loop or the test thread), and the loop waits for it, so runs are reproducible.
 */
class BlockingWorkerDecisionExecutor(private val worker: AiWorker) : DecisionExecutor {
    val threads = java.util.Collections.synchronizedSet(HashSet<Thread>())

    override fun execute(work: () -> DecisionOutcome, done: (DecisionOutcome) -> Unit): Cancellable {
        val outcome = worker.executor.submit<DecisionOutcome> { threads += Thread.currentThread(); work() }.get(30, TimeUnit.SECONDS)
        done(outcome)
        return Cancellable { }
    }
}

fun nickname(raw: String = "阿梅"): Nickname = (Nickname.parse(raw) as NicknameResult.Valid).nickname

/** A solo owner on a virtual loop: everything runs on the test thread except AI decisions. */
class VirtualSolo(
    seed: Long = 7,
    solo: SoloConfig = SoloConfig(aiDelayMs = 50),
    val history: HistoryRepository = HistoryRepository(InMemoryKeyValueStore()),
    val preferenceStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
) {
    val preferences = PreferencesRepository(preferenceStore)
    val loop = VirtualSessionLoop()
    var executor: BlockingWorkerDecisionExecutor? = null
    val owner = SessionOwner(
        OwnerDependencies(
            profiles = TestProfiles.profiles,
            crypto = testCrypto(seed),
            loopFactory = { loop },
            decisionExecutorFactory = { w, _, _ -> BlockingWorkerDecisionExecutor(w).also { executor = it } },
            history = history,
            solo = solo,
        ),
    )

    /** View model scope: unconfined, so state flows update synchronously on the test thread. */
    fun viewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) = TableViewModel(owner, preferences, scope)

    fun run(ms: Long) = loop.scheduler.runFor(ms)

    fun runUntil(maxMs: Long = 600_000, done: () -> Boolean): Boolean = loop.scheduler.runUntil(maxMs, done = done)

    val table: TableSnapshot? get() = owner.state.value.table

    fun start(): VirtualSolo {
        check(owner.startSolo(nickname()))
        check(runUntil(60_000) { table?.view?.match?.phase == MatchPhase.PLAYING }) { "solo table did not start: ${owner.state.value}" }
        return this
    }
}

/** [ScriptedHuman] (shared with the on-device tests) under the packaged test profiles. */
fun ScriptedHuman(vm: TableViewModel, view: () -> MatchMemberView?, seed: Long = 3) = ScriptedHuman(vm, view, TestProfiles.memberInputs, seed)

fun VirtualSolo.human(vm: TableViewModel, seed: Long = 3) = ScriptedHuman(vm, { table?.view }, seed)

/** Drives [solo] with [human] until [done] or [maxMs] of virtual time. */
fun VirtualSolo.drive(human: ScriptedHuman, maxMs: Long = 3_000_000, done: () -> Boolean): Boolean {
    val end = loop.now + maxMs
    while (!done()) {
        if (loop.now >= end) return false
        human.act()
        run(20)
    }
    return true
}

/** Runs until it is this member's turn to play (leading or following) with controls enabled. */
fun VirtualSolo.untilMyPlay(vm: TableViewModel, maxMs: Long = 600_000): Boolean =
    runUntil(maxMs) { vm.uiState.value.let { it.phase == MatchPhase.PLAYING && it.turn.youAct && it.controlsEnabled } }

/** A session whose state the test sets directly; records what is submitted (see [RecordingSession]). */
class FakeSession(view: MatchMemberView?) : RecordingSession(view, TestProfiles.rules)

fun card(id: String): ViewCard = TableFixtures.card(id)

/** A playing-phase view for [seat] at deal level [level] under the test profiles. */
fun playingView(
    seat: Seat = Seat.SOUTH,
    hand: List<String>,
    turn: Seat = seat,
    trick: List<PublicPlay> = emptyList(),
    passed: List<Seat> = emptyList(),
    finish: List<Seat> = emptyList(),
    level: Int = 2,
    revision: Long = 10,
    phase: MatchPhase = MatchPhase.PLAYING,
    tribute: TributeView? = null,
): MatchMemberView = TableFixtures.playingView(TestProfiles.profiles, seat, hand, turn, trick, passed, finish, level, revision, phase, tribute)
