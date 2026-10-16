package cn.wpgd.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.table.TableRules
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.table.WakeLockSink
import cn.wpgd.app.ui.TableRoute
import cn.wpgd.app.ui.WpgdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The rule profiles the app packages (assets from `rules/profiles/`). */
object DeviceProfiles {
    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open(name).use { it.readBytes().decodeToString() }

    val profiles: RuleProfiles by lazy { RuleProfiles.parse(asset("P1-base-v1.json"), asset("M1-match-v1.json")) }
    val rules: TableRules by lazy { TableRules(profiles) }
}

fun str(@StringRes id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

fun deviceView(hand: List<String>, build: (List<String>) -> MatchMemberView = { TableFixtures.playingView(DeviceProfiles.profiles, hand = it) }) = build(hand)

/** A screen over a [RecordingSession]; the view model runs on the main thread like in the app. */
class ScreenUnderTest(view: MatchMemberView) {
    val session = RecordingSession(view, DeviceProfiles.rules)
    val preferences = PreferencesRepository(InMemoryKeyValueStore())
    val vm = TableViewModel(session, preferences, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}

/** Lays [content] out at [width] and [fontScale], whatever the device: the L1 and L8 conditions. */
@Composable
fun Sized(width: Dp = 320.dp, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
        Box(Modifier.requiredWidth(width).fillMaxHeight()) { content() }
    }
}

/** The table screen as the app shows it, with the given sinks. */
fun ComposeContentTestRule.showTable(
    vm: TableViewModel,
    width: Dp? = null,
    fontScale: Float = 1f,
    effects: EffectSink = EffectSink { },
    wake: WakeLockSink = WakeLockSink { },
) {
    setContent {
        WpgdTheme {
            if (width == null && fontScale == 1f) {
                TableRoute(vm, effects, wake, onLeave = {})
            } else {
                Sized(width ?: 412.dp, fontScale) { TableRoute(vm, effects, wake, onLeave = {}) }
            }
        }
    }
    waitForIdle()
}

/** Every node of the unmerged semantics tree. */
fun ComposeTestRule.allNodes(): List<SemanticsNode> {
    val out = ArrayList<SemanticsNode>()
    fun walk(n: SemanticsNode) {
        out += n
        n.children.forEach(::walk)
    }
    walk(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    return out
}

/** All text a screen reader can reach on [node]: its text, editable text, content and state descriptions. */
fun SemanticsNode.spokenText(): List<String> = buildList {
    config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
    config.getOrNull(SemanticsProperties.ContentDescription)?.let(::addAll)
    config.getOrNull(SemanticsProperties.StateDescription)?.let(::add)
}
