package cn.wpgd.app

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.SoloConfig
import cn.wpgd.app.session.crypto.JcaCrypto
import cn.wpgd.app.table.TableUiState
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A real solo table (production loop, AI worker and crypto) with a short AI delay. */
class DeviceSolo(aiDelayMs: Long = 40) {
    val owner = SessionOwner(
        OwnerDependencies(
            profiles = DeviceProfiles.profiles,
            crypto = JcaCrypto.suite(),
            loopFactory = { onError -> ExecutorSessionLoop(onError = onError) },
            solo = SoloConfig(aiDelayMs = aiDelayMs),
        ),
    )
    val preferences = PreferencesRepository(InMemoryKeyValueStore())
    val vm = TableViewModel(owner, preferences, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun start(): DeviceSolo {
        check(owner.startSolo((Nickname.parse("阿梅") as NicknameResult.Valid).nickname))
        return this
    }
}

/**
 * The simplest legal human for tests: gives the first allowed tribute or return card, passes when
 * passing is allowed, otherwise leads its lowest single. Picks only what the screen state offers.
 */
object SimpleHuman {
    /** The cards to select and the button to press now, or null when the human has nothing to do. */
    fun decide(ui: TableUiState): Pair<List<String>, String>? {
        if (!ui.controlsEnabled || ui.pending || !ui.turn.youAct) return null
        val cards = ui.hand.rows.flatten().flatMap { it.cards }
        return when (ui.phase) {
            MatchPhase.TRIBUTE -> cards.firstOrNull { it.eligible }?.let { listOf(it.id) to "button:tribute" }
            MatchPhase.RETURN -> cards.firstOrNull { it.eligible }?.let { listOf(it.id) to "button:return" }
            MatchPhase.PLAYING -> if (ui.actions.pass) emptyList<String>() to "button:pass" else listOf(cards.first().id) to "button:play"
            else -> null
        }
    }

    /** Acts through the view model's intents (for tests about effects, not about the buttons). */
    fun actThroughViewModel(vm: TableViewModel): Boolean {
        val (ids, button) = decide(vm.state()) ?: return false
        vm.clearSelection()
        ids.forEach(vm::tap)
        when (button) {
            "button:tribute" -> vm.giveTribute()
            "button:return" -> vm.returnTribute()
            "button:pass" -> vm.pass()
            "button:play" -> vm.play()
        }
        return true
    }
}
