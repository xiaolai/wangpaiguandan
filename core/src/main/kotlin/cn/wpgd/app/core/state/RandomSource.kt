package cn.wpgd.app.core.state

/**
 * The only source of randomness the reducer accepts. The caller injects it in [Command.Start], so
 * the reducer itself never reads a clock, a platform generator or `kotlin.random.Random`.
 */
interface RandomSource {
    /** The next 64 uniformly distributed bits. */
    fun nextLong(): Long
}

/**
 * A uniform integer in `0 until bound`, by rejection sampling over 63-bit values (no modulo bias).
 * Part of the cross-platform deal contract: take `r = nextLong() ushr 1`, `v = r % bound`; accept
 * when `r - v + (bound - 1)` does not overflow a signed 64-bit integer, otherwise draw again.
 */
fun RandomSource.nextInt(bound: Int): Int {
    require(bound > 0) { "bound must be positive, got $bound" }
    val b = bound.toLong()
    while (true) {
        val r = nextLong() ushr 1
        val v = r % b
        if (r - v + (b - 1) >= 0) return v.toInt()
    }
}

/**
 * SplitMix64 (Steele, Lea and Flood, "Fast splittable pseudorandom number generators", OOPSLA 2014;
 * the reference C code by Sebastiano Vigna). State is one 64-bit word; each step adds the golden
 * gamma 0x9E3779B97F4A7C15 and mixes the result. Same seed, same sequence, on every platform.
 *
 * Not cryptographic. The deal is secret only because the seed is: the host must draw the seed from
 * a platform secure random source and must never put it in a projection, event or diagnostic.
 */
class SplitMix64(seed: Long) : RandomSource {
    private var state: Long = seed

    override fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    private companion object {
        val GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX2 = 0x94D049BB133111EBuL.toLong()
    }
}

/**
 * Fisher–Yates: for i from the last index down to 1, swap item i with item `random.nextInt(i + 1)`.
 * Returns a new list; the receiver is not changed.
 */
fun <T> List<T>.shuffledWith(random: RandomSource): List<T> {
    val out = toMutableList()
    for (i in out.lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val t = out[i]; out[i] = out[j]; out[j] = t
    }
    return out
}
