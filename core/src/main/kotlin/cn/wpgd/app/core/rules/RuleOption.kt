package cn.wpgd.app.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Unsigned decisions that a registry option depends on: D1–D18 from section 9 of `rules/base-v1.md`
 * (D19–D24 there have no option), D25–D40 from section 10 of `rules/match-v1.md`.
 */
enum class Decision {
    D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D12, D13, D14, D15, D16, D17, D18,
    D25, D26, D27, D28, D29, D30, D31, D32, D33, D34, D35, D36, D37, D38, D39, D40,
}

/**
 * One variable rule. Its [id] is the key used in profile JSON and never changes meaning.
 *
 * - [source]: the `rules/base-v1.md` row(s) the default comes from.
 * - [decision]: the section-9 decision the default depends on, or null when the default is sourced.
 * - [pending]: base-v1 marks the decision itself as pending further source checks.
 * - [deprecatedSince]: profile version from which the option is retired. Profiles that still set it
 *   load with a warning; once the entry is deleted from the registry they are rejected as unknown.
 */
sealed class RuleOption<T> {
    abstract val id: String
    abstract val default: T
    abstract val source: String
    abstract val decision: Decision?
    abstract val pending: Boolean
    abstract val deprecatedSince: String?

    /** Human-readable value type, used in error messages. */
    abstract val typeName: String

    internal sealed interface Decoded<out T> {
        data class Ok<T>(val value: T) : Decoded<T>
        data object WrongType : Decoded<Nothing>
        data class OutOfRange(val detail: String) : Decoded<Nothing>
    }

    internal abstract fun decode(element: JsonElement): Decoded<T>
    internal abstract fun encode(value: T): JsonElement

    final override fun toString(): String = id
}

/** An integer option, bounded by [range]. */
class IntOption(
    override val id: String,
    override val default: Int,
    val range: IntRange,
    override val source: String,
    override val decision: Decision?,
    override val pending: Boolean = false,
    override val deprecatedSince: String? = null,
) : RuleOption<Int>() {
    init { require(default in range) { "Default of $id is outside $range" } }
    override val typeName get() = "integer in $range"
    override fun decode(element: JsonElement): Decoded<Int> = decodeBounded(element, range)
    override fun encode(value: Int): JsonElement = JsonPrimitive(value)
}

/** An integer limit where JSON `null` means "no limit". */
class LimitOption(
    override val id: String,
    override val default: Int?,
    val range: IntRange,
    override val source: String,
    override val decision: Decision?,
    override val pending: Boolean = false,
    override val deprecatedSince: String? = null,
) : RuleOption<Int?>() {
    init { require(default == null || default in range) { "Default of $id is outside $range" } }
    override val typeName get() = "integer in $range, or null for no limit"
    override fun decode(element: JsonElement): Decoded<Int?> {
        if (element is JsonNull) return Decoded.Ok(null)
        return decodeBounded(element, range)
    }
    override fun encode(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
}

class BoolOption(
    override val id: String,
    override val default: Boolean,
    override val source: String,
    override val decision: Decision?,
    override val pending: Boolean = false,
    override val deprecatedSince: String? = null,
) : RuleOption<Boolean>() {
    override val typeName get() = "boolean"
    override fun decode(element: JsonElement): Decoded<Boolean> {
        if (element !is JsonPrimitive || element is JsonNull || element.isString) return Decoded.WrongType
        return element.booleanOrNull?.let { Decoded.Ok(it) } ?: Decoded.WrongType
    }
    override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
}

/**
 * One of a fixed set of named values. [choices] lists only the values the engine implements, so a
 * profile naming a variant that has no code is rejected as out of range instead of being ignored.
 */
class ChoiceOption(
    override val id: String,
    override val default: String,
    val choices: Set<String>,
    override val source: String,
    override val decision: Decision?,
    override val pending: Boolean = false,
    override val deprecatedSince: String? = null,
) : RuleOption<String>() {
    init { require(default in choices) { "Default of $id is not one of $choices" } }
    override val typeName get() = "one of ${choices.sorted()}"
    override fun decode(element: JsonElement): Decoded<String> {
        if (element !is JsonPrimitive || element is JsonNull || !element.isString) return Decoded.WrongType
        val v = element.content
        return if (v in choices) Decoded.Ok(v) else Decoded.OutOfRange("'$v' is not one of ${choices.sorted()}")
    }
    override fun encode(value: String): JsonElement = JsonPrimitive(value)
}

/** An exact Int within [range]; shared by [IntOption] and the non-null case of [LimitOption]. */
private fun decodeBounded(element: JsonElement, range: IntRange): RuleOption.Decoded<Int> {
    val n = element.strictInt() ?: return RuleOption.Decoded.WrongType
    return if (n in range) RuleOption.Decoded.Ok(n) else RuleOption.Decoded.OutOfRange("$n is outside $range")
}

/** A JSON number literal that is an exact Int: rejects strings ("4"), fractions (4.5) and null. */
private fun JsonElement.strictInt(): Int? =
    if (this !is JsonPrimitive || this is JsonNull || isString) null else intOrNull
