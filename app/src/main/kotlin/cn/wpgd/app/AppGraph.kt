package cn.wpgd.app

import cn.wpgd.app.ble.BleReport
import cn.wpgd.app.ble.BleRoomNetwork
import cn.wpgd.app.data.HistoryRepository
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.LocalStores
import cn.wpgd.app.data.NicknameRepository
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.data.WallClock
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.room.AddressJoin
import cn.wpgd.app.room.LocalVersion
import cn.wpgd.app.room.NetworkAddressLookup
import cn.wpgd.app.room.RoomFinder
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomNetwork
import cn.wpgd.app.room.RoomStore
import cn.wpgd.app.room.SavedTables
import cn.wpgd.app.session.crypto.JcaCrypto
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.storage.TableRepository
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WI-09: what rooms need from the platform. [network] reaches nearby devices (LAN sockets and NSD on
 * Android); [storage] keeps hosted tables (app-private files, excluded from backup). Built lazily, so
 * a process that only plays solo never creates either (GDN-005).
 */
class RoomPlatform(val network: RoomNetwork, val storage: Storage)

/**
 * WI-10: Bluetooth rooms. Exists only in builds with the `bleTransport` flag on, and is built only
 * when the user opens a Bluetooth room after the permission gate ([cn.wpgd.app.ble.BleGate]) said
 * ready, so solo play and Wi-Fi rooms never construct it. [report] receives one diagnostics line at a
 * time (counts and timings only; Android: logcat tag `wpgd-ble`).
 */
class BluetoothPlatform(val network: BleRoomNetwork, val report: (String) -> Unit)

/**
 * The application-scoped object graph (plain Kotlin; `WpgdApplication` builds it once per
 * process). Holds the [SessionOwner], so a table outlives any Activity (GDN-089).
 */
class AppGraph(
    stores: LocalStores,
    profiles: () -> RuleProfiles,
    platform: () -> RoomPlatform,
    private val clock: WallClock = WallClock.SYSTEM,
    /** WI-10: null when the build's `bleTransport` flag is off: no Bluetooth entry, nothing Bluetooth built. */
    bluetooth: (() -> BluetoothPlatform)? = null,
    /** How often the Bluetooth diagnostics line is reported while a Bluetooth room is active. */
    private val bleReportMs: Long = 10_000,
) {
    val preferences = PreferencesRepository(stores.preferences)
    val nickname = NicknameRepository(stores.profile)
    val history = HistoryRepository(stores.history)
    val identity = IdentityRepository(stores.identity)
    val rooms = RoomStore(stores.rooms)

    private val profiles by lazy(profiles)
    private val platform by lazy(platform)
    private val bluetoothFactory = bluetooth
    private val bluetoothPlatform: BluetoothPlatform by lazy { checkNotNull(bluetoothFactory) { "this build has no Bluetooth rooms" }() }

    /** Test seam: the Bluetooth room network (builds it if needed; the build's flag must be on). */
    internal val bluetoothNetworkForTest: BleRoomNetwork get() = bluetoothPlatform.network

    /** Whether this build offers Bluetooth rooms at all (the `bleTransport` flag). */
    val bluetoothEnabled: Boolean get() = bluetoothFactory != null
    private var current: SessionOwner? = null
    private val open = MutableStateFlow<SessionOwner?>(null)

    /** The owner of the open table, or null; the menu shows "back to the table" while it is set. */
    val openTable: StateFlow<SessionOwner?> = open.asStateFlow()

    /** The running owner, or a new one if there is none (or the last one was shut down). */
    @Synchronized
    fun owner(): SessionOwner = current?.takeIf { !it.terminated } ?: newOwner(rooms = false)

    /**
     * A new owner able to host or join rooms over [medium], replacing a finished one. A solo owner
     * never gets the room network or storage, so solo play cannot reach anything permission-gated.
     */
    @Synchronized
    fun roomOwner(medium: RoomMedium = RoomMedium.LAN): SessionOwner {
        current?.takeIf { !it.terminated && it.state.value.lifecycle != OwnerLifecycle.SHUT_DOWN && it.state.value.table != null }?.let { return it }
        current?.shutdown()
        return newOwner(rooms = true, medium).also { if (medium == RoomMedium.BLUETOOTH) startBleReport(it) }
    }

    private fun network(medium: RoomMedium): RoomNetwork = when (medium) {
        RoomMedium.LAN -> platform.network
        RoomMedium.BLUETOOTH -> bluetoothPlatform.network
    }

    /** Replaces the current owner, once a closing one has finished; a Bluetooth report for the replaced one stops here. */
    private fun newOwner(rooms: Boolean, medium: RoomMedium = RoomMedium.LAN): SessionOwner = awaitClosed().let { SessionOwner(
        OwnerDependencies(
            profiles = profiles,
            crypto = JcaCrypto.suite(),
            loopFactory = { onError -> ExecutorSessionLoop(onError = onError) },
            history = history,
            clock = clock,
            network = if (rooms) network(medium) else null,
            storage = if (rooms) platform.storage else null,
            rooms = if (rooms) this.rooms else null,
            identity = identity,
        ),
    ).also {
        stopBleReport()
        current = it
        open.value = it
    } }

    /** The running owner if a table is open, else null; never creates one (the menu asks this). */
    @Synchronized
    fun runningOwner(): SessionOwner? = current?.takeIf { !it.terminated && it.state.value.lifecycle != OwnerLifecycle.SHUT_DOWN }

    /** Ends the current table and every job it runs, and returns once that is done. */
    fun closeTable() {
        closeTableInBackground()
        awaitClosed()
    }

    /**
     * Ends the current table without blocking the caller (the UI thread): the table is detached at
     * once, and the owner's shutdown — which waits up to seconds for a leave or a host's last words —
     * runs on its own thread. The next owner is created only after it finished ([newOwner]), so the
     * old table's last save and room withdrawal never overlap a new table's.
     */
    @Synchronized
    fun closeTableInBackground() {
        stopBleReport()
        val owner = current ?: return
        current = null
        open.value = null
        val previous = closing
        closing = Thread({
            previous?.join()
            owner.shutdown()
        }, "wpgd-close").apply { start() }
    }

    /** The shutdown [closeTableInBackground] started, until it is awaited. */
    private var closing: Thread? = null

    /** Waits for a background close to finish (callers hold the lock or need none). */
    @Synchronized
    private fun awaitClosed() {
        val t = closing ?: return
        var interrupted = false
        while (t.isAlive) {
            try {
                t.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        closing = null
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** A view model for the table screen; [scope] is the view model's (main-dispatcher) scope. */
    fun tableViewModel(scope: CoroutineScope): TableViewModel = TableViewModel(owner(), preferences, scope)

    /** This build's protocol range and rule profiles, to compare with a room's advert (C7). */
    val localVersion: LocalVersion by lazy { LocalVersion.of(this.profiles.base.hash, this.profiles.match.hash) }

    /** The finder's own thread; a failure there is a bug and crashes loudly (uncaught handler). */
    private val finderLoop by lazy {
        ExecutorSessionLoop("wpgd-finder") { e -> Thread.currentThread().let { t -> t.uncaughtExceptionHandler?.uncaughtException(t, e) ?: throw e } }
    }

    /** The room search (C1–C3); started and stopped by the join screen. */
    val finder: RoomFinder by lazy { RoomFinder({ this.platform.network.browser() }, finderLoop) }

    /** WI-10: the Bluetooth room search; built on first use, after the permission gate. */
    private val bleFinder: RoomFinder by lazy { RoomFinder({ bluetoothPlatform.network.browser() }, finderLoop) }

    fun finder(medium: RoomMedium): RoomFinder = when (medium) {
        RoomMedium.LAN -> finder
        RoomMedium.BLUETOOTH -> bleFinder
    }

    /**
     * The medium a saved table is restored on: the one it was hosted on, except that a Bluetooth table
     * comes back over Wi-Fi when this build has Bluetooth switched off (the kill switch keeps saves).
     */
    fun savedMedium(tableId: String): RoomMedium = rooms.medium(tableId).takeIf { it != RoomMedium.BLUETOOTH || bluetoothEnabled } ?: RoomMedium.LAN

    /** The pending diagnostics tick, or null when no Bluetooth room is being reported. */
    private var bleReport: Cancellable? = null

    @Volatile private var reportOwner: SessionOwner? = null

    /** Test seam: whether the Bluetooth diagnostics report is scheduled or holds an owner. */
    internal val bleReportingForTest: Boolean @Synchronized get() = bleReport != null || reportOwner != null

    /**
     * WI-10 device spike: while a Bluetooth room is active, one line every [bleReportMs] with the
     * server's connection counts, each link's MTU, packet and record timings, and this table's
     * command-to-receipt times ([BleReport]; no payload, addresses or identities). Runs for [owner]
     * only: replacing or closing the table stops it ([stopBleReport]).
     */
    @Synchronized
    private fun startBleReport(owner: SessionOwner) {
        stopBleReport()
        reportOwner = owner
        scheduleBleReport(owner)
    }

    /** Callers hold the lock. */
    private fun scheduleBleReport(owner: SessionOwner) {
        bleReport = finderLoop.schedule(bleReportMs) {
            if (reportOwner !== owner) return@schedule
            val net = bluetoothPlatform.network
            if (net.active()) BleReport.lines(net, owner.metrics.receiptMs).forEach(bluetoothPlatform.report)
            synchronized(this) { if (reportOwner === owner) scheduleBleReport(owner) }
        }
    }

    @Synchronized
    private fun stopBleReport() {
        bleReport?.cancel()
        bleReport = null
        reportOwner = null
    }

    /** C10: a join-by-address lookup over the LAN room network, on the finder's thread. */
    fun addressJoin(): AddressJoin = AddressJoin(NetworkAddressLookup(platform.network, finderLoop))

    /** The hosted tables saved on this device (H3, H6, H7). */
    fun savedTables(): SavedTables = SavedTables(TableRepository(platform.storage), rooms, identity, clock, profiles)
}
