package cn.wpgd.app.room

import cn.wpgd.app.lan.ConnectFailure
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.lan.ConnectReport
import cn.wpgd.app.owner.SessionLoop
import cn.wpgd.app.session.client.LookupResult
import cn.wpgd.app.session.client.RoomLookup
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.wire.RoomListing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why no room was found at a typed address; each has an actionable message (strings `address_*`). */
enum class LookupFailure(val messageKey: String) {
    /** No route, no answer to the connection, or the name does not resolve: wrong address, or the network isolates phones. */
    UNREACHABLE("address.unreachable"),

    /** The phone at that address refused the connection: nothing listens on that port. */
    WRONG_PORT("address.wrong_port"),

    /** Connected, then dropped without an answer: the host is full of connections or just closed its table. */
    CLOSED("address.closed"),

    /** This app answered but hosts no room right now. */
    NO_ROOM("address.no_room"),

    /** Something else answered (another app's port). */
    NOT_A_ROOM("address.not_a_room"),

    /** Connected, but nothing answered in time. */
    TIMEOUT("address.timeout"),
}

sealed interface AddressLookupOutcome {
    /** The room at the address, as an advert reached at exactly that address. */
    data class Found(val advert: RoomAdvert) : AddressLookupOutcome

    data class Failed(val reason: LookupFailure) : AddressLookupOutcome
}

/** Looks up the room at one address; [onDone] is called at most once, on any thread. */
fun interface AddressLookupService {
    fun lookup(endpoint: LanEndpoint, onDone: (AddressLookupOutcome) -> Unit): Cancellable
}

/**
 * Join by address over the room network: one connection, one `room_query`, one answer
 * ([RoomLookup]). Runs on [loop] (the session's single-thread rule). The answer becomes a
 * [RoomAdvert] whose endpoint is the address the user typed, never one the answer names, so the
 * joiner connects where the user pointed; the advert then goes through the normal join.
 */
class NetworkAddressLookup(
    private val network: RoomNetwork,
    private val loop: SessionLoop,
    private val timeoutMs: Long = RoomLookup.DEFAULT_TIMEOUT_MS,
) : AddressLookupService {
    override fun lookup(endpoint: LanEndpoint, onDone: (AddressLookupOutcome) -> Unit): Cancellable {
        val call = Call(endpoint, onDone)
        loop.post(call::start)
        return Cancellable { loop.post(call::cancel) }
    }

    /** One lookup; every method runs on [loop]. */
    private inner class Call(private val endpoint: LanEndpoint, private val onDone: (AddressLookupOutcome) -> Unit) {
        private var cancelled = false
        private var lookup: RoomLookup? = null

        fun start() {
            if (cancelled) return
            val transport = network.connect(endpoint, loop::post)
            lookup = RoomLookup(transport, loop, timeoutMs = timeoutMs) { r -> if (!cancelled) onDone(outcome(r, transport)) }.also { it.start() }
        }

        fun cancel() {
            cancelled = true
            lookup?.cancel()
        }

        private fun outcome(r: LookupResult, transport: Transport): AddressLookupOutcome = when (r) {
            is LookupResult.Found -> advertOf(r.listing)?.let { AddressLookupOutcome.Found(it) } ?: AddressLookupOutcome.Failed(LookupFailure.NOT_A_ROOM)
            LookupResult.NotListed -> AddressLookupOutcome.Failed(LookupFailure.NO_ROOM)
            // CLOSED's message already names "too many connections"; the host answered that it is full.
            LookupResult.Full -> AddressLookupOutcome.Failed(LookupFailure.CLOSED)
            LookupResult.NotARoom -> AddressLookupOutcome.Failed(LookupFailure.NOT_A_ROOM)
            LookupResult.TimedOut -> AddressLookupOutcome.Failed(LookupFailure.TIMEOUT)
            LookupResult.Closed -> AddressLookupOutcome.Failed(closedBecause(transport as? ConnectReport))
        }

        private fun closedBecause(report: ConnectReport?): LookupFailure = when {
            report == null -> LookupFailure.CLOSED
            report.malformedRecords > 0 -> LookupFailure.NOT_A_ROOM
            else -> when (report.connectFailure) {
                ConnectFailure.REFUSED -> LookupFailure.WRONG_PORT
                ConnectFailure.TIMED_OUT, ConnectFailure.UNREACHABLE -> LookupFailure.UNREACHABLE
                null -> LookupFailure.CLOSED
            }
        }

        private fun advertOf(l: RoomListing): RoomAdvert? = try {
            RoomAdvert(
                l.roomId, l.tableName, l.hostName, l.protocolMin, l.protocolMax, l.baseProfileHash, l.matchProfileHash, l.hostKey,
                l.openSeats.toSet(), endpoint, l.replaceableSeats.toSet(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** The join-by-address screen's state. */
sealed interface AddressJoinState {
    /** Typing; [problem] when the address is malformed, [failure] when the last lookup found no room. */
    data class Editing(val problem: AddressProblem? = null, val failure: LookupFailure? = null) : AddressJoinState

    data class Looking(val endpoint: LanEndpoint) : AddressJoinState

    /** A room answered: the play-or-watch choice follows, through the normal join. */
    data class Found(val advert: RoomAdvert) : AddressJoinState
}

/**
 * Join by address (C10): validates the typed address, looks it up once at a time, and shows the room
 * or why not. A result for a lookup that was cancelled or replaced is ignored. Thread-safe.
 */
class AddressJoin(private val service: AddressLookupService) {
    private val flow = MutableStateFlow<AddressJoinState>(AddressJoinState.Editing())
    val state: StateFlow<AddressJoinState> = flow.asStateFlow()

    private var generation = 0L
    private var running: Cancellable? = null

    @Synchronized
    fun lookup(host: String, port: String) {
        if (flow.value is AddressJoinState.Looking) return
        when (val input = ManualAddress.parse(host, port)) {
            is AddressInput.Invalid -> flow.value = AddressJoinState.Editing(problem = input.problem)
            is AddressInput.Valid -> {
                val gen = ++generation
                flow.value = AddressJoinState.Looking(input.endpoint)
                val call = service.lookup(input.endpoint) { deliver(gen, it) }
                running = if (gen == generation && flow.value is AddressJoinState.Looking) call else null
            }
        }
    }

    /** Leaves the found room (or a running lookup) and returns to typing. */
    fun back() = cancel()

    @Synchronized
    fun cancel() {
        generation++
        running?.cancel()
        running = null
        flow.value = AddressJoinState.Editing()
    }

    @Synchronized
    private fun deliver(gen: Long, outcome: AddressLookupOutcome) {
        if (gen != generation) return
        running = null
        flow.value = when (outcome) {
            is AddressLookupOutcome.Found -> AddressJoinState.Found(outcome.advert)
            is AddressLookupOutcome.Failed -> AddressJoinState.Editing(failure = outcome.reason)
        }
    }
}
