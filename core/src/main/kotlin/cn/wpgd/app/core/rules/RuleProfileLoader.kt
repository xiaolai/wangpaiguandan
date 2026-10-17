package cn.wpgd.app.core.rules

import cn.wpgd.app.core.readOnlyCopy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Why a profile was rejected. Returned inside [Result.failure]; the loader never throws. */
sealed class RuleProfileError(message: String) : Exception(message) {
    class Malformed(val detail: String) : RuleProfileError("Malformed profile JSON: $detail")
    class UnknownField(val field: String) : RuleProfileError("Unknown profile field '$field'")
    class MissingField(val field: String) : RuleProfileError("Missing required field '$field'")
    class WrongType(val field: String, val expected: String) : RuleProfileError("'$field' must be $expected")
    class UnknownOption(val key: String) : RuleProfileError("Unknown option '$key'")
    class OutOfRange(val key: String, val detail: String) : RuleProfileError("Option '$key': $detail")
    class BadVersion(val version: String) : RuleProfileError("Version '$version' is not MAJOR.MINOR.PATCH")
    class BadId(val id: String) : RuleProfileError("Profile id '$id' must match ${ID_PATTERN.pattern}")
}

/** Profile ids double as file names (`rules/profiles/<id>.json`), so no dots, slashes or spaces. */
internal val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
private val VERSION_PATTERN = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

/**
 * Strict JSON loader. Unknown top-level fields, unknown option keys and duplicate keys are errors,
 * never ignored. The file is read as a JSON tree and checked field by field, so each failure maps
 * to a typed [RuleProfileError] rather than a parser message.
 *
 * File shape: `id`, `version`, `displayName`, `provisional` (required); `sourceRefs`, `options` (optional).
 */
object RuleProfileLoader {
    private val json = Json
    private val required = listOf("id", "version", "displayName", "provisional")
    private val optional = listOf("sourceRefs", "options")

    /** A valid profile nests 2 deep (root, then `options` or `sourceRefs`). The tree parser recurses per level. */
    private const val MAX_DEPTH = 16

    fun parse(json: String, registry: OptionRegistry = RuleOptions.registry): Result<RuleProfile> =
        try {
            Result.success(load(json, registry))
        } catch (e: RuleProfileError) {
            Result.failure(e)
        }

    private fun load(text: String, registry: OptionRegistry): RuleProfile {
        rejectDeepNesting(text)
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw RuleProfileError.Malformed(e.message ?: "not valid JSON")
        }
        if (root !is JsonObject) throw RuleProfileError.Malformed("top level must be an object")
        rejectDuplicateKeys(text)
        root.keys.firstOrNull { it !in required && it !in optional }?.let { throw RuleProfileError.UnknownField(it) }
        required.firstOrNull { it !in root }?.let { throw RuleProfileError.MissingField(it) }

        val id = root.string("id")
        if (!ID_PATTERN.matches(id)) throw RuleProfileError.BadId(id)
        val version = root.string("version")
        if (!VERSION_PATTERN.matches(version)) throw RuleProfileError.BadVersion(version)

        val warnings = mutableListOf<RuleProfileWarning>()
        val options = LinkedHashMap<RuleOption<*>, Any?>()
        for ((key, element) in root.objectOrEmpty("options")) {
            val option = registry[key] ?: throw RuleProfileError.UnknownOption(key)
            options[option] = when (val decoded = option.decode(element)) {
                is RuleOption.Decoded.Ok -> decoded.value
                RuleOption.Decoded.WrongType -> throw RuleProfileError.WrongType("options.$key", option.typeName)
                is RuleOption.Decoded.OutOfRange -> throw RuleProfileError.OutOfRange(key, decoded.detail)
            }
            option.deprecatedSince?.let { warnings += RuleProfileWarning.DeprecatedOption(key, it) }
        }

        return RuleProfile(
            id = id,
            version = version,
            displayName = root.string("displayName"),
            provisional = root.boolean("provisional"),
            // Read-only snapshots (round 3 #40): a Java caller could otherwise change a value after the hash was taken.
            sourceRefs = root.stringList("sourceRefs").readOnlyCopy(),
            options = options.readOnlyCopy(),
            registry = registry,
            warnings = warnings.readOnlyCopy(),
        ).also(::rejectEmptyBombRange)
    }

    /**
     * Each option checks only its own range, so `bomb.minSize` above a non-null `bomb.maxSize` would
     * load and silently remove every same-rank bomb (R4.1). Reject it as [RuleProfileError.OutOfRange].
     */
    private fun rejectEmptyBombRange(profile: RuleProfile) {
        val registry = profile.registry
        if (registry[RuleOptions.BombMinSize.id] !== RuleOptions.BombMinSize || registry[RuleOptions.BombMaxSize.id] !== RuleOptions.BombMaxSize) return
        val min = profile[RuleOptions.BombMinSize]
        val max = profile[RuleOptions.BombMaxSize] ?: return
        if (min > max) throw RuleProfileError.OutOfRange(RuleOptions.BombMinSize.id, "$min is greater than ${RuleOptions.BombMaxSize.id} $max")
    }

    private fun JsonObject.string(field: String): String {
        val e = getValue(field)
        if (e !is JsonPrimitive || e is JsonNull || !e.isString) throw RuleProfileError.WrongType(field, "a string")
        return e.content
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val e = getValue(field)
        if (e !is JsonPrimitive || e is JsonNull || e.isString) throw RuleProfileError.WrongType(field, "a boolean")
        return e.booleanOrNull ?: throw RuleProfileError.WrongType(field, "a boolean")
    }

    private fun JsonObject.stringList(field: String): List<String> {
        val e = this[field] ?: return emptyList()
        if (e !is JsonArray) throw RuleProfileError.WrongType(field, "an array of strings")
        return e.map {
            if (it !is JsonPrimitive || it is JsonNull || !it.isString) throw RuleProfileError.WrongType(field, "an array of strings")
            it.content
        }
    }

    private fun JsonObject.objectOrEmpty(field: String): JsonObject {
        val e = this[field] ?: return JsonObject(emptyMap())
        return e as? JsonObject ?: throw RuleProfileError.WrongType(field, "an object")
    }

    /**
     * kotlinx-serialization's tree parser keeps the last of two equal keys without complaint, so a
     * profile could set one option twice and the second would silently win. Reject that instead.
     * Precondition: [text] already parsed as valid JSON.
     */
    private fun rejectDuplicateKeys(text: String) {
        val scopes = ArrayDeque<MutableSet<String>?>() // null marks an array scope
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> scopes.addLast(mutableSetOf())
                '[' -> scopes.addLast(null)
                '}', ']' -> scopes.removeLast()
                '"' -> {
                    val end = stringEnd(text, i)
                    var next = end + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        // Decode escapes so "a" and "a" count as the same key.
                        val key = (json.parseToJsonElement(text.substring(i, end + 1)) as JsonPrimitive).content
                        val keys = checkNotNull(scopes.lastOrNull()) { "key outside an object in valid JSON" }
                        if (!keys.add(key)) throw RuleProfileError.Malformed("duplicate key '$key'")
                    }
                    i = end
                }
            }
            i++
        }
    }

    /**
     * Rejects input nested deeper than [MAX_DEPTH] before the recursive parser sees it, so hostile
     * input fails as [RuleProfileError.Malformed] instead of a StackOverflowError. Makes no other
     * validity assumption: strings, escapes and brackets may be unbalanced or unterminated.
     */
    private fun rejectDeepNesting(text: String) {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) throw RuleProfileError.Malformed("nested deeper than $MAX_DEPTH levels")
                '}', ']' -> depth--
            }
            i++
        }
    }

    /** Index of the closing quote of the string literal that opens at [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
        return i
    }
}
