package cn.wpgd.app.data

import cn.wpgd.app.core.state.Seat

/**
 * A display name (GDN-002, audit A35). It is only a label: seats come from the host's admission,
 * never from a name, and a name that reads like a seat or a role is refused so it cannot claim
 * one visually either. Bounded to [MAX_CODE_POINTS] code points, no control, format or other
 * default-ignorable (invisible) characters, no unpaired surrogates, at least one visible character, surrounding whitespace
 * trimmed.
 */
@JvmInline
value class Nickname private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_CODE_POINTS = 12

        /** Names that read as a seat, a direction or a table role. Compared after trimming, case-insensitively. */
        val RESERVED: Set<String> = (
            Seat.entries.map { it.name } +
                listOf("东", "南", "西", "北", "东家", "南家", "西家", "北家", "上家", "下家", "对家", "队友", "我", "自己", "你") +
                listOf("房主", "主机", "电脑", "机器人", "AI", "BOT", "HOST", "观众", "空位", "托管")
            ).map { it.lowercase() }.toSet()

        fun parse(raw: String): NicknameResult {
            val name = raw.trim()
            if (name.isEmpty()) return NicknameResult.Invalid(NicknameProblem.EMPTY)
            if (name.codePointCount(0, name.length) > MAX_CODE_POINTS) return NicknameResult.Invalid(NicknameProblem.TOO_LONG)
            val bad = name.codePoints().anyMatch { cp ->
                val t = Character.getType(cp)
                t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() || t == Character.UNASSIGNED.toInt() ||
                    t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt() || t == Character.PRIVATE_USE.toInt() ||
                    // An unpaired surrogate is malformed UTF-16: UTF-8 cannot carry it, so it would not survive storage or the wire.
                    t == Character.SURROGATE.toInt()
            }
            if (bad || hasInvisible(name)) return NicknameResult.Invalid(NicknameProblem.INVISIBLE_OR_CONTROL)
            if (name.lowercase() in RESERVED) return NicknameResult.Invalid(NicknameProblem.RESERVED)
            return NicknameResult.Valid(Nickname(name))
        }

        /**
         * True when [name] holds a default-ignorable code point (Unicode DerivedCoreProperties: drawn as
         * nothing, so "房\u034F主" would read as 房主) or nothing visible at all (only marks or spaces).
         * The emoji variation selectors U+FE0E/U+FE0F are allowed right after a symbol, where they
         * only choose the emoji's style.
         */
        private fun hasInvisible(name: String): Boolean {
            val cps = name.codePoints().toArray()
            val visible = cps.any { Character.getType(it) !in INVISIBLE_TYPES }
            return !visible || cps.indices.any { i ->
                val cp = cps[i]
                val emojiStyle = (cp == 0xFE0E || cp == 0xFE0F) && i > 0 && Character.getType(cps[i - 1]) == Character.OTHER_SYMBOL.toInt()
                !emojiStyle && DEFAULT_IGNORABLE.any { cp in it }
            }
        }

        private val INVISIBLE_TYPES = setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
        )

        /** Default_Ignorable_Code_Point (Unicode 15.1 DerivedCoreProperties.txt). */
        private val DEFAULT_IGNORABLE: List<IntRange> = listOf(
            0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5,
            0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164,
            0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3,
            0x1D173..0x1D17A, 0xE0000..0xE0FFF,
        )
    }
}

enum class NicknameProblem(val messageKey: String) {
    EMPTY("nickname.empty"),
    TOO_LONG("nickname.too_long"),
    INVISIBLE_OR_CONTROL("nickname.invisible_or_control"),

    /** Reads as a seat or a role (东家, 房主, AI …). */
    RESERVED("nickname.reserved"),
}

sealed interface NicknameResult {
    data class Valid(val nickname: Nickname) : NicknameResult
    data class Invalid(val problem: NicknameProblem) : NicknameResult
}

/** Persists the nickname in the profile store. A stored value that no longer parses reads as none. */
class NicknameRepository(private val store: KeyValueStore) {
    fun current(): Nickname? = store.get(KEY)?.let { (Nickname.parse(it) as? NicknameResult.Valid)?.nickname }

    /** Saves [raw] if valid; returns the parse result either way. */
    fun set(raw: String): NicknameResult = Nickname.parse(raw).also { if (it is NicknameResult.Valid) store.put(KEY, it.nickname.value) }

    private companion object {
        const val KEY = "nickname"
    }
}
