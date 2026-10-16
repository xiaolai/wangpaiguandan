package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.HintAvailability
import cn.wpgd.app.core.ai.HintRequester
import cn.wpgd.app.core.ai.HintResult
import cn.wpgd.app.core.ai.HintService
import cn.wpgd.app.core.ai.MemberSeatInputs
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.wire.RoomPolicyView

/**
 * Hints for the member using this device (GDN-062). Computed here, from the member's own
 * projection, with the same generator and policy as the AI seats ([HintService]); a hint is never
 * sent over the wire, so no other member and no spectator can see it.
 *
 * Room policy (PROVISIONAL, gate G4): hints exist only when the host's room policy says so
 * (friendly rooms by default; competitive rooms disable them). No policy received yet means no hint.
 * A spectator (with or without a private follow view) never gets one. A modified client could still
 * compute advice from its own hand; that cannot be prevented by any protocol and is not claimed.
 */
class MemberHints(base: RuleProfile, match: RuleProfile, private val service: HintService = HintService()) {
    private val inputs = MemberSeatInputs(base, match)

    fun request(client: SessionClient): HintResult {
        val seat = client.seat
        val view = client.memberView
        val requester = if (seat != null && view != null && view.seat == seat) HintRequester.Player(seat) else HintRequester.Spectator
        val availability = availability(client.policy)
        // Legal-play generation is the expensive part: only for a player in a room that offers hints.
        val input = if (requester is HintRequester.Player && availability == HintAvailability.OFFERED) view?.let(inputs::of) else null
        return service.hint(availability, requester, input)
    }

    companion object {
        fun availability(policy: RoomPolicyView?): HintAvailability =
            if (policy?.hints == true) HintAvailability.OFFERED else HintAvailability.DISABLED_BY_ROOM
    }
}
