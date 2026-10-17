package cn.wpgd.app.e2e

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.MainActivity
import cn.wpgd.app.ScriptedHuman
import cn.wpgd.app.WpgdApplication
import cn.wpgd.app.appGraph
import cn.wpgd.app.core.ai.MemberSeatInputs
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchPublicView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.room.AddressJoinState
import cn.wpgd.app.room.LobbyUi
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.nick
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The on-device half of the multi-emulator end-to-end run (tools/e2e/run_e2e.py; DEBUG builds only:
 * this source set is androidTestDebug, and the code it reads comes through the debug-only
 * [cn.wpgd.app.AutomationHook]). One instance runs per device for the whole match, started by
 * `adb shell am instrument -e e2e.role …`; without that argument it is skipped, so a normal
 * `connectedDebugAndroidTest` run never blocks on it.
 *
 * It drives the real app: the production [cn.wpgd.app.AppGraph] (LAN sockets, storage, identity),
 * join by address through the production lookup, the visible app (MainActivity on the table or
 * watch screen), and a [ScriptedHuman] playing legal actions through the table's view model.
 *
 * Files, all in this app's private storage (`files/e2e/`, read by the harness with `run-as`):
 * `status.json` (rewritten every half second), `inbox/` (commands the harness drops: `approve-<code>`,
 * `start`, `finish`), `samples.jsonl` (players: own hand per revision; watchers: every card the
 * spectator view and the screen's semantics show), `result.json` at the end. Nothing is logged.
 */
@RunWith(AndroidJUnit4::class)
class E2eAgentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as WpgdApplication
    private val dir by lazy { File(instrumentation.targetContext.filesDir, "e2e").apply { mkdirs() } }
    private val inbox by lazy { File(dir, "inbox").apply { mkdirs() } }

    private val role by lazy { args.getString("e2e.role").orEmpty() }
    private val deadline by lazy { System.currentTimeMillis() + (args.getString("e2e.timeoutMin")?.toLong() ?: 60) * 60_000 }
    private val targetDeals by lazy { args.getString("e2e.deals")?.toInt() ?: 0 }

    private val events = ArrayList<String>()
    private val dealResults = LinkedHashMap<Int, String>()
    private var violations = 0
    private var samples = 0
    private var lastSampleKey = ""
    private var semanticsChecks = 0
    private var done = false
    private var stage = "starting"
    private var rejoin: String = "null"
    private var human: ScriptedHuman? = null

    @Test fun agent() {
        assumeTrue("not an end-to-end run (no e2e.role argument)", role.isNotEmpty())
        File(dir, "result.json").delete()
        File(dir, "status.json").delete()
        // A player restarted mid-match keeps the hand samples it recorded before it was killed.
        if (args.getString("e2e.mode") != "rejoin") File(dir, "samples.jsonl").delete()
        inbox.listFiles()?.forEach { it.delete() }
        when (role) {
            "host" -> host()
            "player" -> joiner(Role.PLAYER, Seat.valueOf(args.getString("e2e.seat")!!))
            "watcher" -> joiner(Role.OBSERVER, null)
            else -> error("unknown e2e.role $role")
        }
    }

    // ---- Roles -------------------------------------------------------------------------------------

    private fun host() {
        val nickname = args.getString("e2e.nick") ?: "Amei"
        val owner = appGraph.roomOwner(RoomMedium.LAN)
        check(owner.hostRoom(nick(nickname), RoomSettings("E2E桌", graceSeconds = 30))) { "hostRoom refused" }
        event("hosted")
        openTableScreen()
        val human = autoplayer(owner)
        var started = false
        var finished = false
        var dealEndedAt = 0L
        while (!finished) {
            tick(owner)
            for (cmd in takeCommands()) when {
                cmd.startsWith("approve-") -> {
                    val code = cmd.removePrefix("approve-")
                    val p = owner.state.value.table?.hostSide?.pending?.firstOrNull { it.code == code }
                    if (p == null) event("approve-unmatched") else {
                        owner.approve(p.connectionId)
                        event("approved ${p.role} ${p.seat}")
                    }
                }
                cmd == "start" -> {
                    for (s in Seat.entries) if (owner.state.value.table?.roomView?.seats?.single { it.seat == s }?.nickname == null) owner.setAiSeat(s)
                    waitFor("lobby can start") { LobbyUi.of(owner.state.value, null)?.canStart == true }
                    owner.startMatch()
                    started = true
                    event("started")
                }
                cmd == "finish" -> finished = true
            }
            if (started && !done) {
                play(human)
                val m = match(owner.state.value)
                if (m?.phase == MatchPhase.AWAITING_DEAL && m.dealNumber > 0) {
                    if (dealEndedAt == 0L) dealEndedAt = System.currentTimeMillis()
                    val reached = targetDeals in 1..m.dealNumber
                    if (reached) finishPlaying(owner)
                    // A pause between deals, as a host would leave for people to read the result.
                    else if (System.currentTimeMillis() - dealEndedAt > BETWEEN_DEALS_MS) {
                        if (owner.continueToNextDeal()) dealEndedAt = 0L
                    }
                }
                if (m?.phase == MatchPhase.OVER) finishPlaying(owner)
            }
            pause()
        }
        owner.leaveHosted(checkNotNull(owner.prepareLeave()).end())
        event("ended")
        writeStatus(owner)
    }

    private fun joiner(r: Role, seat: Seat?) {
        val host = checkNotNull(args.getString("e2e.host")) { "e2e.host missing" }
        val port = checkNotNull(args.getString("e2e.port")) { "e2e.port missing" }
        val nickname = args.getString("e2e.nick") ?: if (r == Role.PLAYER) "Zhou" else "Kan"
        val rejoining = args.getString("e2e.mode") == "rejoin"
        // Join by address through the production lookup (the same path as the 按地址加入 screen).
        val join = appGraph.addressJoin()
        var advert: RoomAdvert? = null
        while (advert == null) {
            join.lookup(host, port)
            waitFor("lookup at $host:$port") { join.state.value !is AddressJoinState.Looking }
            when (val s = join.state.value) {
                is AddressJoinState.Found -> advert = s.advert
                is AddressJoinState.Editing -> {
                    event("lookup failed: ${s.problem ?: s.failure}")
                    check(s.problem == null) { "bad address $host:$port: ${s.problem}" }
                    Thread.sleep(2_000)
                }
                else -> Unit
            }
        }
        val a = checkNotNull(advert)
        event("found ${a.roomId}")
        val ticket = appGraph.identity.rejoinTicket(a.roomId)
        if (rejoining) {
            check(ticket != null) { "rejoin mode but no stored ticket for ${a.roomId}" }
            check(ticket.hostKey == a.hostKey) { "the stored ticket is pinned to another host key" }
        }
        val owner = appGraph.roomOwner(RoomMedium.LAN)
        check(owner.joinRoom(a, r, seat, nick(nickname))) { "joinRoom refused" }
        val joinStarted = System.currentTimeMillis()
        var sawApproval = false
        waitFor("admitted") {
            tick(owner)
            val phase = owner.state.value.table?.join?.phase
            if (phase == JoinPhase.AWAITING_APPROVAL) sawApproval = true
            check(phase !in FAILED_JOINS) { "join failed: ${owner.state.value.table?.join}" }
            phase == JoinPhase.ADMITTED && owner.state.value.table?.roomView != null
        }
        event("admitted seat=${owner.state.value.table?.seat}")
        if (rejoining) {
            rejoin = """{"ticketPinnedKeyMatched":true,"sawApprovalPrompt":$sawApproval,"seat":"${owner.state.value.table?.seat}","msToAdmitted":${System.currentTimeMillis() - joinStarted}}"""
            check(!sawApproval) { "a returning player had to wait for approval: the ticket was not used" }
            check(owner.state.value.table?.seat == seat) { "rejoined at ${owner.state.value.table?.seat}, not $seat" }
        }
        openTableScreen()
        val human = if (r == Role.PLAYER) autoplayer(owner) else null
        var finished = false
        while (!finished) {
            tick(owner)
            val t = owner.state.value.table
            if (r == Role.PLAYER && t?.roomView?.lobby == true && (t.view?.match?.dealNumber ?: 0) == 0) {
                val mine = t.roomView!!.seats.firstOrNull { it.seat == t.roomView!!.yourSeat }
                if (mine != null && !mine.ready) owner.setReady(true)
            }
            if (human != null && !done) play(human)
            if (r == Role.OBSERVER) watcherChecks(owner)
            val m = match(owner.state.value)
            if (m?.phase == MatchPhase.OVER || (targetDeals > 0 && m != null && m.dealNumber >= targetDeals && m.phase == MatchPhase.AWAITING_DEAL)) finishPlaying(owner)
            if (t?.join?.phase == JoinPhase.ENDED) finished = true
            if ("finish" in takeCommands()) finished = true
            pause()
        }
        if (owner.state.value.table?.join?.phase != JoinPhase.ENDED) owner.leaveRoom()
        writeStatus(owner)
    }

    // ---- Parts -------------------------------------------------------------------------------------

    private fun autoplayer(owner: SessionOwner): Pair<ScriptedHuman, TableViewModel> {
        var vm: TableViewModel? = null
        instrumentation.runOnMainSync { vm = TableViewModel(owner, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
        val inputs = MemberSeatInputs(DeviceProfiles.profiles.base, DeviceProfiles.profiles.match)
        val seed = (args.getString("e2e.seed")?.toLong() ?: 7L) + role.hashCode()
        return ScriptedHuman(checkNotNull(vm), { owner.state.value.table?.view }, inputs, seed).also { human = it } to checkNotNull(vm)
    }

    private fun play(h: Pair<ScriptedHuman, TableViewModel>) {
        instrumentation.runOnMainSync { h.first.act() }
    }

    /** The app's own screen shows the open table (players) or the watch screen (watchers). */
    private fun openTableScreen() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("menu:continue").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("menu:continue").performClick()
    }

    /** A watcher holds no hand, follows nobody, and the screen shows only public cards. */
    private fun watcherChecks(owner: SessionOwner) {
        val t = owner.state.value.table ?: return
        if (t.view != null) violation("a watcher holds a member view")
        if (t.follow != null) violation("a watcher follows a hand it never asked for")
        val sv = t.spectatorView ?: return
        val public = sv.deal?.table?.trick.orEmpty().flatMap { p -> p.cards.map { it.id } }.toSet()
        val key = "${sv.match.dealNumber}:${sv.deal?.table?.revision}"
        if (key == lastSampleKey) return
        lastSampleKey = key
        // The screen: every card node on the watch screen must be a card of the public trick.
        val onScreen = screenCardIds()
        if (onScreen != null) {
            semanticsChecks++
            val hidden = onScreen - public
            if (hidden.isNotEmpty()) violation("the watch screen shows cards outside the public trick: $hidden")
        }
        sample("""{"deal":${sv.match.dealNumber},"rev":${sv.deal?.table?.revision ?: -1},"visible":${json(public)},"screen":${json(onScreen ?: emptySet())}}""")
    }

    /**
     * The card ids the visible screen's semantics tree holds, read on the main thread (the tree is
     * the composition's; reading it from this thread while the live screen recomposes is a race).
     * Null when no Compose root is attached this instant.
     */
    private fun screenCardIds(): Set<String>? {
        var out: Set<String>? = null
        instrumentation.runOnMainSync {
            val root = composeRoot(rule.activity.window.decorView) ?: return@runOnMainSync
            val ids = HashSet<String>()
            fun walk(n: SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("card:") }?.let { ids += it.removePrefix("card:") }
                n.children.forEach(::walk)
            }
            walk(root.semanticsOwner.unmergedRootSemanticsNode)
            out = ids
        }
        return out
    }

    private fun composeRoot(v: View): RootForTest? = when (v) {
        is RootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { composeRoot(v.getChildAt(it)) }
        else -> null
    }

    /** Records state each tick: deal results as they settle, and a player's own hand per revision. */
    private fun tick(owner: SessionOwner) {
        check(System.currentTimeMillis() < deadline) { "end-to-end run timed out in stage $stage" }
        val state = owner.state.value
        state.failure?.let { error("the table failed: $it") }
        val m = match(state)
        m?.lastResult?.let { r ->
            dealResults.putIfAbsent(
                r.dealNumber,
                """{"deal":${r.dealNumber},"level":${r.dealLevel},"finishOrder":${json(r.finishOrder.map { it.name })},"winningTeam":"${r.winningTeam}","gain":${r.gain},"nextLevel":${r.nextLevel},"matchWinner":${r.matchWinner?.let { "\"$it\"" } ?: "null"}}""",
            )
        }
        val v = state.table?.view
        val deal = v?.deal
        if (role != "watcher" && deal != null) {
            val key = "${v.match.dealNumber}:${deal.table.revision}"
            if (key != lastSampleKey) {
                lastSampleKey = key
                sample("""{"deal":${v.match.dealNumber},"rev":${deal.table.revision},"seat":"${v.seat}","hand":${json(deal.hand.map { it.id })}}""")
            }
        }
        stage = when {
            done -> "done"
            m == null -> state.table?.join?.phase?.name ?: "lobby"
            else -> "deal ${m.dealNumber} ${m.phase}"
        }
        writeStatusThrottled(owner)
    }

    private fun finishPlaying(owner: SessionOwner) {
        if (done) return
        done = true
        event("finished playing")
        val m = checkNotNull(match(owner.state.value))
        val teams = m.teams.joinToString(",", "[", "]") { """{"team":"${it.team}","level":${it.level},"aFailures":${it.aFailures}}""" }
        val final = """{"tableId":"${owner.state.value.table?.tableId}","phase":"${m.phase}","matchNumber":${m.matchNumber},"dealNumber":${m.dealNumber},"winner":${m.winner?.let { "\"$it\"" } ?: "null"},"teams":$teams}"""
        val result = """{"role":"$role","seat":${owner.state.value.table?.seat?.let { "\"$it\"" } ?: "null"},"final":$final,"deals":${dealResults.values.joinToString(",", "[", "]")},""" +
            """"actions":${human?.actions ?: 0},"violations":$violations,"samples":$samples,"semanticsChecks":$semanticsChecks,"rejoin":$rejoin,"events":${json(events)}}"""
        atomicWrite(File(dir, "result.json"), result)
    }

    private fun match(s: OwnerState): MatchPublicView? = s.table?.view?.match ?: s.table?.spectatorView?.match

    // ---- Files -------------------------------------------------------------------------------------

    private var lastStatus = 0L

    private fun writeStatusThrottled(owner: SessionOwner) {
        if (System.currentTimeMillis() - lastStatus >= STATUS_MS) writeStatus(owner)
    }

    private fun writeStatus(owner: SessionOwner) {
        lastStatus = System.currentTimeMillis()
        // The hook supplies what a tester reads off the screens (codes, port); null would mean a build without it.
        val hook = checkNotNull(app.automation.status(appGraph)) { "this build has no automation hook" }
        val m = match(owner.state.value)
        val mode = args.getString("e2e.mode") ?: "fresh"
        val status = """{"role":"$role","mode":"$mode","stage":"$stage","done":$done,"deal":${m?.dealNumber ?: 0},"phase":${m?.phase?.let { "\"$it\"" } ?: "null"},""" +
            """"connection":${owner.state.value.table?.connection?.let { "\"$it\"" } ?: "null"},"room":${owner.state.value.table?.room?.let { "\"$it\"" } ?: "null"},""" +
            """"violations":$violations,"samples":$samples,"events":${events.size},"at":$lastStatus,"hook":$hook}"""
        atomicWrite(File(dir, "status.json"), status)
    }

    private fun takeCommands(): List<String> = inbox.listFiles().orEmpty().sortedBy { it.name }.map { f -> f.name.also { f.delete() } }

    private fun sample(line: String) {
        if (samples >= MAX_SAMPLES) return
        samples++
        File(dir, "samples.jsonl").appendText(line + "\n")
    }

    private fun event(e: String) {
        events += "${System.currentTimeMillis()} $e"
    }

    private fun violation(what: String) {
        violations++
        event("VIOLATION $what")
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            pause()
        }
    }

    private fun pause() = Thread.sleep(TICK_MS)

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        check(tmp.renameTo(f)) { "could not write ${f.name}" }
    }

    private fun json(items: Iterable<String>): String = items.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    private companion object {
        const val TICK_MS = 100L
        const val STATUS_MS = 500L
        const val BETWEEN_DEALS_MS = 2_000L
        const val MAX_SAMPLES = 20_000
        val FAILED_JOINS = setOf(JoinPhase.DENIED, JoinPhase.SEAT_TAKEN, JoinPhase.FULL, JoinPhase.INCOMPATIBLE, JoinPhase.FAILED, JoinPhase.ENDED)
    }
}
