package cn.wpgd.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import cn.wpgd.app.room.HostingFailure
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Bluetooth LE radio on Android 12+ (WI-10): a GATT server for a hosted room, GATT client ports
 * for joining, scanning and advert reads. No Google Play services; platform `android.bluetooth` only.
 * Everything runs on one handler thread ("wpgd-ble"), so the GATT state below is single-threaded;
 * [BlePort.send] may be called from any thread.
 *
 * Not verified on hardware (no device was available): roles, MTU, simultaneous connections and
 * throughput are measured by the G5 spike, `dev-docs/ble-device-spike.md`.
 *
 * Permissions: lint's MissingPermission check is suppressed for this class because the check cannot
 * see the mechanism that holds: a Bluetooth room is created only after [BleGate] reports READY (all
 * of [BlePermissions.RUNTIME] granted, checked at that moment), [requireGranted] re-checks at every
 * entry point, and Android kills the process if a runtime permission is revoked while it runs.
 * Every platform call that can still throw a SecurityException goes through [guard], which turns it
 * into a closed port or a failed read rather than a crash.
 */
@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission")
class AndroidBleRadio(private val context: Context) : BleRadio {
    private val thread = HandlerThread("wpgd-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager: BluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: error("Bluetooth is unavailable")
    private val adapter: BluetoothAdapter? get() = manager.adapter

    /**
     * Devices from recent scan results, so a connect uses the address type the scan saw. Bounded:
     * rotating random addresses would otherwise accumulate for the life of the process.
     */
    private val devices = RecentEntries<String, BluetoothDevice>(MAX_DEVICES)

    override val stats = BleRadioStats()

    private fun requireGranted() {
        val missing = BlePermissions.RUNTIME.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) throw SecurityException("Bluetooth permissions missing: $missing")
    }

    /** Runs [block]; a SecurityException (permission gone) or IllegalStateException (radio off) becomes [onFail]. */
    private inline fun <T> guard(onFail: () -> T, block: () -> T): T = try {
        block()
    } catch (_: SecurityException) {
        onFail()
    } catch (_: IllegalStateException) {
        onFail()
    }

    private fun device(address: String): BluetoothDevice? = devices.get(address) ?: guard({ null }) { adapter?.getRemoteDevice(address) }

    override fun openServer(onPeer: (BlePort) -> Unit): BleServer {
        requireGranted()
        return Server(onPeer).also { s -> handler.post { s.start() } }
    }

    override fun connect(address: String): BlePort {
        requireGranted()
        return ClientPort(address)
    }

    override fun scanner(): BleScanner {
        requireGranted()
        return Scanner()
    }

    // ---- Host: GATT server and advertising --------------------------------------------------------

    private inner class Server(private val onPeer: (BlePort) -> Unit) : BleServer {
        override val stats = BleServerStats()
        private var gatt: BluetoothGattServer? = null
        private var toPeer: BluetoothGattCharacteristic? = null
        private var serviceReady = false
        private var closed = false
        private var advertising = false

        @Volatile private var advert: ByteArray? = null
        private val peers = HashMap<String, ServerPort>()
        private val failure = HostingFailure()
        private val notifyQueue = ArrayDeque<ServerPort>()
        private var notifying: ServerPort? = null
        private var notifySeq = 0L

        private val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                handler.post {
                    serviceReady = status == BluetoothGatt.GATT_SUCCESS
                    if (!serviceReady) failed() else if (advert != null) startAdvertising()
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                handler.post { connectionChanged(device, newState) }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                handler.post { peers[device.address]?.mtuChanged(mtu) }
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                handler.post { readRequest(device, requestId, offset, characteristic) }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val copy = value?.copyOf()
                handler.post { writeRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, copy) }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val copy = value?.copyOf()
                handler.post { descriptorWrite(device, requestId, descriptor, preparedWrite, responseNeeded, offset, copy) }
            }

            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
                handler.post {
                    val on = peers[device.address]?.subscribed == true
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                handler.post { respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, null) }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                handler.post { notified(device.address, status == BluetoothGatt.GATT_SUCCESS) }
            }
        }

        private val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                stats.advertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                handler.post {
                    advertising = false
                    stats.advertising = false
                    failed()
                }
            }
        }

        fun start() {
            val server = guard({ null }) { manager.openGattServer(context, callback) }
            if (server == null) {
                closed = true
                failed()
                return
            }
            gatt = server
            val service = BluetoothGattService(UUID.fromString(BleGattSpec.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(BleGattSpec.ADVERT), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            service.addCharacteristic(
                BluetoothGattCharacteristic(UUID.fromString(BleGattSpec.TO_HOST), BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
            )
            val notify = BluetoothGattCharacteristic(UUID.fromString(BleGattSpec.TO_PEER), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            notify.addDescriptor(BluetoothGattDescriptor(UUID.fromString(BleGattSpec.CCCD), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            service.addCharacteristic(notify)
            toPeer = notify
            if (!guard({ false }) { server.addService(service) }) failed()
        }

        override fun onFailure(failed: () -> Unit) = failure.register(failed)

        /** Counted for diagnostics and reported to the room: nearby devices cannot find or reach it. */
        private fun failed() {
            stats.advertiseFailures++
            failure.fail()
        }

        override fun setAdvert(advert: ByteArray?) {
            this.advert = advert?.copyOf()
            handler.post { if (this.advert != null) startAdvertising() else stopAdvertising() }
        }

        private fun startAdvertising() {
            if (advertising || closed || !serviceReady) return
            val advertiser = guard({ null }) { adapter?.bluetoothLeAdvertiser }
            if (advertiser == null) {
                failed()
                return
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            // Only the service UUID: an iPhone host can advertise no more, and the advert itself is
            // read from the GATT characteristic. No device name (it may hold the owner's name).
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(UUID.fromString(BleGattSpec.SERVICE)))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
            advertising = guard({ false }) {
                advertiser.startAdvertising(settings, data, advertiseCallback)
                true
            }
            if (!advertising) failed()
        }

        private fun stopAdvertising() {
            if (!advertising) return
            advertising = false
            stats.advertising = false
            guard({ }) { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        }

        private fun connectionChanged(device: BluetoothDevice, newState: Int) {
            val server = gatt ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (closed || peers.size >= MAX_CONNECTIONS) {
                    stats.refused++
                    guard({ }) { server.cancelConnection(device) }
                    return
                }
                peers[device.address] = ServerPort(this, device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers.remove(device.address)?.disconnected()
                notifyQueue.removeAll { it.device.address == device.address }
                if (notifying?.device?.address == device.address) notifying = null
                pumpNotify()
            }
            stats.onConnected(peers.size)
            stats.subscribed = peers.values.count { it.subscribed }
        }

        private fun readRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != UUID.fromString(BleGattSpec.ADVERT)) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, null)
                return
            }
            val value = advert ?: ByteArray(0)
            if (offset > value.size) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, null)
                return
            }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value.copyOfRange(offset, value.size), offset)
        }

        private fun writeRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val port = peers[device.address]
            val ok = characteristic.uuid == UUID.fromString(BleGattSpec.TO_HOST) && !prepared && offset == 0 && value != null && port != null && port.subscribed
            if (responseNeeded) respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, null)
            if (ok) port.received(value)
        }

        private fun descriptorWrite(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val port = peers[device.address]
            val isCccd = descriptor.uuid == UUID.fromString(BleGattSpec.CCCD) && descriptor.characteristic?.uuid == UUID.fromString(BleGattSpec.TO_PEER)
            val ok = isCccd && !prepared && offset == 0 && port != null && value != null && value.size == 2
            if (responseNeeded) respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, null)
            if (!ok) return
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && !port.subscribed) {
                port.subscribed = true
                stats.subscribed = peers.values.count { it.subscribed }
                onPeer(port)
            } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) && port.subscribed) {
                port.close()
            }
        }

        private fun respond(device: BluetoothDevice, requestId: Int, status: Int, value: ByteArray?, offset: Int = 0) {
            guard({ }) { gatt?.sendResponse(device, requestId, status, offset, value) }
        }

        /** One notification in flight across all centrals: the shared characteristic is written per send on Android 12. */
        fun enqueueNotify(port: ServerPort) {
            if (closed || port.closed) return
            notifyQueue.addLast(port)
            pumpNotify()
        }

        private fun pumpNotify() {
            if (notifying != null) return
            val server = gatt ?: return
            val ch = toPeer ?: return
            while (true) {
                val port = notifyQueue.pollFirst() ?: return
                val packet = port.takePending() ?: continue
                notifying = port
                val seq = ++notifySeq
                val status = guard({ BluetoothGatt.GATT_FAILURE }) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(port.device, ch, false, packet)
                    } else {
                        @Suppress("DEPRECATION")
                        ch.value = packet
                        @Suppress("DEPRECATION")
                        if (server.notifyCharacteristicChanged(port.device, ch, false)) BluetoothStatusCodes.SUCCESS else BluetoothGatt.GATT_FAILURE
                    }
                }
                if (status != BluetoothStatusCodes.SUCCESS) {
                    notifying = null
                    port.sent(false)
                    continue
                }
                // A notification the stack never confirms would stall every central: give up on it.
                // The stack may still confirm it late, and [notified] matches by address only: on a
                // live port that late confirmation would acknowledge the next packet. Drop the
                // connection instead, so nothing more is sent on it.
                handler.postDelayed({
                    if (notifying === port && notifySeq == seq) {
                        this@AndroidBleRadio.stats.notifyTimeouts++
                        notifying = null
                        notifyQueue.removeAll { it === port }
                        cancel(port)
                        port.disconnected()
                        pumpNotify()
                    }
                }, NOTIFY_TIMEOUT_MS)
                return
            }
        }

        private fun notified(address: String, ok: Boolean) {
            val port = notifying ?: return
            if (port.device.address != address) return
            notifying = null
            port.sent(ok)
            pumpNotify()
        }

        fun cancel(port: ServerPort) {
            guard({ }) { gatt?.cancelConnection(port.device) }
        }

        override fun close() {
            handler.post {
                if (closed && gatt == null) return@post
                closed = true
                stopAdvertising()
                peers.values.toList().forEach {
                    cancel(it)
                    it.disconnected()
                }
                peers.clear()
                notifyQueue.clear()
                notifying = null
                guard({ }) {
                    gatt?.clearServices()
                    gatt?.close()
                }
                gatt = null
                stats.onConnected(0)
                stats.subscribed = 0
            }
        }
    }

    /** The host's end of one central's connection (ready once the central subscribed). */
    private inner class ServerPort(private val server: Server, val device: BluetoothDevice) : BlePort {
        override val address: String = device.address
        private var events: BlePortEvents? = null
        private var mtu = 23
        var subscribed = false

        @Volatile var closed = false
        private val pending = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)

        override fun open(events: BlePortEvents) {
            handler.post {
                this.events = events
                if (!closed) events.onReady(mtu - 3) else events.onDisconnected()
            }
        }

        fun mtuChanged(mtu: Int) {
            this.mtu = mtu
            events?.onAttPayload(mtu - 3)
        }

        override fun send(packet: ByteArray): Boolean {
            if (closed || !pending.compareAndSet(null, packet.copyOf())) return false
            handler.post { server.enqueueNotify(this) }
            return true
        }

        fun takePending(): ByteArray? = pending.get()

        fun sent(ok: Boolean) {
            pending.set(null)
            events?.onSent(ok)
        }

        fun received(packet: ByteArray) {
            if (!closed) events?.onPacket(packet)
        }

        override fun close() {
            handler.post {
                if (closed) return@post
                server.cancel(this)
                disconnected()
            }
        }

        fun disconnected() {
            if (closed) return
            closed = true
            pending.set(null)
            events?.onDisconnected()
        }
    }

    // ---- Joiner: GATT client ----------------------------------------------------------------------

    private inner class ClientPort(override val address: String) : BlePort {
        private var events: BlePortEvents? = null
        private var gatt: BluetoothGatt? = null
        private var toHost: BluetoothGattCharacteristic? = null
        private var attPayload = BleLimits.MIN_ATT_PAYLOAD

        @Volatile private var ready = false

        @Volatile private var closed = false
        private val inFlight = AtomicBoolean(false)

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    guard({ }) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    requestMtuOrDiscover(g) { fail() }
                } else {
                    if (!ready) stats.connectFailures++
                    fail()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) attPayload = mtu - 3
                if (!ready) discover(g) else events?.onAttPayload(attPayload)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UUID.fromString(BleGattSpec.SERVICE))
                val write = service?.getCharacteristic(UUID.fromString(BleGattSpec.TO_HOST))
                val notify = service?.getCharacteristic(UUID.fromString(BleGattSpec.TO_PEER))
                val cccd = notify?.getDescriptor(UUID.fromString(BleGattSpec.CCCD))
                if (status != BluetoothGatt.GATT_SUCCESS || write == null || notify == null || cccd == null) {
                    fail()
                    return
                }
                toHost = write
                val started = guard({ false }) {
                    g.setCharacteristicNotification(notify, true) && writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                if (!started) fail()
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail()
                    return
                }
                if (ready || closed) return
                ready = true
                events?.onReady(attPayload)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (!inFlight.compareAndSet(true, false)) return
                events?.onSent(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == UUID.fromString(BleGattSpec.TO_PEER) && !closed) events?.onPacket(value.copyOf())
            }

            @Deprecated("Android 12 delivers notifications here")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == UUID.fromString(BleGattSpec.TO_PEER) && !closed) events?.onPacket(value.copyOf())
            }
        }

        override fun open(events: BlePortEvents) {
            handler.post {
                this.events = events
                val g = connectGatt(address, callback)
                if (g == null) {
                    stats.connectFailures++
                    fail()
                    return@post
                }
                gatt = g
                handler.postDelayed({
                    if (!ready && !closed) {
                        stats.connectFailures++
                        fail()
                    }
                }, CONNECT_TIMEOUT_MS)
            }
        }

        private fun discover(g: BluetoothGatt) = discoverOr(g) { fail() }

        override fun send(packet: ByteArray): Boolean {
            if (!ready || closed || !inFlight.compareAndSet(false, true)) return false
            val copy = packet.copyOf()
            handler.post { write(copy) }
            return true
        }

        private fun write(packet: ByteArray) {
            val g = gatt
            val ch = toHost
            if (closed || g == null || ch == null) {
                if (inFlight.compareAndSet(true, false)) events?.onSent(false)
                return
            }
            val ok = guard({ false }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    ch.value = packet
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok && inFlight.compareAndSet(true, false)) events?.onSent(false)
        }

        override fun close() {
            handler.post { fail() }
        }

        private fun fail() {
            if (closed) return
            closed = true
            ready = false
            closeGatt(gatt)
            gatt = null
            events?.onDisconnected()
        }
    }

    // ---- Discovery --------------------------------------------------------------------------------

    private inner class Scanner : BleScanner {
        private var callback: ScanCallback? = null

        override fun start(onSeen: (address: String) -> Unit) {
            handler.post {
                val scanner = guard({ null }) { adapter?.bluetoothLeScanner }
                if (scanner == null) {
                    stats.scanFailures++
                    return@post
                }
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) = seen(result)

                    override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::seen)

                    override fun onScanFailed(errorCode: Int) {
                        stats.scanFailures++
                    }

                    private fun seen(result: ScanResult) {
                        stats.scanResults++
                        val device = result.device
                        devices.put(device.address, device)
                        onSeen(device.address)
                    }
                }
                callback = cb
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BleGattSpec.SERVICE))).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                stats.scanStarts++
                if (!guard({ false }) { scanner.startScan(listOf(filter), settings, cb); true }) stats.scanFailures++
            }
        }

        override fun stop() {
            handler.post {
                val cb = callback ?: return@post
                callback = null
                guard({ }) { adapter?.bluetoothLeScanner?.stopScan(cb) }
            }
        }

        override fun readAdvert(address: String, onResult: (ByteArray?) -> Unit) {
            handler.post { AdvertRead(address, onResult).start() }
        }
    }

    /** One short connection: MTU, discovery, read the advert characteristic, disconnect. */
    private inner class AdvertRead(private val address: String, private val onResult: (ByteArray?) -> Unit) {
        private var gatt: BluetoothGatt? = null
        private var done = false

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    requestMtuOrDiscover(g) { finish(null) }
                } else {
                    finish(null)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = discover(g)

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(UUID.fromString(BleGattSpec.SERVICE))?.getCharacteristic(UUID.fromString(BleGattSpec.ADVERT))
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || !guard({ false }) { g.readCharacteristic(ch) }) finish(null)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                finish(value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }

            @Deprecated("Android 12 delivers reads here")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                finish(characteristic.value?.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }

        fun start() {
            stats.advertReads++
            val g = connectGatt(address, callback)
            if (g == null) {
                finish(null)
                return
            }
            gatt = g
            handler.postDelayed({ finish(null) }, ADVERT_READ_TIMEOUT_MS)
        }

        private fun discover(g: BluetoothGatt) = discoverOr(g) { finish(null) }

        private fun finish(value: ByteArray?) {
            if (done) return
            done = true
            if (value == null) stats.advertReadFailures++
            closeGatt(gatt)
            gatt = null
            onResult(value?.takeIf { it.size <= BleAdvertCodec.MAX_BYTES }?.copyOf())
        }
    }

    // ---- GATT client lifecycle shared by joiner ports and advert reads -----------------------------

    /** Connects over LE 1M with callbacks on the radio's thread; null when the platform refused. */
    private fun connectGatt(address: String, callback: BluetoothGattCallback): BluetoothGatt? =
        device(address)?.let { d -> guard({ null }) { d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler) } }

    /** Asks for the large MTU; if the request cannot be made, discovers services at once. */
    private fun requestMtuOrDiscover(g: BluetoothGatt, onFail: () -> Unit) {
        if (!guard({ false }) { g.requestMtu(BleGattSpec.REQUESTED_MTU) }) discoverOr(g, onFail)
    }

    private fun discoverOr(g: BluetoothGatt, onFail: () -> Unit) {
        if (!guard({ false }) { g.discoverServices() }) onFail()
    }

    private fun closeGatt(g: BluetoothGatt?) {
        guard({ }) {
            g?.disconnect()
            g?.close()
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    private companion object {
        /** Centrals a host keeps at once: 3 players + 2 watchers + one advert reader, with room to spare. */
        const val MAX_CONNECTIONS = 8
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val ADVERT_READ_TIMEOUT_MS = 8_000L
        const val NOTIFY_TIMEOUT_MS = 2_000L

        /** Scanned devices remembered for connecting (well above [BleBrowserConfig.maxHosts]). */
        const val MAX_DEVICES = 64
    }
}

/** At most [capacity] entries, the least recently used evicted first. Thread-safe. */
internal class RecentEntries<K : Any, V : Any>(private val capacity: Int) {
    init {
        require(capacity >= 1)
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        map[key] = value
    }

    val size: Int @Synchronized get() = map.size
}
