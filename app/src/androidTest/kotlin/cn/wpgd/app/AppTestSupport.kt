package cn.wpgd.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import cn.wpgd.app.owner.SessionOwner

typealias AppRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

val appGraph: AppGraph get() = ApplicationProvider.getApplicationContext<WpgdApplication>().graph

/** The running table's owner (fails if there is none). */
val runningOwner: SessionOwner get() = checkNotNull(appGraph.runningOwner()) { "no table is running" }

fun AppRule.exists(tag: String): Boolean = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun AppRule.isEnabled(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let { SemanticsProperties.Disabled !in it.config } == true

/** From the main menu, starts a solo table and waits until the hand is on screen. */
fun AppRule.startSoloFromMenu() {
    waitUntil(10_000) { exists("menu:solo") }
    onNodeWithTag("menu:solo").performClick()
    waitUntil(30_000) { onAllNodes(SemanticsMatcher("a card") { n -> n.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("card:") }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

fun keepScreenOn(activity: ComponentActivity): Boolean =
    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
