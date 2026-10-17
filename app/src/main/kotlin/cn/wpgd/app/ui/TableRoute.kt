package cn.wpgd.app.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.data.WallClock
import cn.wpgd.app.owner.RoomSession
import cn.wpgd.app.room.LeaveChoice
import cn.wpgd.app.room.LeavePrompt
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.table.EffectOutput
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.HandSort
import cn.wpgd.app.table.TableUiState
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.table.WakeInputs
import cn.wpgd.app.table.WakeLockController
import cn.wpgd.app.table.WakeLockSink

/**
 * The visual part of the cues the screen is showing now: the bomb flash, the static highlight that
 * replaces it in reduced motion, the card slide, and the last announcement (a polite live region).
 * Set only from [EffectOutput]s, which the owner emits once per event id.
 */
@Stable
class TableEffectsState {
    var flash by mutableStateOf<EffectOutput.Flash?>(null)
    var highlight by mutableStateOf<EffectOutput.Highlight?>(null)
    var motion by mutableStateOf<EffectOutput.Motion?>(null)
    var announce by mutableStateOf<EffectOutput.Announce?>(null)

    /** The motion cue a trick area last started (not composition state: it outlives a layout). */
    private var motionStarted: String? = null

    /**
     * Whether [m] may start now: once per cue. A resize mid-animation swaps the layout, which cancels
     * the running animation before [motion] is cleared; the new trick area must not replay it (round 3 #31).
     */
    fun startMotion(m: EffectOutput.Motion): Boolean {
        if (motionStarted == m.cueId) return false
        motionStarted = m.cueId
        return true
    }

    /** [systemReducedMotion]: the system animator scale is zero, which counts as reduced motion too. */
    fun show(output: EffectOutput, systemReducedMotion: Boolean) {
        when (output) {
            is EffectOutput.Flash -> if (systemReducedMotion) highlight = EffectOutput.Highlight(output.cueId, output.seat) else flash = output
            is EffectOutput.Motion -> if (systemReducedMotion) highlight = EffectOutput.Highlight(output.cueId, output.seat) else motion = output
            is EffectOutput.Highlight -> highlight = output
            is EffectOutput.Announce -> announce = output
            is EffectOutput.Sound, is EffectOutput.Haptic -> Unit
        }
    }
}

/**
 * Every intent the table screen can send; each goes to the view model (or, for the room parts, to
 * [room]), and the owner and host check it against the state. [room] is null at a solo table.
 */
class TableActions(
    private val vm: TableViewModel,
    val leave: () -> Unit,
    val room: RoomSession? = null,
    private val clock: WallClock = WallClock.SYSTEM,
    /** H9: the host's leave was sent; the route closes the table once the owner confirms it ([hostLeaveConfirmed]). */
    private val onHostLeaveSent: () -> Unit = {},
) {
    val hand = HandCallbacks(vm::tap, vm::dragStart, vm::dragOver) { vm.dragEnd() }

    fun play() {
        vm.play()
    }

    fun pass() {
        vm.pass()
    }

    fun giveTribute() {
        vm.giveTribute()
    }

    fun returnTribute() {
        vm.returnTribute()
    }

    fun resist() {
        vm.resist()
    }

    fun nextDeal() {
        vm.continueToNextDeal()
    }

    fun chooseReading(key: String) = vm.chooseReading(key)

    fun dismissRejection() = vm.dismissRejection()

    fun clearSelection() = vm.clearSelection()

    fun group() = vm.groupSelection()

    fun ungroup() = vm.ungroupAll()

    fun sort(sort: HandSort) = vm.updatePreferences { it.copy(handSort = sort) }

    fun preferences(change: (TablePreferences) -> TablePreferences) = vm.updatePreferences(change)

    fun pause() = vm.pause()

    fun resume() = vm.resume()

    fun reconnect() = vm.reconnect()

    /** T9: answer a watcher's request to see this hand. */
    fun answerFollow(grantId: String, allow: Boolean) = room?.answerFollow(grantId, allow)

    /** T10: take back a watcher's view of this hand. */
    fun revokeFollow(grantId: String) = room?.stopFollow(grantId)

    /** H1 (host): let the AI stand in for an away player now. */
    fun standIn(seat: Seat) = room?.standInNow(seat)

    /** GDN-017 (host). */
    fun rematch() = room?.rematch()

    /** H9 (host): what leaving means now. */
    fun prepareLeave(): LeavePrompt? = room?.prepareLeave()

    /**
     * H9 (host): asks the owner to suspend or end; the table closes on this device only once the owner
     * confirms it. False, and nothing is sent, when the choice is a suspend whose shown expiry has
     * passed: the caller shows a fresh prompt.
     */
    fun leaveHosted(choice: LeaveChoice): Boolean {
        val r = room ?: return false
        return leaveHostedTable(r, choice, clock.nowMillis(), onHostLeaveSent)
    }

    /** A joined player or watcher leaves on purpose (the host applies the room's leave policy). */
    fun leaveJoined() {
        room?.leaveRoom()
        leave()
    }
}

/**
 * The table screen wired to its view model. Effects are performed through [effects] (sound,
 * haptics) and shown on screen (flash, highlight, announcement); the screen is kept awake through
 * [wake] only while the wake policy says so (GDN-100).
 */
@Composable
fun TableRoute(
    vm: TableViewModel,
    effects: EffectSink,
    wake: WakeLockSink,
    onLeave: () -> Unit,
    room: RoomSession? = null,
    onHostLeaveSent: () -> Unit = {},
) {
    val ui by vm.uiState.collectAsState()
    val controller = remember(wake) { WakeLockController(wake) }
    DisposableEffect(vm, controller) {
        vm.setTableVisible(true)
        onDispose {
            vm.setTableVisible(false)
            controller.release()
        }
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    // The menu (and the settings in it) covers the table: the table is not visible then (GDN-100).
    LaunchedEffect(vm, menuOpen) { vm.setTableVisible(!menuOpen) }
    LaunchedEffect(controller, ui.seat, ui.phase, ui.room, menuOpen) {
        controller.update(wakeInputs(ui, menuOpen))
    }

    val fx = remember { TableEffectsState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only while the screen is started: a table that plays on in the background makes no sound or
    // vibration (the cue flow has no replay, so nothing comes back on return either).
    LaunchedEffect(vm, effects, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.effects.collect { output ->
                // Read per cue, so a change of the system setting while the screen lives applies at once.
                val systemReducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                fx.show(output, systemReducedMotion)
                effects.perform(output)
            }
        }
    }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    val actions = remember(vm, onLeave, room, onHostLeaveSent) { TableActions(vm, onLeave, room, onHostLeaveSent = onHostLeaveSent) }
    TableScreen(
        ui = ui,
        rules = vm.rulesSummary,
        fx = fx,
        actions = actions,
        menuOpen = menuOpen,
        onMenu = { menuOpen = it },
    )
}

/** What the wake policy is told about the table screen: the open menu hides the table. */
internal fun wakeInputs(ui: TableUiState, menuOpen: Boolean): WakeInputs =
    WakeInputs(seated = ui.seat != null, phase = ui.phase, room = ui.room ?: RoomCondition.UNKNOWN, tableVisible = !menuOpen)

/**
 * H9: sends the host's leave [choice] and reports it [sent], unless it is a suspend whose shown
 * expiry has passed by [now]: the owner refuses that one (the table would be kept for less than was
 * shown), so the host must be shown a fresh prompt. Returns whether it was sent. The table closes
 * on this device only when [hostLeaveConfirmed].
 */
internal fun leaveHostedTable(room: RoomSession, choice: LeaveChoice, now: Long, sent: () -> Unit): Boolean {
    if (choice is LeaveChoice.Suspend && choice.prompt.expiresAt <= now) return false
    room.leaveHosted(choice)
    sent()
    return true
}

/**
 * H9: the owner carried out the host's leave: the table is suspended or ended. A storage failure
 * leaves the room paused instead, and the table stays open so the host sees it and may try again.
 */
internal fun hostLeaveConfirmed(room: RoomCondition?): Boolean = room == RoomCondition.SUSPENDED || room == RoomCondition.ENDED
