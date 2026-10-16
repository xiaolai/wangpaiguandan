package cn.wpgd.app.owner

import cn.wpgd.app.diagnostics.LatencySamples
import cn.wpgd.app.diagnostics.failureKind
import cn.wpgd.app.session.diagnostics.DiagnosticField
import cn.wpgd.app.session.diagnostics.DiagnosticsReport
import cn.wpgd.app.session.diagnostics.FailureKind
import cn.wpgd.app.session.diagnostics.SessionDiagnostics
import cn.wpgd.app.session.diagnostics.TableKindToken
import cn.wpgd.app.core.ai.AiPolicies
import cn.wpgd.app.core.ai.AiPolicy
import cn.wpgd.app.core.ai.PolicyId
import cn.wpgd.app.core.match.DealResult
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.HistoryEntry
import cn.wpgd.app.data.HistoryRepository
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.RejoinTicket
import cn.wpgd.app.data.WallClock
import cn.wpgd.app.room.LeaveChoice
import cn.wpgd.app.room.LeavePrompt
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomNetwork
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.RoomStore
import cn.wpgd.app.room.TableCode
import cn.wpgd.app.session.ai.AiDriverConfig
import cn.wpgd.app.session.ai.AiSeatDriver
import cn.wpgd.app.session.ai.DecisionExecutor
import cn.wpgd.app.session.client.ClientConfig
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.EndReason
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.storage.TableRepository
import cn.wpgd.app.session.storage.UserConfirmation
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.session.wire.ReadyBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.RevokeBody
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.table.EffectTracker
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.table.TableCue
import cn.wpgd.app.table.TableRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What the table UI needs from whoever runs the table. [SessionOwner] is the implementation. */
interface TableSession {
    val state: StateFlow<OwnerState>

    /** Cues keyed by event id; never replayed (no replay buffer, and the tracker lives in the owner). */
    val cues: SharedFlow<TableCue>

    val rules: TableRules

    /** Sends one game command. At most one is pending: a second call while one is pending is refused. */
    fun submit(body: CommandBody): SubmitResult

    /** Host only: deals the next deal after a settled one. False when not possible now. */
    fun continueToNextDeal(): Boolean

    fun dismissRejection()

    /**
     * Rejoins over a new link with the stored ticket, by proof (same epoch, same seat, no approval)
     * after the connection went stale or dropped (T8). The view stays on screen meanwhile.
     */
    fun reconnect()

    /** Host only: pauses the table; every game command is refused until [resume] (audit A22). */
    fun pause()

    fun resume()
}

/**
 * WI-09: the room requests a screen can make, on top of the table's. Members: ready, follow, consent,
 * leave. Host only (ignored elsewhere): admission, seats, settings, start, rematch, stand-in, leaving.
 * Every request is checked again by the host; a refusal comes back as [OwnerState] data.
 */
interface RoomSession : TableSession {
    fun setReady(ready: Boolean)
    fun requestFollow(seat: Seat)
    fun answerFollow(grantId: String, approve: Boolean)
    fun stopFollow(grantId: String)

    /** A joined member leaves its room on purpose (its seat follows the room's leave policy). */
    fun leaveRoom()

    fun approve(connectionId: Long)
    fun deny(connectionId: Long)
    fun swapSeats(a: Seat, b: Seat)
    fun setAiSeat(seat: Seat)
    fun clearAiSeat(seat: Seat)
    fun updateSettings(settings: RoomSettings)
    fun startMatch()
    fun rematch()
    fun standInNow(seat: Seat)

    /** H9: what leaving the hosted table means now; null unless this device hosts a room. */
    fun prepareLeave(): LeavePrompt?

    fun leaveHosted(choice: LeaveChoice)
}

/** Settings of a solo table. */
data class SoloConfig(
    /** The human's seat. Solo always seats the human here; a nickname never chooses a seat. */
    val humanSeat: Seat = Seat.SOUTH,
    /** Delay before an AI seat acts, so a person can follow the table. */
    val aiDelayMs: Long = 700,
    /** Grace for a lost local link before the room pauses (solo never lets an AI play the human's seat). */
    val graceMs: Long = 30_000,
    val aiDeadlineMs: Long = 1_000,
)

/** Settings of room tables (WI-09). */
data class RoomConfig(
    /** The seat the host takes in a new room. */
    val hostSeat: Seat = Seat.SOUTH,
    val aiDelayMs: Long = 700,
    val aiDeadlineMs: Long = 1_000,
    /** How long a suspended table is kept (H9). */
    val retentionMs: Long = 12 * 60 * 60 * 1_000L,
    /** A joiner whose host went away tries again this often (H2). */
    val reconnectMs: Long = 5_000,
)

/** What the owner is built from. Platform parts are injected so the owner is plain JVM. */
class OwnerDependencies(
    val profiles: RuleProfiles,
    val crypto: CryptoSuite,
    /** Creates the session loop for one owner (production: [ExecutorSessionLoop]). */
    val loopFactory: (onError: (Throwable) -> Unit) -> SessionLoop,
    /** Creates the AI worker for one owner. */
    val aiWorkerFactory: () -> AiWorker = { AiWorker() },
    /** How an AI decision reaches the worker; production posts the outcome back to the loop. */
    val decisionExecutorFactory: (AiWorker, Scheduler, (Throwable) -> Unit) -> DecisionExecutor = ::WorkerDecisionExecutor,
    val aiPolicies: (PolicyId) -> AiPolicy = { AiPolicies[it] },
    val history: HistoryRepository? = null,
    val clock: WallClock = WallClock.SYSTEM,
    /** Dispatcher of the owner's own coroutine scope ([SessionOwner.scope]). */
    val scopeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val solo: SoloConfig = SoloConfig(),
    /** WI-09: how rooms reach each other. Null: rooms are unavailable. Solo never uses it (GDN-005). */
    val network: RoomNetwork? = null,
    /** WI-09: where hosted rooms are saved (suspend, restore). Null keeps hosted rooms in memory only. */
    val storage: Storage? = null,
    /** WI-09: the settings of saved hosted tables. */
    val rooms: RoomStore? = null,
    /** WI-09: rejoin tickets (capabilities) for rooms this device sat in. */
    val identity: IdentityRepository? = null,
    val room: RoomConfig = RoomConfig(),
    /**
     * The `tributeProfile` kill switch ([cn.wpgd.app.room.Features.TRIBUTE_PROFILE]). Off, no profile
     * is offered for a new table: [SessionOwner.startSolo] and [SessionOwner.hostRoom] refuse, while a
     * saved table restores and another host's room is joined under their frozen profile.
     */
    val newTables: Boolean = cn.wpgd.app.room.Features.TRIBUTE_PROFILE,
)

/** Counters for diagnostics and tests. No cards, capabilities or seeds. */
class OwnerMetrics {
    /**
     * WI-10: game command handed to the client → its receipt, in session-loop milliseconds (the time
     * a player waits for a move to count; HP-3 checks p95 < 1 s at 4+2 load).
     */
    val receiptMs = LatencySamples()
    val commandsSent = AtomicLong()
    val connections = AtomicLong()
    val cuesEmitted = AtomicLong()
    val failures = AtomicLong()
    val rejoinAttempts = AtomicLong()

    /** WI-07: compute time of each AI decision on the worker (budget: p95 ≤ 1 s on the minimum device). */
    val aiDecisionMs = LatencySamples()

    /** A lost host was found again at a new address (H2, GDN-085). */
    val roomMoves = AtomicLong()

    /** G2: an advert with the lost room's id but another host key was seen and not followed. */
    val roomMovesRefused = AtomicLong()
}

/**
 * Owns one table independently of any Activity (GDN-089): hold it at application scope. Solo play
 * (GDN-005) hosts an in-process [SessionHost] with three AI seats and joins it as a [SessionClient]
 * over the in-memory transport, with no discovery and no radio. A hosted room (WI-09) does the same
 * and also listens on the [RoomNetwork] for other members; a joined room is a [SessionClient] over
 * the network to someone else's host. The table survives rotation and activity recreation because it
 * lives here, not in the UI; [shutdown] cancels every job it owns.
 *
 * Threads:
 * - Every host and client call runs on the session loop ([OwnerDependencies.loopFactory]), never on
 *   the caller's (main) thread. Public methods are safe from any thread: they post to the loop.
 *   Network transports hand their callbacks to the loop too.
 * - AI decisions run on the AI worker and post back to the loop (GDN-102).
 * - [state] is immutable and published from the loop; it carries only this member's projection.
 */
class SessionOwner(private val deps: OwnerDependencies) : RoomSession {
    override val rules = TableRules(deps.profiles)

    private val stateFlow = MutableStateFlow(OwnerState.IDLE)
    override val state: StateFlow<OwnerState> = stateFlow.asStateFlow()

    private val cueFlow = MutableSharedFlow<TableCue>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val cues: SharedFlow<TableCue> = cueFlow.asSharedFlow()

    val metrics = OwnerMetrics()

    /** Scope for application-level collectors of this owner (cancelled by [shutdown]). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + deps.scopeDispatcher)

    /** Set by [shutdown]: no new request is accepted. Requests queued on the loop before it still run. */
    private val closed = AtomicBoolean(false)

    /** Set when the table is torn down: from then on no loop work runs. */
    private val stopped = AtomicBoolean(false)

    /** Makes [begin]'s check-and-start atomic with [shutdown]'s close: a started table is never revived. */
    private val lifecycle = Any()
    private val failure = AtomicReference<String?>(null)
    private val loop: SessionLoop = deps.loopFactory(::fail)
    private val tracked = TrackedScheduler(loop)
    private val worker: AiWorker = deps.aiWorkerFactory()

    /** Pending command and last rejection: written from any thread, read when publishing. */
    private val pending = AtomicReference<PendingCommand?>(null)
    private val rejection = AtomicReference<CommandRejection?>(null)
    private val tickets = AtomicLong()
    private val started = AtomicBoolean(false)

    // ---- Loop-confined -----------------------------------------------------------------------------
    private var table: OwnerTable? = null
    private val effects = EffectTracker()
    private var lastRecordedDeal: RecordedDeal? = null

    /** The session thread's scheduler; tests use it to observe that shutdown left nothing scheduled. */
    val scheduledTasks: Int get() = tracked.liveTasks

    /**
     * Starts a solo table (one human, three AI seats) if none is running. Idempotent: a recreated
     * Activity calling it again keeps the running table. Returns true when a new table starts; false
     * also when new tables are switched off ([OwnerDependencies.newTables]).
     */
    fun startSolo(nickname: Nickname): Boolean = deps.newTables && begin { createSolo(nickname) }

    /**
     * WI-09: hosts a new room under [settings] (B1): this device's player takes [RoomConfig.hostSeat],
     * the room is announced on the network and others may ask to join. Returns false when this owner
     * already runs a table, the build has no room network, or new tables are switched off.
     */
    fun hostRoom(nickname: Nickname, settings: RoomSettings): Boolean {
        if (deps.network == null || !deps.newTables) return false
        return begin { createHosted(nickname, settings, restoreId = null) }
    }

    /** WI-09 (H3): restores the saved hosted table [tableId] and announces it again. */
    fun restoreRoom(tableId: String, nickname: Nickname): Boolean {
        if (deps.network == null || deps.storage == null) return false
        return begin {
            val settings = deps.rooms?.load(tableId) ?: error("no settings saved for table $tableId")
            createHosted(nickname, settings, restoreId = tableId)
        }
    }

    /**
     * WI-09 (C4): asks to join [advert]'s room as a player at [seat] or, with [role] OBSERVER, as a
     * watcher (no seat). A stored rejoin ticket for the room is used, so a returning player is
     * re-admitted to its own seat without approval (H5). With [replace], the player asks the host to
     * take over [seat] from its away player (GDN-016); the host decides, and the old player's
     * capability ends on approval.
     */
    fun joinRoom(advert: RoomAdvert, role: Role, seat: Seat?, nickname: Nickname, replace: Boolean = false): Boolean {
        // A room found over one medium is reached over that medium only (WI-10: never silently switched).
        if (deps.network == null || deps.network.medium != advert.endpoint.medium) return false
        require((role == Role.PLAYER) == (seat != null)) { "a player claims a seat; a watcher claims none" }
        require(!replace || role == Role.PLAYER) { "only a player takes over a seat" }
        return begin { createJoined(advert, role, seat, nickname, replace) }
    }

    private fun begin(create: () -> Unit): Boolean {
        synchronized(lifecycle) {
            if (closed.get() || !started.compareAndSet(false, true)) return false
            stateFlow.update { it.copy(lifecycle = OwnerLifecycle.RUNNING) }
            loop.post { onLoop { create() } }
        }
        return true
    }

    override fun submit(body: CommandBody): SubmitResult {
        if (closed.get()) return SubmitResult.Refused(SubmitRefusal.SHUT_DOWN)
        if (failure.get() != null) return SubmitResult.Refused(SubmitRefusal.FAILED)
        val t = stateFlow.value.table ?: return SubmitResult.Refused(SubmitRefusal.NO_TABLE)
        if (t.connection != ConnectionState.CONNECTED) return SubmitResult.Refused(SubmitRefusal.NOT_CONNECTED)
        if (t.room != RoomCondition.ACTIVE) return SubmitResult.Refused(SubmitRefusal.ROOM_NOT_ACTIVE)
        val p = PendingCommand(tickets.incrementAndGet(), body, t.revision, t.epoch)
        // The single guard against duplicate commands (GDN-050): one pending command at a time.
        if (!pending.compareAndSet(null, p)) return SubmitResult.Refused(SubmitRefusal.ALREADY_PENDING)
        rejection.set(null)
        refreshLocal()
        loop.post { onLoop { send(p) } }
        return SubmitResult.Sent(p.ticket)
    }

    override fun continueToNextDeal(): Boolean {
        val t = stateFlow.value.table ?: return false
        val view = t.view ?: return false
        if (closed.get() || !t.hosting || view.match.phase != MatchPhase.AWAITING_DEAL || view.match.dealNumber == 0 || t.room != RoomCondition.ACTIVE) return false
        onHost { _, host -> if (host.state.phase == MatchPhase.AWAITING_DEAL && host.state.dealNumber > 0) host.advance() }
        return true
    }

    override fun dismissRejection() {
        rejection.set(null)
        refreshLocal()
    }

    /** Host only: pauses the table (game commands refused until [resume]). */
    override fun pause() = onHost { _, host -> host.pause() }

    override fun resume() = onHost { _, host -> host.resume() }

    /**
     * Drops the current link and rejoins over a new one with the stored ticket, by proof (same epoch,
     * same seat, no approval). The view stays on screen meanwhile; controls are disabled until rejoined.
     */
    override fun reconnect() = onTable { t ->
        when (t.kind) {
            TableKind.SOLO, TableKind.HOSTED -> t.link?.disconnect()
            TableKind.JOINED -> {
                t.reconnectTimer?.cancel()
                t.reconnectTimer = null
                t.transport?.close()
            }
        }
        connect(t)
    }

    // ---- Room requests (WI-09) ---------------------------------------------------------------------

    override fun setReady(ready: Boolean) = onTable { t ->
        val room = t.client.room?.takeIf { it.lobby } ?: return@onTable
        sessionCommand(t, ReadyBody(ready, room.settingsRevision), SessionAction.READY)
    }

    override fun requestFollow(seat: Seat) = onTable { t -> sessionCommand(t, FollowRequestBody(seat), SessionAction.FOLLOW_REQUEST) }

    override fun answerFollow(grantId: String, approve: Boolean) = onTable { t -> sessionCommand(t, ConsentBody(grantId, approve), SessionAction.CONSENT) }

    override fun stopFollow(grantId: String) = onTable { t -> sessionCommand(t, RevokeBody(grantId), SessionAction.REVOKE) }

    override fun leaveRoom() = onTable { t ->
        if (t.kind != TableKind.JOINED) return@onTable
        // Departing: the leave's receipt arrives while still admitted, and must not store the ticket again.
        t.leaving = true
        deps.identity?.forgetRejoin(t.tableId)
        sessionCommand(t, LeaveBody, SessionAction.LEAVE)
    }

    override fun approve(connectionId: Long) = onHost { t, host ->
        if (connectionId == t.ownConnection) return@onHost
        val p = host.pendingAdmissions().singleOrNull { it.connectionId == connectionId } ?: return@onHost
        // G2 (owner-delegated default): the host user compared this code with the joiner's screen before tapping.
        host.approve(connectionId, p.confirmationCode)
    }

    override fun deny(connectionId: Long) = onHost { t, host -> if (connectionId != t.ownConnection) host.deny(connectionId) }

    override fun swapSeats(a: Seat, b: Seat) = onHost { _, host -> host.swapSeats(a, b) }

    override fun setAiSeat(seat: Seat) = onHost { t, host -> host.setAi(seat, checkNotNull(t.driver).policy) }

    override fun clearAiSeat(seat: Seat) = onHost { _, host -> host.clearAiSeat(seat) }

    override fun updateSettings(settings: RoomSettings) = onHost { t, host ->
        if (host.updateSettings(settings.policy(), settings.recovery(checkNotNull(t.driver).policy))) {
            t.info = t.info?.copy(tableName = settings.tableName)
            deps.rooms?.takeIf { host.tableId != null }?.save(t.tableId, settings)
        }
    }

    override fun startMatch() = onHost { _, host -> host.start() }

    override fun rematch() = onHost { _, host -> host.rematch() }

    override fun standInNow(seat: Seat) = onHost { _, host -> host.standInNow(seat) }

    override fun prepareLeave(): LeavePrompt? {
        val t = stateFlow.value.table ?: return null
        if (t.kind != TableKind.HOSTED) return null
        val retention = deps.room.retentionMs
        return LeavePrompt(t.tableId, saved = t.hostSide?.saved == true, retentionMs = retention, expiresAt = deps.clock.nowMillis() + retention)
    }

    /**
     * H9: suspend (saved tables only; kept until exactly the expiry the prompt showed) or end (every
     * member is told, and the save is deleted: the host confirmed "cannot be resumed"). The owner then
     * stops serving the table; the caller closes it.
     */
    override fun leaveHosted(choice: LeaveChoice) = onHost { t, host ->
        val prompt = choice.prompt
        if (prompt.tableId != t.tableId) return@onHost
        val now = deps.clock.nowMillis()
        // The host reports false when the save failed: it paused the room and closed nothing. Then
        // the table stays up and announced, and the published room condition says it is not
        // suspended or ended, which is what the screen waits for before it leaves.
        when (choice) {
            is LeaveChoice.Suspend -> {
                if (!prompt.saved || host.tableId == null || prompt.expiresAt <= now) return@onHost
                if (!host.suspend(now, prompt.expiresAt)) return@onHost
            }
            is LeaveChoice.End -> {
                if (!host.end(now, now + 1)) return@onHost
                host.tableId?.let { id ->
                    deps.storage?.let { TableRepository(it).delete(id, UserConfirmation.CONFIRMED_BY_USER) }
                    deps.rooms?.forget(id)
                    deps.identity?.forgetRejoin(id)
                }
            }
        }
        // Nobody new finds the table from now; the members' connections get [LINGER_MS] to carry the
        // host's close (suspended or ended) before the listener drops them, so they do not mistake
        // it for a lost host and keep rejoining.
        withdraw(t)
        tracked.schedule(LINGER_MS) { onLoop { stopServing(t) } }
    }

    private fun withdraw(t: OwnerTable) {
        t.leaving = true
        if (t.listener != null) {
            announcedRoom.set(null)
            deps.network?.announcer?.withdraw(t.tableId)
        }
        t.advert = null
    }

    /** Test seam: a dead radio (every chunk vanishes, no close signalled), to exercise liveness (GDN-088). */
    internal fun blackholeLink(on: Boolean) = loop.post { onLoop { table?.link?.blackholed = on } }

    /** Test seam: runs [block] on the session loop with the host (never exposed to the UI). */
    internal fun withHost(block: (SessionHost) -> Unit) = loop.post { onLoop { table?.host?.let(block); publish() } }

    /** Test seam: runs [block] on the session loop with this member's client. */
    internal fun withClient(block: (SessionClient) -> Unit) = loop.post { onLoop { table?.client?.let(block); publish() } }

    /**
     * Ends everything this owner runs: closes the client, stops listening and announcing, cancels every
     * scheduled task (AI decisions and deadlines, heartbeats, retries, link deliveries), stops the AI
     * worker and the loop, and cancels [scope]. Idempotent; after it, nothing restarts and every submit
     * is refused.
     */
    fun shutdown() {
        synchronized(lifecycle) { if (!closed.compareAndSet(false, true)) return }
        if (loop.isOnLoop) {
            teardown()
        } else {
            // Posted behind every request already queued (leave, suspend, end): those run first, so a
            // leave reaches the host and a suspend is saved before the table closes. Their last
            // messages then get a bounded time to leave a backpressured link ([LAST_WORDS_MS]).
            val done = CountDownLatch(1)
            loop.post { teardownAfterLastWords(loop.now + LAST_WORDS_MS) { done.countDown() } }
            try {
                done.await(TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        tracked.cancelAll()
        worker.shutdown()
        loop.shutdown()
        if (!tornDown.get() && !loop.terminated) {
            // The loop stalled past the wait (a task that ignores the interrupt) and its queued
            // teardown was discarded. The table is loop-confined, so it is torn down when the loop
            // ends; until then nobody new finds the room, and the owner reports a failure, not a
            // finished shutdown.
            announcedRoom.getAndSet(null)?.let { id -> deps.network?.announcer?.withdraw(id) }
            fail(IllegalStateException("session loop stalled: the table is torn down when the loop ends"))
            scope.cancel()
            pending.set(null)
            stateFlow.value = OwnerState(OwnerLifecycle.FAILED, null, failure.get())
            loop.whenTerminated {
                teardown()
                stateFlow.value = OwnerState(OwnerLifecycle.SHUT_DOWN, null, failure.get())
            }
            return
        }
        // The loop stopped before running the queued teardown: nothing else touches the table now.
        teardown()
        scope.cancel()
        pending.set(null)
        stateFlow.value = OwnerState(OwnerLifecycle.SHUT_DOWN, null, failure.get())
    }

    private val tornDown = AtomicBoolean(false)

    /**
     * The room id this owner currently announces, or null. Written on the loop; read by [shutdown]
     * on its own thread when the loop is stalled (announcers are thread-safe, [RoomAnnouncer]).
     */
    private val announcedRoom = AtomicReference<String?>(null)

    /**
     * Tears down once the table's last messages are out, or at [deadline] (loop time), then calls
     * [done]. On the loop. Network callbacks keep running meanwhile ([post] drops them only once
     * torn down), so a refused chunk is sent when the link has room again.
     */
    private fun teardownAfterLastWords(deadline: Long, done: () -> Unit) {
        val t = table
        if (t == null || stopped.get() || !lastWordsPending(t) || loop.now >= deadline) {
            try {
                teardown()
            } finally {
                done()
            }
            return
        }
        loop.schedule(LAST_WORDS_POLL_MS) { teardownAfterLastWords(deadline, done) }
    }

    /**
     * A joined member's leave has no reply yet (the host has not acknowledged it and the connection
     * still stands), or a leaving host still lingers so its close reaches the members ([leaveHosted]).
     */
    private fun lastWordsPending(t: OwnerTable): Boolean = when (t.kind) {
        TableKind.JOINED -> t.client.phase == ClientPhase.ADMITTED && SessionAction.LEAVE in t.sessionCommands.values
        TableKind.HOSTED -> t.leaving && t.listener != null
        TableKind.SOLO -> false
    }

    /** Stops serving the table and closes its client, once. Runs on the loop, or after the loop stopped. */
    private fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        stopped.set(true)
        table?.let(::stopServing)
        table?.client?.close()
        tracked.cancelAll()
    }

    /** True once the AI worker and the loop have stopped. */
    val terminated: Boolean get() = worker.terminated && loop.terminated && tracked.isClosed

    // ---- Diagnostics (WI-12) -----------------------------------------------------------------------

    /**
     * This owner's local diagnostics export (GDN-108, P8): [common] (build, profiles, crash count),
     * then the owner's counters and, for an open table, its host's and client's state and counters.
     * Built and rendered on the session loop, because the host and client are confined to it. Null
     * when the owner is shut down or the loop does not answer within [timeoutMs] (a stall); the
     * caller then exports without the session lines. A defect in the export is rethrown here, on the
     * caller's thread, and never fails the table.
     */
    fun diagnostics(common: (DiagnosticsReport.Builder) -> Unit, timeoutMs: Long = 2_000): String? {
        if (closed.get()) return null
        val build = {
            val b = DiagnosticsReport.Builder()
            common(b)
            val t = table
            b.token(
                DiagnosticField.OWNER_TABLE,
                when (t?.kind) {
                    null -> TableKindToken.NONE
                    TableKind.SOLO -> TableKindToken.SOLO
                    TableKind.HOSTED -> TableKindToken.HOSTED
                    TableKind.JOINED -> TableKindToken.JOINED
                },
            )
            b.flag(DiagnosticField.OWNER_LOOP_RESPONSIVE, true)
            writeCounters(b)
            t?.host?.let { SessionDiagnostics.host(b, it) }
            t?.client?.let { SessionDiagnostics.client(b, it) }
            b.build().render()
        }
        if (loop.isOnLoop) return build()
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        loop.post {
            try {
                if (!closed.get()) result.set(build())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        error.get()?.let { throw IllegalStateException("diagnostics export failed", it) }
        return result.get()
    }

    /** The owner's own counters (thread-safe: atomics and the latency ring). Never the failure message. */
    fun writeCounters(b: DiagnosticsReport.Builder) {
        b.count(DiagnosticField.OWNER_COMMANDS_SENT, metrics.commandsSent.get())
        b.count(DiagnosticField.OWNER_CONNECTIONS, metrics.connections.get())
        b.count(DiagnosticField.OWNER_REJOIN_ATTEMPTS, metrics.rejoinAttempts.get())
        b.count(DiagnosticField.OWNER_ROOM_MOVES, metrics.roomMoves.get())
        b.count(DiagnosticField.OWNER_ROOM_MOVES_REFUSED, metrics.roomMovesRefused.get())
        b.count(DiagnosticField.OWNER_FAILURES, metrics.failures.get())
        b.token(DiagnosticField.OWNER_FAILURE_KIND, failureKind.get())
        b.count(DiagnosticField.OWNER_RECEIPTS, metrics.receiptMs.count)
        b.millis(DiagnosticField.OWNER_RECEIPT_MS_P50, metrics.receiptMs.percentile(50.0))
        b.millis(DiagnosticField.OWNER_RECEIPT_MS_P95, metrics.receiptMs.percentile(95.0))
        b.millis(DiagnosticField.OWNER_RECEIPT_MS_MAX, if (metrics.receiptMs.count == 0L) -1 else metrics.receiptMs.max)
        b.count(DiagnosticField.OWNER_AI_DECISIONS, metrics.aiDecisionMs.count)
        b.millis(DiagnosticField.OWNER_AI_DECISION_MS_P50, metrics.aiDecisionMs.percentile(50.0))
        b.millis(DiagnosticField.OWNER_AI_DECISION_MS_P95, metrics.aiDecisionMs.percentile(95.0))
        b.millis(DiagnosticField.OWNER_AI_DECISION_MS_MAX, if (metrics.aiDecisionMs.count == 0L) -1 else metrics.aiDecisionMs.max)
    }

    private val failureKind = AtomicReference(FailureKind.NONE)

    // ---- Loop work ---------------------------------------------------------------------------------

    private inline fun onLoop(block: () -> Unit) {
        if (stopped.get()) return
        check(loop.isOnLoop) { "session work off the session loop" }
        block()
    }

    /** Runs [block] on the loop with the current table, then republishes. Refused once shut down. */
    private fun onTable(block: (OwnerTable) -> Unit) {
        if (closed.get()) return
        loop.post {
            onLoop {
                table?.let(block)
                publish()
            }
        }
    }

    /** Runs [block] on the loop when this device hosts the table (solo or room). */
    private fun onHost(block: (OwnerTable, SessionHost) -> Unit) = onTable { t -> t.host?.let { block(t, it) } }

    /**
     * Hands a network callback to the loop; dropped once the table is torn down. Not at [shutdown]:
     * until the teardown the link must still carry the table's last messages (a leave waiting for room).
     */
    private fun post(task: () -> Unit) {
        tracked.schedule(0) { if (!stopped.get()) task() }
    }

    private fun newDriver(seed: Long, deadlineMs: Long): AiSeatDriver = AiSeatDriver(
        scheduler = tracked,
        config = AiDriverConfig(deadlineMs = deadlineMs, tieBreakSeed = seed),
        executor = TimedDecisionExecutor(deps.decisionExecutorFactory(worker, tracked, ::fail), metrics.aiDecisionMs),
        policies = deps.aiPolicies,
    )

    private fun seed(): Long = deps.crypto.random.next(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    private fun hexId(prefix: String): String = prefix + deps.crypto.random.next(8).joinToString("") { "%02x".format(it) }

    /** A [rejoin] ticket rejoins by proof (its capability is never sent) and pins its host key (G2). */
    private fun clientConfig(roomId: String, role: Role, nickname: Nickname, seat: Seat?, rejoin: RejoinTicket? = null) =
        ClientConfig(
            roomId, role, nickname.value, deps.profiles.base.hash, deps.profiles.match.hash, seat = seat,
            rejoinCapability = rejoin?.capability, pinnedHostKey = rejoin?.hostKey,
        )

    private fun createSolo(nickname: Nickname) {
        val solo = deps.solo
        val tableId = hexId("solo-")
        val seed = seed()
        val driver = newDriver(seed, solo.aiDeadlineMs)
        // Solo: a lost local link pauses the room after the grace; an AI never plays the human's cards.
        val recovery = RecoveryPolicy(graceMs = solo.graceMs, afterGrace = AbsencePolicy.PAUSE, afterLeave = AbsencePolicy.PAUSE, substitute = null)
        val host = SessionHost(HostConfig(roomId = tableId, recovery = recovery, aiDelayMs = solo.aiDelayMs), deps.profiles.base, deps.profiles.match, deps.crypto, tracked)
        for (seat in Seat.entries) if (seat != solo.humanSeat) check(host.setAi(seat, driver.policy)) { "could not seat an AI at $seat" }
        val network = InMemoryNetwork(tracked, seed).apply { captureEnabled = false }
        val client = SessionClient(clientConfig(tableId, Role.PLAYER, nickname, solo.humanSeat), deps.crypto, tracked)
        install(OwnerTable(tableId, TableKind.SOLO, client, null, host, driver, network, null))
    }

    private fun createHosted(nickname: Nickname, settings: RoomSettings, restoreId: String?) {
        val net = checkNotNull(deps.network)
        val roomId = restoreId ?: hexId("t")
        val seed = seed()
        val driver = newDriver(seed, deps.room.aiDeadlineMs)
        val config = HostConfig(roomId = roomId, recovery = settings.recovery(driver.policy), policy = settings.policy(), aiDelayMs = deps.room.aiDelayMs)
        val storage = deps.storage
        val host = try {
            when {
                restoreId != null -> when (val r = SessionHost.restore(config, deps.profiles.base, deps.profiles.match, deps.crypto, tracked, checkNotNull(storage), roomId, deps.clock.nowMillis())) {
                    is SessionHost.RestoreResult.Restored -> r.host
                    else -> error("table $roomId cannot be restored: ${r::class.simpleName}")
                }
                storage != null -> SessionHost.create(config, deps.profiles.base, deps.profiles.match, deps.crypto, tracked, storage, roomId, deps.clock.nowMillis())
                else -> SessionHost(config, deps.profiles.base, deps.profiles.match, deps.crypto, tracked)
            }
        } catch (e: IOException) {
            error("the table could not be saved: ${e.message}")
        }
        if (storage != null) {
            deps.rooms?.save(roomId, settings)
            deps.rooms?.saveMedium(roomId, net.medium)
        }
        // The host's own ticket must name the restored room key; one from another key is not used.
        val ticket = restoreId?.let { deps.identity?.rejoinTicket(it) }?.takeIf { it.hostKey == host.hostKeyFingerprint }
        val client = SessionClient(clientConfig(roomId, Role.PLAYER, nickname, deps.room.hostSeat, ticket), deps.crypto, tracked)
        val local = InMemoryNetwork(tracked, seed).apply { captureEnabled = false }
        val t = OwnerTable(roomId, TableKind.HOSTED, client, RoomInfo(roomId, settings.tableName, TableCode.of(roomId), nickname.value, net.medium), host, driver, local, null)
        install(t)
        val listener = net.listen(::post) { transport -> if (table === t && !closed.get()) host.accept(transport) else transport.close() }
        t.listener = listener
        listener.onFailure {
            post {
                if (table === t && t.listener === listener && !t.hostingFailed) {
                    t.hostingFailed = true
                    publish()
                }
            }
        }
        announce(t)
    }

    private fun createJoined(advert: RoomAdvert, role: Role, seat: Seat?, nickname: Nickname, replace: Boolean) {
        // G2: a ticket is used only with the host key it was issued by; for another key the member asks afresh.
        val ticket = if (role == Role.PLAYER && !replace) deps.identity?.rejoinTicket(advert.roomId)?.takeIf { it.hostKey == advert.hostKey } else null
        val client = SessionClient(clientConfig(advert.roomId, role, nickname, seat, ticket).copy(replace = replace), deps.crypto, tracked)
        val t = OwnerTable(
            advert.roomId, TableKind.JOINED, client, RoomInfo(advert.roomId, advert.tableName, advert.tableCode, advert.hostName, advert.endpoint.medium),
            host = null, driver = null, local = null, endpoint = advert.endpoint,
        )
        t.usedTicket = ticket != null
        install(t)
    }

    private fun install(t: OwnerTable) {
        t.client.onUpdate = { onLoop { onClientUpdate(t) } }
        table = t
        connect(t)
        watch()
        publish()
    }

    /**
     * The host has no change callback (members join, seats change, grace timers and AI turns run on
     * its own timers), so this tick republishes within [WATCH_MS] of any host-side change and settles
     * pending commands and rejoins. Client changes, including a timer-driven stale link, arrive
     * through the client's own update callback.
     */
    private fun watch() {
        tracked.schedule(WATCH_MS) {
            onLoop {
                table?.let {
                    resolvePending(it)
                    scheduleRejoin(it)
                }
                publish()
                watch()
            }
        }
    }

    private fun connect(t: OwnerTable) {
        metrics.connections.incrementAndGet()
        when (t.kind) {
            TableKind.SOLO, TableKind.HOSTED -> {
                val link = checkNotNull(t.local).link()
                t.link = link
                t.ownConnection = checkNotNull(t.host).accept(link.hostEnd)
                t.client.connect(link.clientEnd)
            }
            TableKind.JOINED -> {
                val transport = checkNotNull(deps.network).connect(checkNotNull(t.endpoint), ::post)
                t.transport = transport
                t.client.connect(transport)
            }
        }
    }

    private fun onClientUpdate(t: OwnerTable) {
        if (table !== t) return
        val client = t.client
        selfAdmit(t)
        if (client.phase == ClientPhase.ADMITTED) {
            t.everAdmitted = true
            t.reconnectAttempts = 0
            t.reconnectTimer?.cancel()
            t.reconnectTimer = null
            stopFollowing(t)
            rememberTicket(t)
        }
        hostStep(t)
        autoReady(t)
        retryWithoutTicket(t)
        scheduleRejoin(t)
        resolvePending(t)
        resolveSessionCommands(t)
        if (t.kind == TableKind.HOSTED) announce(t)
        publish()
    }

    /** This device is the host: it approves its own player with that player's own code. */
    private fun selfAdmit(t: OwnerTable) {
        val host = t.host ?: return
        if (t.client.phase != ClientPhase.PENDING) return
        val code = t.client.confirmationCode ?: return
        val own = t.ownConnection ?: return
        val p = host.pendingAdmissions().singleOrNull { it.connectionId == own } ?: return
        if (t.approvedConnection == own) return
        t.approvedConnection = own
        val r = host.approve(own, code)
        check(r is SessionHost.ApprovalResult.Admitted) { "own admission failed: $r" }
    }

    /**
     * The host's own steps. Solo deals its first deal by itself; a room's first deal waits for the
     * host user's start (the lobby). Tribute start, open play and settlement run by themselves.
     */
    private fun hostStep(t: OwnerTable) {
        val host = t.host ?: return
        if (host.status != RoomStatus.Active) return
        val auto = when (host.state.phase) {
            MatchPhase.AWAITING_DEAL -> t.kind == TableKind.SOLO && host.state.dealNumber == 0
            MatchPhase.DEALT, MatchPhase.READY, MatchPhase.DEAL_ENDED -> true
            else -> false
        }
        if (auto) host.advance()
    }

    /** The hosting player chose the settings, so it is ready for each revision of them. */
    private fun autoReady(t: OwnerTable) {
        if (t.kind != TableKind.HOSTED || t.client.phase != ClientPhase.ADMITTED) return
        val lobby = t.client.room?.lobby == true
        // A rematch returns to the lobby under the same settings revision with every ready cleared.
        if (lobby && !t.wasInLobby) t.readySentFor = -1
        t.wasInLobby = lobby
        val room = t.client.room?.takeIf { it.lobby } ?: return
        val mine = room.seats.firstOrNull { it.seat == room.yourSeat } ?: return
        if (mine.occupant != Occupant.HUMAN || mine.ready || t.readySentFor == room.settingsRevision) return
        t.readySentFor = room.settingsRevision
        t.client.submit(ReadyBody(true, room.settingsRevision))
    }

    /** Stores this player's capability with the host key it was issued by, so the same device can rejoin its seat later (H5, G2). */
    private fun rememberTicket(t: OwnerTable) {
        val identity = deps.identity ?: return
        if (t.kind == TableKind.SOLO || t.client.config.role != Role.PLAYER || t.leaving) return
        if (t.kind == TableKind.HOSTED && t.host?.tableId == null) return
        val cap = t.client.capability ?: return
        val hostKey = t.client.hostKeyFingerprint ?: return
        val ticket = RejoinTicket(cap, hostKey)
        if (identity.rejoinTicket(t.tableId) != ticket) identity.rememberRejoin(t.tableId, cap, hostKey)
    }

    /** A stored ticket the host no longer knows (a new table, or this member was replaced): ask afresh once. */
    private fun retryWithoutTicket(t: OwnerTable) {
        val c = t.client
        if (t.kind != TableKind.JOINED || !t.usedTicket || c.phase != ClientPhase.REJECTED || c.denial != AdmissionDenial.CAPABILITY_INVALID) return
        deps.identity?.forgetRejoin(t.tableId)
        t.usedTicket = false
        val config = c.config.copy(rejoinCapability = null, pinnedHostKey = null)
        t.client = SessionClient(config, deps.crypto, tracked)
        t.client.onUpdate = { onLoop { onClientUpdate(t) } }
        connect(t)
    }

    /** H2: a joined member whose host went away keeps trying to rejoin (same capability, no approval). */
    private fun scheduleRejoin(t: OwnerTable) {
        val c = t.client
        if (t.kind != TableKind.JOINED || !t.everAdmitted || c.phase != ClientPhase.CLOSED || t.reconnectTimer != null) return
        if (c.closeReason in FINAL_CLOSES) return stopFollowing(t)
        followRoom(t)
        t.reconnectTimer = tracked.schedule(deps.room.reconnectMs) {
            onLoop {
                t.reconnectTimer = null
                if (table === t && t.client.phase == ClientPhase.CLOSED && t.client.closeReason !in FINAL_CLOSES) {
                    t.reconnectAttempts++
                    metrics.rejoinAttempts.incrementAndGet()
                    connect(t)
                }
                publish()
            }
        }
    }

    /**
     * While the host is lost, watches discovery for the same room id over the same medium: a host
     * that restarts (process killed, table restored) listens on a new port or a new random Bluetooth
     * address. The next rejoin attempt goes there with the same ticket; the host still checks the
     * room id at hello and the rejoin proof at join, so ownership never follows a name or an address.
     * G2: only an advert that names the host key this member pinned is followed; another host
     * announcing the same room id is ignored (and a welcome with another key is refused anyway).
     */
    private fun followRoom(t: OwnerTable) {
        if (t.finder != null) return
        val browser = deps.network?.browser() ?: return
        t.finder = browser
        browser.start { rooms -> post { onLoop { roomFound(t, rooms) } } }
    }

    private fun roomFound(t: OwnerTable, rooms: List<RoomAdvert>) {
        if (table !== t || t.finder == null) return
        val current = checkNotNull(t.endpoint)
        val pinned = t.client.hostKeyFingerprint ?: return
        val found = rooms.firstOrNull { it.roomId == t.tableId && it.endpoint.medium == current.medium && it.hostKey == pinned } ?: run {
            if (rooms.any { it.roomId == t.tableId && it.hostKey != pinned }) metrics.roomMovesRefused.incrementAndGet()
            return
        }
        if (found.endpoint != current) {
            t.endpoint = found.endpoint
            metrics.roomMoves.incrementAndGet()
        }
    }

    private fun stopFollowing(t: OwnerTable) {
        t.finder?.stop()
        t.finder = null
    }

    /** Announces the room, again whenever the seats a newcomer may take change. */
    private fun announce(t: OwnerTable) {
        val host = t.host ?: return
        val listener = t.listener ?: return
        val info = t.info ?: return
        // Shutting down: a task still running must not announce the room again (see [shutdown]).
        if (t.leaving || closed.get()) return
        val seats = host.room().seats
        val open = seats.filter { it.occupant == Occupant.EMPTY || (it.occupant == Occupant.AWAITING_PLAYER && it.nickname == null) }.map { it.seat }.toSet()
        val away = seats.filter { it.seat !in open && it.occupant in REPLACEABLE }.map { it.seat }.toSet()
        val advert = RoomAdvert(
            t.tableId, info.tableName, info.hostName, PROTOCOL_VERSION, PROTOCOL_VERSION,
            deps.profiles.base.hash, deps.profiles.match.hash, host.hostKeyFingerprint, open, listener.endpoint, away,
        )
        if (advert != t.advert) {
            t.advert = advert
            announcedRoom.set(advert.roomId)
            checkNotNull(deps.network).announcer.announce(advert) {
                // Nearby devices cannot find the room: the host says so, as for a failed listener.
                post {
                    if (table === t && t.listener === listener && !t.hostingFailed) {
                        t.hostingFailed = true
                        publish()
                    }
                }
            }
        }
    }

    /** Stops listening and announcing (a hosted table) and drops the network link (a joined one). */
    private fun stopServing(t: OwnerTable) {
        t.reconnectTimer?.cancel()
        t.reconnectTimer = null
        stopFollowing(t)
        t.listener?.let {
            announcedRoom.set(null)
            deps.network?.announcer?.withdraw(t.tableId)
            it.close()
        }
        t.listener = null
        t.advert = null
        t.transport?.close()
    }

    private fun sessionCommand(t: OwnerTable, body: CommandBody, action: SessionAction) {
        val id = t.client.submit(body) ?: return
        t.sessionCommands[id] = action
        t.notice = null
    }

    private fun resolveSessionCommands(t: OwnerTable) {
        val done = t.sessionCommands.filterKeys { it in t.client.replies }
        for ((id, action) in done) {
            t.sessionCommands.remove(id)
            when (val reply = t.client.replies[id]) {
                is RejectionReceipt -> t.notice = SessionNotice(action, reply.code)
                is Receipt -> if (t.notice?.action == action) t.notice = null
                else -> Unit
            }
        }
        // The connection that carried the rest is gone and the client dropped their retries: no reply
        // will come. The room state the host sends after a rejoin shows whether they took effect.
        if (t.client.phase != ClientPhase.ADMITTED) t.sessionCommands.clear()
    }

    /** Test seam: session requests (ready, follow, consent, revoke, leave) still awaiting a reply. */
    internal val sessionRequestsInFlight: Int get() = table?.sessionCommands?.size ?: 0

    private fun send(p: PendingCommand) {
        // The owner failed after this was accepted: a failed table accepts no more input.
        if (failure.get() != null) {
            pending.compareAndSet(p, null)
            publish()
            return
        }
        val s = table
        // Chosen against an older table (a snapshot arrived, or a new epoch, before it left): never sent.
        if (s != null && p.movedOn(s.client.revision, s.client.epoch)) {
            if (pending.compareAndSet(p, null)) rejection.set(CommandRejection(p.ticket, null, RejectionKey.TABLE_MOVED_ON))
            publish()
            return
        }
        val id = s?.client?.submit(p.body)
        if (s == null || id == null) {
            if (pending.compareAndSet(p, null)) rejection.set(CommandRejection(p.ticket, null, RejectionKey.NOT_CONNECTED))
            publish()
            return
        }
        metrics.commandsSent.incrementAndGet()
        s.pendingId = p.ticket to id
        s.pendingSentAt = tracked.now
        publish()
    }

    /** Settles the pending command from the client's replies (receipt or typed rejection). */
    private fun resolvePending(s: OwnerTable) {
        val p = pending.get() ?: return
        // Not handed to the client yet (the send task is still queued on the loop).
        val id = s.pendingId?.takeIf { it.first == p.ticket }?.second ?: return
        when (val reply = s.client.replies[id]) {
            is Receipt -> if (pending.compareAndSet(p, null)) {
                s.pendingId = null
                metrics.receiptMs.add(maxOf(0L, tracked.now - s.pendingSentAt))
            }
            is RejectionReceipt -> {
                clear(p, RejectionKeys.of(reply.code, reply.reason), reply.code)
                s.pendingId = null
                effects.rejected(reply.epoch, reply.revision, id)?.let(::emit)
            }
            null -> if (s.client.phase != ClientPhase.ADMITTED) {
                // The connection that carried it is gone and the client dropped its retries: it may or
                // may not have been applied. The next snapshot shows the truth.
                clear(p, RejectionKey.OUTCOME_UNKNOWN, null)
                s.pendingId = null
            }
            else -> error("unexpected reply ${reply::class.simpleName} to a command")
        }
    }

    private fun clear(p: PendingCommand, key: RejectionKey, code: RejectionCode?) {
        if (pending.compareAndSet(p, null)) rejection.set(CommandRejection(p.ticket, code, key))
    }

    /**
     * Publishes the state built from the client (this member's projection) and the room status. Its
     * side effects come first: cues and history for a new view, and settling a command whose
     * connection is gone.
     */
    private fun publish() {
        val s = table ?: return
        val c = s.client
        val view = c.memberView
        val epoch = c.epoch
        if (view != null && epoch != null) {
            effects.observe(epoch, c.revision, view).forEach(::emit)
            record(s, view.match.matchNumber, view.match.lastResult)
        }
        val connection = connectionOf(c)
        if (connection == ConnectionState.DISCONNECTED) resolvePending(s)
        val table = snapshot(s, connection)
        stateFlow.update { old ->
            if (old.lifecycle == OwnerLifecycle.SHUT_DOWN) return@update old
            OwnerState(
                lifecycle = if (failure.get() != null) OwnerLifecycle.FAILED else OwnerLifecycle.RUNNING,
                table = table,
                failure = failure.get(),
            )
        }
    }

    private fun connectionOf(c: SessionClient): ConnectionState = when {
        c.phase == ClientPhase.ADMITTED && c.stale -> ConnectionState.STALE
        c.phase == ClientPhase.ADMITTED -> ConnectionState.CONNECTED
        c.phase == ClientPhase.CLOSED || c.phase == ClientPhase.REJECTED -> ConnectionState.DISCONNECTED
        else -> ConnectionState.CONNECTING
    }

    /** This member's table as the UI sees it. Pure: reads the table, its client and host, nothing else. */
    private fun snapshot(s: OwnerTable, connection: ConnectionState): TableSnapshot {
        val c = s.client
        val host = s.host
        return TableSnapshot(
            tableId = s.tableId,
            seat = c.seat,
            epoch = c.epoch,
            revision = c.revision,
            view = c.memberView,
            connection = connection,
            room = if (host != null) condition(host.status) else joinedCondition(s),
            pending = pending.get(),
            lastRejection = rejection.get(),
            hosting = host != null,
            kind = s.kind,
            role = c.config.role,
            spectatorView = c.spectatorView,
            follow = c.follow?.let { FollowedView(it.grantId, it.seat, it.view) },
            grants = c.grants,
            roomView = c.room.takeIf { s.kind != TableKind.SOLO },
            info = s.info,
            hostSide = if (s.kind == TableKind.HOSTED && host != null) hostSide(s, host) else null,
            join = if (s.kind == TableKind.JOINED) joinStatus(s) else null,
            notice = s.notice,
        )
    }

    private fun condition(status: RoomStatus): RoomCondition = when (status) {
        RoomStatus.Active -> RoomCondition.ACTIVE
        is RoomStatus.Paused -> RoomCondition.PAUSED
        is RoomStatus.Suspended -> RoomCondition.SUSPENDED
        is RoomStatus.Ended -> RoomCondition.ENDED
        is RoomStatus.Quarantined -> RoomCondition.QUARANTINED
    }

    /** A joined member knows the room only from what the host sends: its status and its close reason. */
    private fun joinedCondition(t: OwnerTable): RoomCondition {
        val c = t.client
        return when {
            c.closeReason == CloseReason.SUSPENDED -> RoomCondition.SUSPENDED
            c.closeReason == CloseReason.EPOCH_ENDED -> RoomCondition.ENDED
            c.room?.paused == true -> RoomCondition.PAUSED
            c.room != null -> RoomCondition.ACTIVE
            else -> RoomCondition.UNKNOWN
        }
    }

    private fun hostSide(t: OwnerTable, host: SessionHost): HostSide = HostSide(
        pending = host.pendingAdmissions().filter { it.connectionId != t.ownConnection }.map {
            PendingJoiner(it.connectionId, it.nickname, it.role, it.seat, it.replace, it.confirmationCode)
        },
        startBlockers = if (host.inLobby) LobbyGate.blockers(host.room().seats) else emptyList(),
        canStandIn = host.recoveryPolicy.substitute != null,
        saved = host.tableId != null,
        canRematch = host.state.phase == MatchPhase.OVER && host.status == RoomStatus.Ended(EndReason.MATCH_OVER),
        hostingFailed = t.hostingFailed,
    )

    private fun joinStatus(t: OwnerTable): JoinStatus {
        val c = t.client
        val phase = when (c.phase) {
            ClientPhase.IDLE, ClientPhase.HELLO_SENT, ClientPhase.JOINING -> if (t.everAdmitted) JoinPhase.HOST_LOST else JoinPhase.CONNECTING
            ClientPhase.PENDING -> JoinPhase.AWAITING_APPROVAL
            ClientPhase.ADMITTED -> JoinPhase.ADMITTED
            ClientPhase.REJECTED -> when {
                c.helloRejection?.reason == HelloRejectReason.VERSION_UNSUPPORTED || c.helloRejection?.reason == HelloRejectReason.PROFILE_MISMATCH -> JoinPhase.INCOMPATIBLE
                c.helloRejection?.reason == HelloRejectReason.ROOM_FULL || c.denial == AdmissionDenial.OBSERVERS_FULL || c.denial == AdmissionDenial.PENDING_FULL -> JoinPhase.FULL
                c.denial == AdmissionDenial.SEAT_TAKEN || c.denial == AdmissionDenial.SEAT_INVALID -> JoinPhase.SEAT_TAKEN
                c.denial == AdmissionDenial.DENIED_BY_HOST -> JoinPhase.DENIED
                else -> JoinPhase.FAILED
            }
            ClientPhase.CLOSED -> when {
                c.closeReason == CloseReason.SUSPENDED -> JoinPhase.SUSPENDED
                c.closeReason in FINAL_CLOSES -> JoinPhase.ENDED
                t.everAdmitted -> JoinPhase.HOST_LOST
                else -> JoinPhase.FAILED
            }
        }
        return JoinStatus(phase, c.confirmationCode, c.denial, c.helloRejection?.reason, c.closeReason, t.reconnectAttempts)
    }

    /** Republishes the fields any thread may change (pending, rejection) on top of the loop's last state. */
    private fun refreshLocal() {
        stateFlow.update { old ->
            old.copy(table = old.table?.copy(pending = pending.get(), lastRejection = rejection.get()))
        }
    }

    private fun emit(cue: TableCue) {
        metrics.cuesEmitted.incrementAndGet()
        cueFlow.tryEmit(cue)
    }

    /**
     * Records [result], the last settled deal of match [matchNumber] (the view's: a rematch clears the
     * last result, so the two always belong together). A deal is (table, match, deal number): a rematch
     * numbers its deals from 1 again, so only a repeat of the same triple is skipped here and in the history.
     */
    private fun record(s: OwnerTable, matchNumber: Int, result: DealResult?) {
        val history = deps.history ?: return
        if (result == null) return
        val key = RecordedDeal(s.tableId, matchNumber, result.dealNumber)
        if (key == lastRecordedDeal) return
        lastRecordedDeal = key
        history.append(HistoryEntry.of(s.tableId, matchNumber, result, s.client.seat, deps.clock.nowMillis()))
    }

    private data class RecordedDeal(val tableId: String, val matchNumber: Int, val dealNumber: Int)

    private fun fail(t: Throwable) {
        metrics.failures.incrementAndGet()
        failureKind.compareAndSet(FailureKind.NONE, failureKind(t))
        failure.compareAndSet(null, "${t::class.simpleName}: ${t.message}")
        stateFlow.update { it.copy(lifecycle = if (it.lifecycle == OwnerLifecycle.SHUT_DOWN) it.lifecycle else OwnerLifecycle.FAILED, failure = failure.get()) }
    }

    private companion object {
        const val WATCH_MS = 250L

        /** How long [shutdown] waits for the loop to run the requests queued before it, then the teardown. */
        const val TEARDOWN_WAIT_MS = 2_000L

        /** How long a leaving host keeps its connections open so its close reaches every member. */
        const val LINGER_MS = 1_000L

        /**
         * How long [shutdown]'s teardown waits for a leave to be acknowledged or a leaving host's
         * linger to end: above [LINGER_MS], below [TEARDOWN_WAIT_MS].
         */
        const val LAST_WORDS_MS = 1_500L
        const val LAST_WORDS_POLL_MS = 10L

        /** Seats a newcomer may ask to take over: their player is away (GDN-016). */
        val REPLACEABLE = setOf(Occupant.ABSENT, Occupant.STAND_IN, Occupant.AWAITING_PLAYER)

        /** Closes after which a joined member does not rejoin by itself. */
        val FINAL_CLOSES = setOf(CloseReason.LEFT, CloseReason.REPLACED, CloseReason.EPOCH_ENDED, CloseReason.SUSPENDED, CloseReason.DENIED)
    }
}
