package cn.wpgd.app.ble

import cn.wpgd.app.diagnostics.LatencySamples

/**
 * The Bluetooth diagnostics lines the G5 device spike reads (`dev-docs/ble-device-spike.md`): the
 * host's simultaneous connections, every recent link's MTU, setup time, packet and record timings,
 * and the table's command-to-receipt times. Counts and milliseconds only: no payload, no Bluetooth
 * address (a phone's address can be a permanent identifier, P8), no room or member identity.
 */
object BleReport {
    fun lines(network: BleRoomNetwork, receiptMs: LatencySamples?): List<String> = lines(network.stats(), receiptMs)

    fun lines(stats: BleNetworkStats, receiptMs: LatencySamples?): List<String> = buildList {
        val s = stats.server
        add(
            if (s == null) {
                "ble server none links_opened=${stats.linksOpened}"
            } else {
                "ble server connected=${s.connected} max_connected=${s.maxConnected} subscribed=${s.subscribed} refused=${s.refused} " +
                    "advertising=${s.advertising} advertise_failures=${s.advertiseFailures} links_opened=${stats.linksOpened}"
            },
        )
        add("ble radio ${stats.radio.summary()}")
        stats.links.forEachIndexed { i, (_, m) -> add("ble link#$i ${m.summary()}") }
        if (receiptMs != null) add("ble table ${receiptMs.summary("receipt_ms")}")
    }
}
