package cn.wpgd.app.release

import cn.wpgd.app.table.RuleProfiles

/** Development builds may carry provisional rule profiles; release builds may not (plan section 3). */
enum class BuildMode {
    DEVELOPMENT,
    RELEASE,
    ;

    companion object {
        /** From the build's `BuildConfig.RELEASE_GATE`, which only the `release` build type sets. */
        fun of(releaseGate: Boolean): BuildMode = if (releaseGate) RELEASE else DEVELOPMENT
    }
}

/** A release build was started with a rule profile that gates G1a/G1b have not signed. */
class UnsignedRuleProfileException(message: String) : IllegalStateException(message)

/**
 * WI-12 release gate (plan section 3, G1a/G1b): a release build refuses to start while the rule
 * profiles it carries are provisional (unsigned). Called first thing in `WpgdApplication.onCreate`,
 * so a release built from provisional profiles crashes at launch with this message instead of
 * playing unsigned rules. A development build plays them (and shows them as provisional), and does
 * not load the profiles to decide. The build-time half is Gradle's `verifyReleaseDistributable`,
 * which refuses to sign such a release in the first place.
 */
object ReleaseGate {
    fun check(mode: BuildMode, profiles: () -> RuleProfiles) {
        if (mode != BuildMode.RELEASE) return
        val p = profiles()
        val unsigned = listOf("G1a" to p.base, "G1b" to p.match).filter { it.second.provisional }
        if (unsigned.isNotEmpty()) {
            throw UnsignedRuleProfileException(
                "release build refuses to start: unsigned rule profile " +
                    unsigned.joinToString { (gate, profile) -> "${profile.id} ${profile.version} (gate $gate)" } +
                    ". A release needs profiles signed by the owner and a rules reviewer; use a debug build for development.",
            )
        }
    }
}
