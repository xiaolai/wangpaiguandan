package cn.wpgd.app.core.state

import kotlinx.serialization.Serializable

/**
 * The four playing seats, declared in play order. Play passes counter-clockwise to the player on
 * one's right (下家) (base-v1 D19, from JS17 line 77 and CG22a line 263): sitting at SOUTH, that is
 * EAST. Partners sit opposite: SOUTH–NORTH and EAST–WEST.
 */
@Serializable
enum class Seat {
    SOUTH, EAST, NORTH, WEST;

    /** The seat that plays after this one (下家). */
    val next: Seat get() = entries[(ordinal + 1) % entries.size]

    /** The partner opposite (对家). */
    val partner: Seat get() = entries[(ordinal + 2) % entries.size]
}
