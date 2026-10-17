package cn.wpgd.app

import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.MemberSeatInputs
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.table.PlayPreview
import cn.wpgd.app.table.TableViewModel
import kotlin.random.Random

/**
 * A scripted human acting only through the [TableViewModel]: when the screen offers a decision it
 * picks one legal action (seeded) from the member's own projection, selects its cards by tapping,
 * picks the matching wildcard reading if asked, and presses the button. Shared by the JVM tests and
 * the on-device end-to-end driver (tools/e2e/), which runs it for the human seats.
 */
class ScriptedHuman(private val vm: TableViewModel, private val view: () -> MatchMemberView?, private val inputs: MemberSeatInputs, seed: Long = 3) {
    private val random = Random(seed)
    var actions = 0
        private set

    /** Acts if it is this member's turn and nothing is pending. Returns true when it sent a command. */
    fun act(): Boolean {
        val ui = vm.state()
        // Only the member itself can move the table on its own turn, so the screen and the view agree then.
        if (!ui.controlsEnabled || ui.pending || !ui.turn.youAct) return false
        val v = view() ?: return false
        val input = inputs.of(v)?.takeIf { it.hasChoice } ?: return false
        val all = input.choices.all
        val action = all[random.nextInt(all.size)]
        vm.clearSelection()
        val result = when (action) {
            is AiAction.Play -> {
                action.action.cards.forEach { vm.tap(it.id) }
                val preview = vm.state().preview as? PlayPreview.Options
                    ?: error("no options for ${action.canonical()}: preview ${vm.state().preview}, selected ${vm.state().hand.selectedIds}")
                if (preview.needsChoice) {
                    val want = action.action.declaration.orEmpty()
                    vm.chooseReading(preview.options.first { it.type == action.action.combination.type && it.declaration == want }.key)
                }
                vm.play()
            }
            AiAction.Pass -> vm.pass()
            is AiAction.GiveTribute -> { vm.tap(action.cardId); vm.giveTribute() }
            is AiAction.ReturnTribute -> { vm.tap(action.cardId); vm.returnTribute() }
            AiAction.Resist -> vm.resist()
        }
        check(result is SubmitResult.Sent) { "the screen refused a legal action $action: $result (ui ${vm.state().actions})" }
        actions++
        return true
    }
}
