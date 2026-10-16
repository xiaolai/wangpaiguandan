package cn.wpgd.app.table

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.owner.TableSession
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.ReturnTributeBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The table screen's state holder. The table itself lives in the [TableSession] (the
 * application-scoped owner); this keeps only the screen's selection, manual groups and chosen
 * wildcard reading, and derives [uiState] from the owner's state with [TableUiMapper].
 *
 * Behaviours (GDN-050, GDN-074–076, GDN-081, GDN-088):
 * - A pending command keeps the selection; a refusal shows a [cn.wpgd.app.owner.RejectionKey] and
 *   keeps the cards selected; an accepted play removes them from the hand (and so from the selection).
 * - A double tap sends exactly one command: the owner refuses a second while one is pending.
 * - Every intent is ignored while controls are disabled (stale connection, paused room, pending).
 * - Preferences change locally and never touch the session.
 *
 * [scope] is this view model's scope (production: main dispatcher); clearing the view model cancels it.
 */
class TableViewModel(
    private val session: TableSession,
    private val preferences: PreferencesRepository,
    scope: CoroutineScope,
) : ViewModel(scope) {
    private val mapper = TableUiMapper(session.rules)
    private val local = MutableStateFlow(LocalTableState())

    val uiState: StateFlow<TableUiState> = combine(session.state, local, preferences.preferences, mapper::map)
        .stateIn(viewModelScope, SharingStarted.Eagerly, current())

    init {
        // A card that leaves the hand leaves the selection for good, so a card that comes back in the
        // same deal (a tribute card returned) is not selected again without the player's touch.
        viewModelScope.launch {
            session.state.collect { s ->
                val hand = s.table?.view?.deal?.hand ?: return@collect
                val ids = hand.mapTo(HashSet()) { it.id }
                local.update { it.copy(selection = it.selection.prunedTo(ids)) }
            }
        }
    }

    /**
     * Effects of new events under the current preferences: sound, haptics, flashes and the
     * announcement text. Only events that happen while collected; nothing is replayed. A sound or
     * haptic of a cue already performed for this table is dropped ([CueGuard]; this view model is
     * bound to one table).
     */
    private val cueGuard = CueGuard()
    val effects: Flow<EffectOutput> = session.cues.transform { cue ->
        EffectRouter.route(cue, preferences.preferences.value).forEach { if (cueGuard.firstTime(it)) emit(it) }
    }

    /**
     * The state as of now, recomputed from the sources. [uiState] may emit a dispatch later than an
     * intent (its combine yields); intents, accessibility actions and tests that must see their own
     * change at once read this instead.
     */
    fun state(): TableUiState = current()

    private fun current(): TableUiState = mapper.map(session.state.value, synced(), preferences.preferences.value)

    /** The local state, reset when a new deal has started (card ids repeat across deals). */
    private fun synced(): LocalTableState {
        val deal = session.state.value.table?.view?.match?.dealNumber ?: return local.value
        local.update { if (it.dealNumber == deal) it else LocalTableState(tableVisible = it.tableVisible, dealNumber = deal) }
        return local.value
    }

    private fun handIds(): Set<String> = session.state.value.table?.view?.deal?.hand?.map { it.id }?.toSet().orEmpty()

    /**
     * Changes the selection; ignored while controls are disabled (the selection is kept as it is).
     * [change] gets the cards that may be selected now: those of the hand that are eligible for the
     * current step ([CardUi.eligible]), so a drag or a tap never picks a card the step refuses.
     */
    private inline fun select(crossinline change: (HandSelection, Set<String>) -> HandSelection) {
        val ui = current()
        if (!ui.controlsEnabled) return
        val hand = handIds()
        val usable = ui.hand.rows.asSequence().flatten().flatMap { it.cards }.filter { it.eligible }.map { it.id }.toSet()
        local.update { it.copy(selection = change(it.selection.prunedTo(hand), usable), chosenOption = null) }
    }

    fun tap(cardId: String) = select { s, h -> s.tap(cardId, h) }
    fun dragStart(cardId: String) = select { s, h -> s.dragStart(cardId, h) }
    fun dragOver(cardId: String) = select { s, h -> s.dragOver(cardId, h) }

    /** Always ends a drag, even with controls disabled, so no drag outlives its gesture. */
    fun dragEnd() {
        synced()
        local.update { it.copy(selection = it.selection.dragEnd()) }
    }

    /** Long press: selects the whole column holding [cardId]. */
    fun selectColumn(cardId: String) {
        val group = current().hand.rows.flatten().firstOrNull { g -> g.cards.any { it.id == cardId } } ?: return
        select { s, h -> s.addAll(group.cards.map { it.id }, h) }
    }

    fun clearSelection() = select { s, _ -> s.clear() }

    /**
     * Picks one reading of a wildcard play (GDN-076); its payload is exactly what [play] sends.
     * Ignored while controls are disabled, so a pending play's preview stays what was submitted.
     */
    fun chooseReading(key: String) {
        val ui = current()
        if (!ui.controlsEnabled) return
        val p = ui.preview as? PlayPreview.Options ?: return
        if (p.options.any { it.key == key }) local.update { it.copy(chosenOption = key) }
    }

    /** Makes the selected cards one manual group; the selection is cleared. Ignored while controls are disabled. */
    fun groupSelection() {
        if (!current().controlsEnabled) return
        val hand = session.state.value.table?.view?.deal?.hand ?: return
        local.update { it.copy(manualGroups = HandArrangement.group(it.manualGroups, it.selection.ids, hand), selection = HandSelection(), chosenOption = null) }
    }

    fun ungroupAll() = local.update { it.copy(manualGroups = emptyList()) }

    fun play(): SubmitResult? {
        val ui = current()
        if (!ui.actions.play) return null
        val payload = (ui.preview as? PlayPreview.Options)?.payload ?: return null
        return session.submit(payload)
    }

    fun pass(): SubmitResult? = if (current().actions.pass) session.submit(PassBody) else null

    fun giveTribute(): SubmitResult? {
        val ui = current()
        val card = ui.hand.selectedIds.singleOrNull()
        return if (ui.actions.giveTribute && card != null) session.submit(GiveTributeBody(card)) else null
    }

    fun returnTribute(): SubmitResult? {
        val ui = current()
        val card = ui.hand.selectedIds.singleOrNull()
        return if (ui.actions.returnTribute && card != null) session.submit(ReturnTributeBody(card)) else null
    }

    fun resist(): SubmitResult? = if (current().actions.resist) session.submit(ResistBody) else null

    fun continueToNextDeal(): Boolean = current().actions.continueToNextDeal && session.continueToNextDeal()

    fun dismissRejection() = session.dismissRejection()

    /** In-table settings (GDN-081): local only; the session, its connection and its revision are untouched. */
    fun updatePreferences(change: (TablePreferences) -> TablePreferences) = preferences.update(change)

    fun setTableVisible(visible: Boolean) = local.update { it.copy(tableVisible = visible) }

    /** Host only, and only when the state offers it (audit A22, H8). */
    fun pause() {
        if (current().canPause) session.pause()
    }

    fun resume() {
        if (current().canResume) session.resume()
    }

    /** Rejoins after the link went stale or dropped (T8); ignored while connected or already rejoining. */
    fun reconnect() {
        if (current().canReconnect) session.reconnect()
    }

    /** The profiles this table plays under (T13); fixed for the table's life. */
    val rulesSummary: RulesSummary = RulesSummary.of(session.rules)
}
