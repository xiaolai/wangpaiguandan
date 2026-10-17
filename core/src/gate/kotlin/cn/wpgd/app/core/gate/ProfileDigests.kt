package cn.wpgd.app.core.gate

import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleOptions
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleProfileLoader
import java.io.File

/**
 * Release gate helper (`:app:verifyReleaseDistributable`, WI-12). Loads each rule profile file with
 * the same loader and registries the app uses (base rules: [RuleOptions]; match: [MatchOptions]) and
 * writes one tab-separated line per file: file name, id, version, provisional flag and canonical hash,
 * or file name, `ERROR` and why it does not load. The gate binds a signed decision to this exact hash,
 * so a changed option under an unchanged id and version is refused.
 *
 * Usage: `<output file> <profile file>...`. Kept out of `main` so `:core` itself stays free of `java.*`.
 */
fun main(args: Array<String>) {
    // No profile file is a valid input: the gate itself reports an empty profile folder.
    require(args.isNotEmpty()) { "usage: <output file> <profile file>..." }
    val lines = args.drop(1).map(::File).sortedBy { it.name }.map { f -> digestLine(f.name, f.readText()) }
    File(args[0]).apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
}

internal fun digestLine(name: String, text: String): String {
    val results = listOf(RuleOptions.registry, MatchOptions.registry).map { RuleProfileLoader.parse(text, it) }
    val loaded: List<RuleProfile> = results.mapNotNull { it.getOrNull() }
    val fields = when (loaded.size) {
        1 -> loaded.single().let { p -> listOf(p.id, p.version, p.provisional.toString(), p.hash) }
        0 -> listOf("ERROR", results.joinToString("; ") { it.exceptionOrNull()?.message ?: "unknown error" })
        // A profile both registries accept has two hashes; the gate must not pick one silently.
        else -> listOf("ERROR", "loads as both a base and a match profile")
    }
    return (listOf(name) + fields).joinToString("\t") { it.replace(Regex("\\s+"), " ") }
}
