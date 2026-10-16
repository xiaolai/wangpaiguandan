package cn.wpgd.app.table

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.deviceView
import cn.wpgd.app.showTable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-074: a finger dragged across a six-card run selects exactly those six cards, never a
 * neighbour, and dragging back over them does not toggle them twice.
 */
@RunWith(AndroidJUnit4::class)
class DragSelectionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var screen: ScreenUnderTest

    @Before fun show() {
        screen = ScreenUnderTest(deviceView(TableFixtures.RUN_HAND))
        rule.showTable(screen.vm)
    }

    /** Centre of [id] in the hand node's coordinates. */
    private fun centre(id: String): Offset {
        val hand = rule.onNodeWithTag("hand", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("card:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return card.center - hand.topLeft
    }

    private fun drag(path: List<Offset>, steps: Int = 12) {
        rule.onNodeWithTag("hand", useUnmergedTree = true).performTouchInput {
            down(path.first())
            for ((a, b) in path.zipWithNext()) {
                for (i in 1..steps) moveTo(a + (b - a) * (i / steps.toFloat()))
            }
            up()
        }
        rule.waitForIdle()
    }

    @Test fun dragAcrossASixCardRunSelectsExactlyThoseSix() {
        val centres = TableFixtures.RUN.map(::centre)
        // Layout precondition: the run lies on one line, each club at the bottom of its column.
        val y = centres.first().y
        centres.forEach { assertEquals("the run is not on one row", y, it.y, 1f) }

        drag(listOf(centres.first(), centres.last()))
        assertEquals(TableFixtures.RUN.toSet(), screen.vm.state().hand.selectedIds.toSet())
    }

    @Test fun draggingBackAndForthNeverTogglesACardTwice() {
        val first = centre(TableFixtures.RUN.first())
        val last = centre(TableFixtures.RUN.last())
        drag(listOf(first, last, first, last))
        assertEquals(TableFixtures.RUN.toSet(), screen.vm.state().hand.selectedIds.toSet())

        // A drag that starts on a selected card removes: back across the first three.
        drag(listOf(first, centre(TableFixtures.RUN[2])))
        assertEquals(TableFixtures.RUN.drop(3).toSet(), screen.vm.state().hand.selectedIds.toSet())
    }

    @Test fun aTapAfterADragStillTogglesOneCard() {
        drag(listOf(centre(TableFixtures.RUN.first()), centre(TableFixtures.RUN.last())))
        rule.onNodeWithTag("card:0S9", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals((TableFixtures.RUN + "0S9").toSet(), screen.vm.state().hand.selectedIds.toSet())
    }
}
