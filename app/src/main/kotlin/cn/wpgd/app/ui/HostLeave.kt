package cn.wpgd.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import cn.wpgd.app.R
import cn.wpgd.app.room.LeaveChoice
import cn.wpgd.app.room.LeavePrompt
import java.text.DateFormat
import java.util.Date

/** H9: the host's leave prompt, taken once when shown and again when its expiry has passed ([HostLeaveOptions]). */
@Composable
internal fun rememberLeavePrompt(prepare: () -> LeavePrompt?): MutableState<LeavePrompt?> = remember { mutableStateOf(prepare()) }

/**
 * H9, shared by the lobby and the table menu: what leaving the hosted table means (kept until the
 * time shown, or ended for everyone), then the suspend and end buttons drawn by [button]. A suspend
 * whose shown expiry has passed is refused by [onChoice] (false): the prompt is taken again from
 * [prepare], so the host reads and confirms the new expiry.
 */
@Composable
internal fun HostLeaveOptions(
    prompt: MutableState<LeavePrompt?>,
    prepare: () -> LeavePrompt?,
    onChoice: (LeaveChoice) -> Boolean,
    textStyle: TextStyle,
    button: @Composable (label: String, tag: String, onClick: () -> Unit) -> Unit,
) {
    val p = prompt.value ?: return
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val hours = (p.retentionMs / 3_600_000L).toInt()
    Text(
        if (p.saved) stringResource(R.string.leave_host_body_saved, hours, format.format(Date(p.expiresAt))) else stringResource(R.string.leave_host_body_unsaved),
        style = textStyle,
        modifier = Modifier.testTag("menu:leave_consequence"),
    )
    if (p.saved) button(stringResource(R.string.leave_suspend), "menu:leave_suspend") { if (!onChoice(p.suspend())) prompt.value = prepare() }
    button(stringResource(R.string.leave_end), "menu:leave_end") { onChoice(p.end()) }
}
