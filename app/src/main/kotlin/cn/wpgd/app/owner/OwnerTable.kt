package cn.wpgd.app.owner

import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomBrowser
import cn.wpgd.app.room.RoomEndpoint
import cn.wpgd.app.room.RoomListener
import cn.wpgd.app.session.ai.AiSeatDriver
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.InMemoryLink
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.Transport

/**
 * The one table an owner runs, confined to the session loop. Solo and hosted tables run their own
 * [host] and reach it over an in-memory [local] link; a joined table reaches a remote host at
 * [endpoint] over the room network.
 */
internal class OwnerTable(
    val tableId: String,
    val kind: TableKind,
    var client: SessionClient,
    var info: RoomInfo?,
    val host: SessionHost?,
    val driver: AiSeatDriver?,
    val local: InMemoryNetwork?,
    /** JOINED: where the room was last found. Changes only to the address discovery reports for this room id. */
    var endpoint: RoomEndpoint?,
) {
    init {
        require((kind == TableKind.JOINED) == (host == null)) { "a joined table has no host here; solo and hosted tables do" }
        require((kind == TableKind.JOINED) == (endpoint != null)) { "only a joined table has a remote endpoint" }
    }

    /** SOLO/HOSTED: the current in-memory link to the own host, and that host's connection id for it. */
    var link: InMemoryLink? = null
    var ownConnection: Long? = null
    var approvedConnection: Long? = null

    /** JOINED: the current network transport. */
    var transport: Transport? = null

    /** JOINED: the capability the current client was created with (a stored rejoin ticket). */
    var usedTicket: Boolean = false

    /** Command id of the pending game command's ticket, once the client accepted it. */
    var pendingId: Pair<Long, String>? = null

    /** When the pending game command was handed to the client (loop time), for [OwnerMetrics.receiptMs]. */
    var pendingSentAt: Long = 0

    /** Session requests in flight (ready, follow, consent, revoke, leave), by command id. */
    val sessionCommands = LinkedHashMap<String, SessionAction>()
    var notice: SessionNotice? = null

    /** HOSTED: listening point and the advert last announced. */
    var listener: RoomListener? = null
    var advert: RoomAdvert? = null

    /** HOSTED: the network reported that nearby devices can no longer find or reach the room. */
    var hostingFailed: Boolean = false

    /**
     * HOSTED: the host left (suspended or ended): never announced again, listener closing.
     * JOINED: the player asked to leave: its rejoin ticket, already forgotten, is never stored again.
     */
    var leaving: Boolean = false

    /** HOSTED: the settings revision the host's own player was last made ready for. */
    var readySentFor: Long = -1

    /** HOSTED: whether the room was in the lobby at the last update (re-arms [readySentFor]). */
    var wasInLobby: Boolean = false

    var everAdmitted: Boolean = false
    var reconnectAttempts: Int = 0
    var reconnectTimer: Cancellable? = null

    /**
     * JOINED, host lost (H2, GDN-085): watches discovery for this room id, because a host that
     * restarts listens on a new port (LAN) or a new random address (Bluetooth). Null otherwise.
     */
    var finder: RoomBrowser? = null
}
