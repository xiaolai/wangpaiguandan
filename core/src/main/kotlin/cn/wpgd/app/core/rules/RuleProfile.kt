package cn.wpgd.app.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An immutable rule profile. Built only by [RuleProfileLoader], so every value in [options] has
 * been decoded and range-checked by its own [RuleOption].
 *
 * [options] holds only what the profile sets explicitly; [get] falls back to the registry default.
 * A provisional profile is a development profile; a signed (release) profile must not be provisional.
 */
@ConsistentCopyVisibility
data class RuleProfile internal constructor(
    val id: String,
    val version: String,
    val displayName: String,
    val provisional: Boolean,
    val sourceRefs: List<String>,
    val options: Map<RuleOption<*>, Any?>,
    val registry: OptionRegistry,
    val warnings: List<RuleProfileWarning>,
) {
    /** Effective value: the explicit setting, else the registry default. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(option: RuleOption<T>): T {
        require(registry[option.id] === option) { "Option ${option.id} is not in this profile's registry" }
        return if (option in options) options[option] as T else option.default
    }

    /**
     * SHA-256 (lowercase hex) of a canonical JSON form: keys sorted, no whitespace.
     * It covers every content field and the effective value of every active option, plus any
     * retired option the profile still sets, so changing a registry default also changes the hash.
     */
    val hash: String by lazy { sha256(canonicalJson()) }

    internal fun canonicalJson(): String {
        val effective = registry.active.map { it.id to encoded(it) } +
            options.keys.filter { it.deprecatedSince != null }.map { it.id to encoded(it) }
        val root = sortedObject(
            listOf(
                "displayName" to JsonPrimitive(displayName),
                "format" to JsonPrimitive(CANONICAL_FORMAT),
                "id" to JsonPrimitive(id),
                "options" to sortedObject(effective),
                "provisional" to JsonPrimitive(provisional),
                "sourceRefs" to JsonArray(sourceRefs.map(::JsonPrimitive)),
                "version" to JsonPrimitive(version),
            ),
        )
        return Json.encodeToString(JsonElement.serializer(), root)
    }

    private fun <T> encoded(option: RuleOption<T>): JsonElement = option.encode(get(option))

    private companion object {
        /** Bump when the canonical form itself changes, so old and new hashes can never collide. */
        const val CANONICAL_FORMAT = 1

        fun sortedObject(entries: List<Pair<String, JsonElement>>): JsonObject =
            JsonObject(entries.sortedBy { it.first }.toMap(LinkedHashMap()))

        fun sha256(text: String): String = Sha256.hex(text)
    }
}

sealed interface RuleProfileWarning {
    /** The profile sets an option the registry has retired. It loads, but the setting should be removed. */
    data class DeprecatedOption(val key: String, val since: String) : RuleProfileWarning
}
