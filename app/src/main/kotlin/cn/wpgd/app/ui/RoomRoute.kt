package cn.wpgd.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.room.LobbyUi
import cn.wpgd.app.room.RoomScreen
import cn.wpgd.app.room.RulesSheet
import cn.wpgd.app.room.WatchNotice
import cn.wpgd.app.room.WatchUi
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.table.WakeLockSink

/**
 * The open table, whatever it is: solo goes straight to the table; a room shows the joining story,
 * the lobby, the table or the watcher's screen, as [RoomScreen] decides from the owner's state.
 */
@Composable
fun OpenTableRoute(
    owner: SessionOwner,
    vm: () -> TableViewModel,
    effects: EffectSink,
    wake: WakeLockSink,
    onLeave: () -> Unit,
    onFindAgain: () -> Unit,
    /** WI-10 (C9): leave a Bluetooth room that cannot be reached and look for rooms on the same Wi-Fi. */
    onUseWifi: () -> Unit = {},
) {
    val state by owner.state.collectAsState()
    val t = state.table
    // H9: a host's leave closes the table here only once the owner has suspended or ended it.
    var hostLeaving by rememberSaveable { mutableStateOf(false) }
    val markHostLeaving: () -> Unit = remember { { hostLeaving = true } }
    LaunchedEffect(hostLeaving, t?.room) { if (hostLeaving && hostLeaveConfirmed(t?.room)) onLeave() }
    if (t == null || t.kind == TableKind.SOLO) {
        TableRoute(vm(), effects, wake, onLeave)
        return
    }
    var seenSettings by rememberSaveable { mutableLongStateOf(-1L) }
    var dismissedGrants by rememberSaveable { mutableLongStateOf(0L) }
    var refusalDismissed by rememberSaveable { mutableStateOf(false) }
    // Leaving on purpose: a joined member tells the host first; the owner then closes.
    val leaveJoined = {
        owner.leaveRoom()
        onLeave()
    }
    // The joining story (also shown to a watcher until its view arrives).
    val joinStatus: @Composable () -> Unit = { JoinStatusScreen(t.info, t.join, onFindAgain = { onLeave(); onFindAgain() }, onLeave = leaveJoined, onUseWifi = { onLeave(); onUseWifi() }) }
    when (RoomScreen.of(state)) {
        RoomScreen.JOIN_STATUS -> joinStatus()
        RoomScreen.LOBBY -> {
            val lobby = LobbyUi.of(state, seenSettings.takeIf { it >= 0 })
            val room = t.roomView
            if (lobby == null || room == null) {
                TableRoute(vm(), effects, wake, onLeave, owner, markHostLeaving)
            } else {
                LobbyScreen(
                    lobby = lobby,
                    rules = RulesSheet.of(owner.rules, room.settings, room.settingsRevision),
                    session = owner,
                    onRulesSeen = { seenSettings = it },
                    onLeave = if (t.kind == TableKind.HOSTED) onLeave else leaveJoined,
                    onLeaveHosted = { choice -> leaveHostedTable(owner, choice, System.currentTimeMillis(), markHostLeaving) },
                )
            }
        }
        RoomScreen.WATCH -> {
            val w = WatchUi.of(state, dismissedGrants, owner.rules.matchSettings.maxAFailures)
            if (w == null) {
                joinStatus()
            } else {
                val shown = if (refusalDismissed && w.notice is WatchNotice.Refused) w.copy(notice = null) else w
                WatchScreen(
                    w = shown,
                    session = object : cn.wpgd.app.owner.RoomSession by owner {
                        override fun requestFollow(seat: cn.wpgd.app.core.state.Seat) {
                            refusalDismissed = false
                            owner.requestFollow(seat)
                        }
                    },
                    onDismissNotice = {
                        dismissedGrants = maxOf(dismissedGrants, t.grants.maxOfOrNull { it.grantRevision } ?: 0L)
                        refusalDismissed = true
                    },
                    onLeave = leaveJoined,
                )
            }
        }
        RoomScreen.TABLE, RoomScreen.OTHER -> TableRoute(vm(), effects, wake, onLeave, owner, markHostLeaving)
    }
}
