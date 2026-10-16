package cn.wpgd.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.wpgd.app.R
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.table.HandSort
import cn.wpgd.app.table.RulesSummary
import cn.wpgd.app.table.TableUiState

private const val SCRIM_ALPHA = 0.5f
private const val SHEET_HEIGHT = 0.9f

/**
 * The in-table menu (T12, A4 in-table, T13): settings that never touch the session (GDN-081), the
 * table's rules, pause and resume for the host, and leaving with a confirmation. An overlay in the
 * same window, so the table keeps running and stays connected underneath.
 */
@Composable
fun TableMenu(ui: TableUiState, rules: RulesSummary, actions: TableActions, onClose: () -> Unit) {
    val c = Wpgd.colors
    val close = stringResource(R.string.close)
    var showRules by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.suitBlack.copy(alpha = SCRIM_ALPHA))
                .testTag("menu:scrim")
                .clickable(onClickLabel = close, onClick = onClose)
                .semantics { contentDescription = close },
        )
        Surface(
            color = c.surface,
            contentColor = c.ink,
            shape = RoundedCornerShape(topStart = Tokens.radius("panel"), topEnd = Tokens.radius("panel")),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(SHEET_HEIGHT).testTag("menu"),
        ) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Tokens.space(5)),
                verticalArrangement = Arrangement.spacedBy(Tokens.space(3)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.table_menu),
                        style = TextStyle(fontSize = Tokens.type("heading"), fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Tokens.touch).testTag("menu:close")) {
                        Text(close, style = TextStyle(fontSize = Tokens.type("label")), color = c.ink)
                    }
                }
                Text(stringResource(R.string.settings_in_table_note), style = TextStyle(fontSize = Tokens.type("label")), color = c.ink2)
                SettingsContent(ui.preferences, actions::preferences)

                RoomSecondary(stringResource(R.string.table_menu_rules), "menu:rules") { showRules = !showRules }
                if (showRules) RulesView(rules)

                if (ui.canPause) RoomSecondary(stringResource(R.string.table_menu_pause), "menu:pause", onClick = actions::pause)
                if (ui.canResume) RoomSecondary(stringResource(R.string.table_menu_resume), "menu:resume", onClick = actions::resume)

                if (ui.kind != TableKind.SOLO) Watchers(ui, actions)

                if (!confirmLeave) {
                    RoomSecondary(stringResource(R.string.table_menu_leave), "menu:leave") { confirmLeave = true }
                } else {
                    LeaveConfirm(ui, actions) { confirmLeave = false }
                }
            }
        }
    }
}

/** T10: who watches this table, and who sees this player's hand now (with a way to take it back). */
@Composable
private fun Watchers(ui: TableUiState, actions: TableActions) {
    val c = Wpgd.colors
    Text(stringResource(R.string.menu_watchers), style = TextStyle(fontSize = Tokens.type("body"), fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
    if (ui.watchers.isEmpty()) Text(stringResource(R.string.lobby_no_watchers), style = TextStyle(fontSize = Tokens.type("label")), color = c.ink2)
    Column(Modifier.testTag("menu:watchers")) {
        for (w in ui.watchers) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag("watcher:${w.name}")) {
                Text(
                    if (w.seesYourHand) stringResource(R.string.watcher_sees, w.name) else w.name,
                    style = TextStyle(fontSize = Tokens.type("body"), fontWeight = if (w.seesYourHand) FontWeight.Bold else FontWeight.Normal),
                    modifier = Modifier.weight(1f),
                )
                val grant = w.grantId
                if (grant != null) {
                    TextButton(onClick = { actions.revokeFollow(grant) }, modifier = Modifier.heightIn(min = Tokens.touch).testTag("watcher:revoke")) {
                        Text(stringResource(R.string.watcher_revoke), style = TextStyle(fontSize = Tokens.type("label")), color = c.danger)
                    }
                }
            }
        }
    }
}

/**
 * Leaving, with its consequences said first: solo ends; a joined player's seat follows the room's
 * policy; the host chooses suspend (kept until the expiry shown, H9) or end (deleted for everyone).
 */
@Composable
private fun LeaveConfirm(ui: TableUiState, actions: TableActions, cancel: () -> Unit) {
    val c = Wpgd.colors
    val body = TextStyle(fontSize = Tokens.type("body"))
    val prompt = if (ui.kind == TableKind.HOSTED) rememberLeavePrompt(actions::prepareLeave) else null
    Surface(color = c.surface2, shape = RoundedCornerShape(Tokens.radius("control")), border = BorderStroke(2.dp, c.danger), modifier = Modifier.testTag("menu:leave_prompt")) {
        Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            Text(stringResource(R.string.table_leave_confirm_title), style = TextStyle(fontSize = Tokens.type("title"), fontWeight = FontWeight.Bold))
            when {
                ui.kind == TableKind.HOSTED && prompt?.value != null ->
                    HostLeaveOptions(prompt, actions::prepareLeave, actions::leaveHosted, body) { label, tag, onClick -> LeaveButton(label, tag, onClick) }
                ui.kind == TableKind.JOINED -> {
                    Text(stringResource(if (ui.seat == null) R.string.leave_watcher_body else R.string.leave_joined_body), style = body)
                    LeaveButton(stringResource(R.string.table_leave_confirm_yes), "menu:leave_confirm", actions::leaveJoined)
                }
                else -> {
                    Text(stringResource(R.string.table_leave_confirm_body), style = body)
                    LeaveButton(stringResource(R.string.table_leave_confirm_yes), "menu:leave_confirm", actions.leave)
                }
            }
            TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag("menu:leave_cancel")) {
                Text(stringResource(R.string.cancel), style = body, color = c.ink)
            }
        }
    }
}

@Composable
private fun LeaveButton(label: String, tag: String, onClick: () -> Unit) {
    val c = Wpgd.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.bg),
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.touch).testTag(tag),
    ) { Text(label, style = TextStyle(fontSize = Tokens.type("body"))) }
}

/** The profiles this table plays under (T13), marked provisional while unsigned. */
@Composable
private fun RulesView(r: RulesSummary) {
    val c = Wpgd.colors
    val small = TextStyle(fontSize = Tokens.type("label"))
    Surface(color = c.surface2, shape = RoundedCornerShape(Tokens.radius("control")), modifier = Modifier.fillMaxWidth().testTag("rules")) {
        Column(Modifier.padding(Tokens.space(4)), verticalArrangement = Arrangement.spacedBy(Tokens.space(2))) {
            Text(stringResource(R.string.rules_base, r.baseId, r.baseVersion), style = small)
            Text(stringResource(R.string.rules_match, r.matchId, r.matchVersion), style = small)
            Text(stringResource(R.string.rules_hash, r.baseHash, r.matchHash), style = small, color = c.ink2)
            Text(r.maxAFailures?.let { stringResource(R.string.rules_a_failures, it) } ?: stringResource(R.string.rules_a_failures_none), style = small)
            Text(stringResource(if (r.resistanceClaimed) R.string.rules_resistance_claimed else R.string.rules_resistance_automatic), style = small)
            if (r.provisional) Text(stringResource(R.string.rules_provisional), style = small.copy(fontWeight = FontWeight.Bold), color = c.danger)
        }
    }
}

/** Settings shared by the menu screen and the in-table menu (A4). Local preferences only. */
@Composable
fun SettingsContent(prefs: TablePreferences, onChange: ((TablePreferences) -> TablePreferences) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
        SwitchRow(stringResource(R.string.settings_sound), null, prefs.sound, "pref:sound") { v -> onChange { it.copy(sound = v) } }
        SwitchRow(stringResource(R.string.settings_vibration), stringResource(R.string.settings_vibration_hint), prefs.vibration, "pref:vibration") { v -> onChange { it.copy(vibration = v) } }
        SwitchRow(stringResource(R.string.settings_reduced_motion), stringResource(R.string.settings_reduced_motion_hint), prefs.reducedMotion, "pref:reduced_motion") { v -> onChange { it.copy(reducedMotion = v) } }
        SwitchRow(stringResource(R.string.settings_large_cards), null, prefs.largeCards, "pref:large_cards") { v -> onChange { it.copy(largeCards = v) } }
        SwitchRow(stringResource(R.string.settings_four_color), stringResource(R.string.settings_four_color_hint), prefs.fourColorSuits, "pref:four_color") { v -> onChange { it.copy(fourColorSuits = v) } }
        Text(stringResource(R.string.settings_sort), style = TextStyle(fontSize = Tokens.type("body"), fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = Tokens.space(3)))
        val text = rememberTableText(null)
        for (sort in HandSort.entries) ChoiceRow(text.sort(sort), prefs.handSort == sort, "sort:${sort.name}") { onChange { it.copy(handSort = sort) } }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val c = Wpgd.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touch)
            .testTag(tag)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f).padding(vertical = Tokens.space(2))) {
            Text(label, style = TextStyle(fontSize = Tokens.type("body")), color = c.ink)
            if (hint != null) Text(hint, style = TextStyle(fontSize = Tokens.type("caption")), color = c.ink2)
        }
        Switch(checked = value, onCheckedChange = null)
    }
}
