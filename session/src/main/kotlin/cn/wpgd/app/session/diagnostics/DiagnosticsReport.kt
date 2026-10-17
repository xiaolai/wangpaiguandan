package cn.wpgd.app.session.diagnostics

import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.session.wire.PROTOCOL_VERSION

/**
 * A local diagnostics export (WI-12, GDN-108, P8): a header line, then one `key=value` line per
 * [DiagnosticField]. Built only through [Builder], whose methods take fields, numbers, flags, enum
 * constants and rule profiles, never text or bytes. [render] re-checks every line with [validate]
 * and throws if any is off the allow-list, so a defect fails loudly instead of exporting it.
 *
 * Local only: this class returns text to its caller. It opens no file and no socket; showing or
 * copying the text is the user's choice in the app.
 */
class DiagnosticsReport private constructor(private val values: List<Pair<String, String>>) {
    fun render(): String {
        val text = buildString {
            append(HEADER).append('\n')
            for ((k, v) in values) append(k).append('=').append(v).append('\n')
        }
        val problems = validate(text)
        check(problems.isEmpty()) { "diagnostics export is off its allow-list: $problems" }
        return text
    }

    /** Each value is checked against its field's shape when written (so a count is never negative). */
    class Builder {
        private val values = LinkedHashMap<String, String>()

        fun count(field: DiagnosticField, value: Long): Builder = plain(field, Shape.COUNT, value.toString())

        fun count(field: DiagnosticField, key: Enum<*>, value: Long): Builder = keyed(field, Shape.COUNT, key, value.toString())

        fun revision(field: DiagnosticField, value: Long): Builder = plain(field, Shape.REVISION, value.toString())

        fun millis(field: DiagnosticField, value: Long): Builder = plain(field, Shape.MILLIS, value.toString())

        fun flag(field: DiagnosticField, value: Boolean): Builder = plain(field, Shape.FLAG, value.toString())

        /** The app's version name: three numbers, so no free text can enter through it. */
        fun appVersion(field: DiagnosticField, version: AppVersion): Builder = plain(field, Shape.APP_VERSION, version.toString())

        fun flag(field: DiagnosticField, key: Enum<*>, value: Boolean): Builder = keyed(field, Shape.FLAG, key, value.toString())

        fun token(field: DiagnosticField, value: Enum<*>): Builder {
            val tokens = field.tokens
            require(field.shape == Shape.TOKEN && tokens != null) { "${field.key} is not a token field" }
            require(tokens.any { it === value }) { "${field.key} does not accept ${value.javaClass.simpleName}.${value.name}" }
            return put(field.key, value.name)
        }

        /** The profile's public id, version, hash and whether it is provisional (unsigned). */
        fun profile(role: ProfileRole, profile: RuleProfile): Builder {
            val f = when (role) {
                ProfileRole.BASE -> listOf(DiagnosticField.PROFILE_BASE_ID, DiagnosticField.PROFILE_BASE_VERSION, DiagnosticField.PROFILE_BASE_HASH, DiagnosticField.PROFILE_BASE_PROVISIONAL)
                ProfileRole.MATCH -> listOf(DiagnosticField.PROFILE_MATCH_ID, DiagnosticField.PROFILE_MATCH_VERSION, DiagnosticField.PROFILE_MATCH_HASH, DiagnosticField.PROFILE_MATCH_PROVISIONAL)
            }
            plain(f[0], Shape.PROFILE_ID, profile.id)
            plain(f[1], Shape.PROFILE_VERSION, profile.version)
            plain(f[2], Shape.PROFILE_HASH, profile.hash)
            return flag(f[3], profile.provisional)
        }

        fun build(): DiagnosticsReport {
            val all = listOf(DiagnosticField.FORMAT.key to FORMAT.toString(), DiagnosticField.PROTOCOL_VERSION.key to PROTOCOL_VERSION.toString()) +
                values.entries.map { it.key to it.value }
            return DiagnosticsReport(all)
        }

        private fun plain(field: DiagnosticField, shape: Shape, value: String): Builder {
            require(field.shape == shape && field.keys == null) { "${field.key} is not a plain ${shape.name.lowercase()} field" }
            require(field != DiagnosticField.FORMAT && field != DiagnosticField.PROTOCOL_VERSION) { "${field.key} is written by build()" }
            require(shape.pattern.matches(value)) { "${field.key}: value does not have the ${shape.name.lowercase()} shape" }
            return put(field.key, value)
        }

        private fun keyed(field: DiagnosticField, shape: Shape, key: Enum<*>, value: String): Builder {
            val keys = field.keys
            require(field.shape == shape && keys != null) { "${field.key} is not a keyed ${shape.name.lowercase()} field" }
            require(keys.any { it === key }) { "${field.key} has no sub-key ${key.javaClass.simpleName}.${key.name}" }
            require(shape.pattern.matches(value)) { "${field.key}: value does not have the ${shape.name.lowercase()} shape" }
            return put("${field.key}.${key.name}", value)
        }

        private fun put(key: String, value: String): Builder {
            require(key !in values) { "$key written twice" }
            values[key] = value
            return this
        }
    }

    companion object {
        const val HEADER = "wpgd-diagnostics"
        const val FORMAT = 1

        private val plainFields: Map<String, DiagnosticField> = DiagnosticField.entries.filter { it.keys == null }.associateBy { it.key }

        private val keyedFields: Map<String, DiagnosticField> = DiagnosticField.entries
            .filter { it.keys != null }
            .flatMap { f -> f.keys!!.map { "${f.key}.${it.name}" to f } }
            .toMap()

        /**
         * Every problem with [text] as an export: a wrong header, a line that is not `key=value`, a
         * key that is not on the allow-list, a duplicate key, or a value of the wrong shape or outside
         * its field's tokens. Empty when the text is a valid export.
         */
        fun validate(text: String): List<String> {
            val problems = ArrayList<String>()
            val lines = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
            if (lines.firstOrNull() != HEADER) problems += "first line is not the header '$HEADER'"
            val seen = HashSet<String>()
            for ((i, line) in lines.withIndex().drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) { problems += "line ${i + 1}: not key=value"; continue }
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                if (!seen.add(key)) problems += "line ${i + 1}: duplicate key $key"
                val field = plainFields[key] ?: keyedFields[key]
                if (field == null) { problems += "line ${i + 1}: key $key is not on the allow-list"; continue }
                if (!field.shape.pattern.matches(value)) { problems += "line ${i + 1}: $key value does not have the ${field.shape.name.lowercase()} shape"; continue }
                if (field.shape == Shape.TOKEN && field.tokens!!.none { it.name == value }) problems += "line ${i + 1}: $key does not accept token $value"
                if (field == DiagnosticField.FORMAT && value != FORMAT.toString()) problems += "line ${i + 1}: unknown format $value"
            }
            if (DiagnosticField.FORMAT.key !in seen) problems += "no format line"
            return problems
        }
    }
}

/** An app version name of the form MAJOR.MINOR.PATCH (`versionName` in app/build.gradle.kts). Holds three numbers only. */
class AppVersion private constructor(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"

    override fun equals(other: Any?): Boolean = other is AppVersion && other.major == major && other.minor == minor && other.patch == patch

    override fun hashCode(): Int = (major * 31 + minor) * 31 + patch

    companion object {
        /** The export's shape for the field, so parsing and export validation cannot drift apart. */
        private val PATTERN = Shape.APP_VERSION.pattern

        /** Throws [IllegalArgumentException] for anything but MAJOR.MINOR.PATCH (a suffix such as `-dev` included). */
        fun parse(name: String): AppVersion {
            val m = requireNotNull(PATTERN.matchEntire(name)) { "app version '$name' is not MAJOR.MINOR.PATCH" }
            val (a, b, c) = m.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt())
        }
    }
}
