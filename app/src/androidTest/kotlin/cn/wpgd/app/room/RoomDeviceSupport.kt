package cn.wpgd.app.room

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.RecordingSession
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.RoomConfig
import cn.wpgd.app.owner.RoomSession
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.TableSession
import cn.wpgd.app.session.crypto.JcaCrypto
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.InetAddress
import java.util.Collections

fun nick(raw: String): Nickname = (Nickname.parse(raw) as NicknameResult.Valid).nickname

/** Waits on the wall clock for [done]; fails with [what] after [ms]. */
fun waitFor(ms: Long = 20_000, what: () -> String = { "" }, done: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!done()) {
        check(System.currentTimeMillis() < end) { "timed out: ${what()}" }
        Thread.sleep(10)
    }
}

/**
 * Several "phones" in one test process: production owners (session thread, AI worker, platform
 * crypto) talking over real TCP sockets on the loopback interface, with an in-process directory in
 * place of multicast discovery (which an emulator cannot test between devices).
 */
class DeviceRoomRig {
    val directory = InProcessDirectory()
    val network = LanRoomNetwork(directory, bindAddress = InetAddress.getLoopbackAddress())
    private val owners = ArrayList<SessionOwner>()

    fun owner(storage: InMemoryStorage? = null): SessionOwner = SessionOwner(
        OwnerDependencies(
            profiles = DeviceProfiles.profiles,
            crypto = JcaCrypto.suite(),
            loopFactory = { onError -> ExecutorSessionLoop("rig-${owners.size}", onError) },
            network = network,
            storage = storage,
            rooms = storage?.let { RoomStore(InMemoryKeyValueStore()) },
            identity = IdentityRepository(InMemoryKeyValueStore()),
            room = RoomConfig(aiDelayMs = 200),
        ),
    ).also { owners += it }

    fun host(name: String = "阿梅", settings: RoomSettings = RoomSettings("周末掼蛋"), storage: InMemoryStorage? = null): SessionOwner = owner(storage).also { o ->
        check(o.hostRoom(nick(name), settings))
        waitFor(what = { "${o.state.value}" }) { o.state.value.table?.roomView != null && advertOf(o) != null }
    }

    fun advertOf(host: SessionOwner): RoomAdvert? = directory.announced.firstOrNull { it.roomId == host.state.value.table?.tableId }

    /** [name] asks to join [host] at [seat] (null: watch); returns once it waits for approval. */
    fun ask(name: String, host: SessionOwner, seat: Seat?): SessionOwner = owner().also { o ->
        check(o.joinRoom(checkNotNull(advertOf(host)), if (seat == null) Role.OBSERVER else Role.PLAYER, seat, nick(name)))
        waitFor(what = { "${o.state.value.table?.join}" }) { o.state.value.table?.join?.phase == JoinPhase.AWAITING_APPROVAL && o.state.value.table?.join?.code != null }
    }

    /** Asks and has the host approve (comparing the code programmatically). */
    fun join(name: String, host: SessionOwner, seat: Seat?): SessionOwner = ask(name, host, seat).also { o ->
        val code = o.state.value.table!!.join!!.code
        waitFor { host.state.value.table?.hostSide?.pending?.any { it.code == code } == true }
        host.approve(host.state.value.table!!.hostSide!!.pending.single { it.code == code }.connectionId)
        waitFor(what = { "${o.state.value.table?.join}" }) { o.state.value.table?.join?.phase == JoinPhase.ADMITTED && o.state.value.table?.roomView != null }
    }

    /** AI in every seat nobody holds, everyone ready, start; waits until deal 1 is dealt on every owner. */
    fun start(host: SessionOwner, players: List<SessionOwner>) {
        for (s in Seat.entries) if (host.state.value.table!!.roomView!!.seats.single { it.seat == s }.nickname == null) host.setAiSeat(s)
        players.forEach { it.setReady(true) }
        waitFor(what = { "${host.state.value.table?.hostSide}" }) { LobbyUi.of(host.state.value, null)?.canStart == true }
        host.startMatch()
        waitFor { (listOf(host) + players).all { (it.state.value.table?.view?.match?.dealNumber ?: 0) == 1 } }
    }

    fun vm(owner: SessionOwner) = TableViewModel(owner, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun shutdown() = owners.forEach { it.shutdown() }
}

/** A [RoomSession] over a [RecordingSession]: the screen's room requests are recorded, nothing is sent. */
class RecordingRoomSession(val base: RecordingSession, var leavePrompt: LeavePrompt? = null) : RoomSession, TableSession by base {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    override val state get() = base.state
    override fun setReady(ready: Boolean) { calls += "ready:$ready" }
    override fun requestFollow(seat: Seat) { calls += "follow:$seat" }
    override fun answerFollow(grantId: String, approve: Boolean) { calls += "answer:$grantId:$approve" }
    override fun stopFollow(grantId: String) { calls += "stop:$grantId" }
    override fun leaveRoom() { calls += "leaveRoom" }
    override fun approve(connectionId: Long) { calls += "approve:$connectionId" }
    override fun deny(connectionId: Long) { calls += "deny:$connectionId" }
    override fun swapSeats(a: Seat, b: Seat) { calls += "swap:$a:$b" }
    override fun setAiSeat(seat: Seat) { calls += "ai:$seat" }
    override fun clearAiSeat(seat: Seat) { calls += "clear:$seat" }
    override fun updateSettings(settings: RoomSettings) { calls += "settings:${settings.mode}" }
    override fun startMatch() { calls += "start" }
    override fun rematch() { calls += "rematch" }
    override fun standInNow(seat: Seat) { calls += "standIn:$seat" }
    override fun prepareLeave(): LeavePrompt? = leavePrompt
    override fun leaveHosted(choice: LeaveChoice) { calls += "leaveHosted:${choice::class.simpleName}" }
}

val OwnerState.joinPhase: JoinPhase? get() = table?.join?.phase

/** The words on [this] node and every node under it (text and content descriptions), unmerged. */
fun SemanticsNode.allText(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + children.flatMap { it.allText() }
