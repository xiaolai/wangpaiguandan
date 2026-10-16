package cn.wpgd.app.room

import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.RoomInfo

/**
 * GDN-021 (C9): when a Bluetooth room cannot be reached, the join screen offers the same Wi-Fi as an
 * explicit choice. The app never switches medium by itself, so the medium a screen names
 * ([RoomInfo.medium]) is always the one actually carrying the room.
 */
object MediumFallback {
    private val OFFERED_AT = setOf(JoinPhase.FAILED, JoinPhase.HOST_LOST)

    fun offersWifi(info: RoomInfo?, phase: JoinPhase?): Boolean = info?.medium == RoomMedium.BLUETOOTH && phase in OFFERED_AT
}
