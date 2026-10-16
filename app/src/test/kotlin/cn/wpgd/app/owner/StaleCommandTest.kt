package cn.wpgd.app.owner

import cn.wpgd.app.session.wire.PassBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command is chosen against the table the screen showed. When a newer snapshot or a new epoch
 * reaches the client between submit and the send on the loop, it is not sent against the newer table.
 */
class StaleCommandTest {
    private val command = PendingCommand(1, PassBody, atRevision = 7, atEpoch = 3)

    @Test fun theSameTableIsNotMovedOn() {
        assertFalse(command.movedOn(revision = 7, epoch = 3))
    }

    @Test fun aNewerSnapshotMovesTheTableOn() {
        assertTrue(command.movedOn(revision = 8, epoch = 3))
    }

    @Test fun aNewEpochMovesTheTableOn() {
        assertTrue(command.movedOn(revision = 7, epoch = 4))
        assertTrue(command.movedOn(revision = 7, epoch = null))
    }

    @Test fun aCommandWithoutAnEpochChecksTheRevisionOnly() {
        val local = command.copy(atEpoch = null)
        assertFalse(local.movedOn(revision = 7, epoch = 9))
        assertTrue(local.movedOn(revision = 6, epoch = 9))
    }
}
