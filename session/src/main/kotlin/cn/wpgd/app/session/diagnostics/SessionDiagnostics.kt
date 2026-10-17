package cn.wpgd.app.session.diagnostics

import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.framing.Violation
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.diagnostics.DiagnosticField as F

/**
 * Writes a host's and a client's state and counters into a diagnostics [DiagnosticsReport.Builder].
 * Call on the thread that owns the host or client (the session loop). What is written: room state,
 * pause/quarantine/end reasons as tokens, phase, revisions and every counter. What is not: the
 * quarantine detail text, room id, member ids, nicknames, capabilities, keys, seeds and every card.
 */
object SessionDiagnostics {
    fun host(b: DiagnosticsReport.Builder, host: SessionHost) {
        val s = host.status
        b.token(
            F.HOST_STATUS,
            when (s) {
                RoomStatus.Active -> RoomState.ACTIVE
                is RoomStatus.Paused -> RoomState.PAUSED
                is RoomStatus.Suspended -> RoomState.SUSPENDED
                is RoomStatus.Ended -> RoomState.ENDED
                is RoomStatus.Quarantined -> RoomState.QUARANTINED
            },
        )
        for (r in PauseReason.entries) b.flag(F.HOST_PAUSED, r, s is RoomStatus.Paused && r in s.reasons)
        if (s is RoomStatus.Quarantined) b.token(F.HOST_QUARANTINE, s.reason)
        if (s is RoomStatus.Ended) b.token(F.HOST_ENDED, s.reason)
        b.token(F.HOST_PHASE, host.state.phase)
        b.revision(F.HOST_REVISION, host.revision)
        b.count(F.HOST_DEAL_NUMBER, host.state.dealNumber.toLong())
        val m = host.metrics
        b.count(F.HOST_COMMITS, m.commits)
        b.count(F.HOST_REJECTIONS, m.rejections)
        b.count(F.HOST_DUPLICATE_COMMANDS, m.duplicateCommands)
        b.count(F.HOST_OBSERVER_COMMANDS_REJECTED, m.observerCommandsRejected)
        b.count(F.HOST_STALE_CALLBACKS, m.staleCallbacks)
        b.count(F.HOST_STALE_AI_TASKS, m.staleAiTasks)
        b.count(F.HOST_AI_REJECTED, m.aiRejected)
        b.count(F.HOST_AI_CANCELLED, m.aiCancelled)
        b.count(F.HOST_AI_DUPLICATE_DELIVERIES, m.aiDuplicateDeliveries)
        b.count(F.HOST_ADMITTED, m.admitted)
        b.count(F.HOST_REJOINED, m.rejoined)
        b.count(F.HOST_REPLACEMENTS, m.replacements)
        b.count(F.HOST_DENIED, m.denied)
        b.count(F.HOST_HELLO_REJECTED, m.helloRejected)
        b.count(F.HOST_CODE_MISMATCHES, m.codeMismatches)
        b.count(F.HOST_IGNORED, m.ignored)
        b.count(F.HOST_RESYNCS, m.resyncs)
        b.count(F.HOST_RESYNCS_THROTTLED, m.resyncsThrottled)
        b.count(F.HOST_GRANTS_ACTIVATED, m.grantsActivated)
        b.count(F.HOST_GRANTS_REVOKED, m.grantsRevoked)
        b.count(F.HOST_FOLLOW_FRAMES_DROPPED, m.followFramesDropped)
        b.count(F.HOST_HEARTBEAT_TIMEOUTS, m.heartbeatTimeouts)
        b.count(F.HOST_TRANSPORT_CLOSED, m.transportClosed)
        b.count(F.HOST_STORAGE_FAILURES, m.storageFailures)
        b.count(F.HOST_CHECKPOINTS, m.checkpoints)
        b.count(F.HOST_CHECKPOINT_FAILURES, m.checkpointFailures)
        b.count(F.HOST_MEMBERS_AHEAD, m.membersAhead)
        b.count(F.HOST_SEAT_TRANSITIONS, m.seatTransitions)
        for (v in Violation.entries) b.count(F.HOST_VIOLATIONS, v, m.violations[v] ?: 0)
        for (c in CloseReason.entries) b.count(F.HOST_CLOSED, c, m.closed[c] ?: 0)
    }

    fun client(b: DiagnosticsReport.Builder, client: SessionClient) {
        b.token(F.CLIENT_ROLE, client.config.role)
        b.token(F.CLIENT_PHASE, client.phase)
        client.closeReason?.let { b.token(F.CLIENT_CLOSE_REASON, it) }
        b.revision(F.CLIENT_REVISION, client.revision)
        b.count(F.CLIENT_STATUS_REVISION, client.statusRevision)
        b.flag(F.CLIENT_STALE, client.stale)
        b.count(F.CLIENT_PENDING_COMMANDS, client.pendingCommands.toLong())
        val m = client.metrics
        b.count(F.CLIENT_DIGESTS_VERIFIED, m.digestsVerified)
        b.count(F.CLIENT_DIGEST_MISMATCHES, m.digestMismatches)
        b.count(F.CLIENT_UNVERIFIED_RECEIPTS, m.unverifiedReceipts)
        b.count(F.CLIENT_RESYNC_REQUESTS, m.resyncRequests)
        b.count(F.CLIENT_STALE_FOLLOW_DISCARDED, m.staleFollowDiscarded)
        b.count(F.CLIENT_FOLLOW_CLEARED, m.followCleared)
        b.count(F.CLIENT_OLDER_SNAPSHOTS_IGNORED, m.olderSnapshotsIgnored)
        b.count(F.CLIENT_DUPLICATE_SNAPSHOTS, m.duplicateSnapshots)
        b.count(F.CLIENT_OLDER_STATUS_IGNORED, m.olderStatusIgnored)
        b.count(F.CLIENT_BAD_SNAPSHOTS, m.badSnapshots)
        b.count(F.CLIENT_STALE_EPOCH_FRAMES, m.staleEpochFrames)
        b.count(F.CLIENT_PROFILE_HASH_MISMATCHES, m.profileHashMismatches)
        b.count(F.CLIENT_DUPLICATE_REPLIES, m.duplicateReplies)
        b.count(F.CLIENT_REJECTIONS, m.rejections)
        b.count(F.CLIENT_COMMAND_SENDS, m.commandSends)
        b.count(F.CLIENT_STALE_CALLBACKS, m.staleCallbacks)
        b.count(F.CLIENT_VIOLATIONS, m.violations)
        b.count(F.CLIENT_LIVENESS_TIMEOUTS, m.livenessTimeouts)
        b.count(F.CLIENT_HOST_KEY_MISMATCHES, m.hostKeyMismatches)
        b.count(F.CLIENT_IGNORED, m.ignored)
        b.count(F.CLIENT_SEAT_MOVES, m.seatMoves)
    }
}
