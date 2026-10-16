package cn.wpgd.app.table

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.match.MatchSettings
import cn.wpgd.app.core.match.ResistanceMode
import cn.wpgd.app.core.match.TributeRules
import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.rules.Reading
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleProfileLoader
import cn.wpgd.app.core.rules.RuleSettings
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.topReading
import cn.wpgd.app.core.state.ViewCard

/** The base (rules) and match (tribute, levels) profiles a table is played under. */
class RuleProfiles(val base: RuleProfile, val match: RuleProfile) {
    companion object {
        /** Parses both profile files; fails loudly on any profile error (an app never plays an unreadable profile). */
        fun parse(baseJson: String, matchJson: String): RuleProfiles = RuleProfiles(
            RuleProfileLoader.parse(baseJson).getOrThrow(),
            RuleProfileLoader.parse(matchJson, MatchOptions.registry).getOrThrow(),
        )
    }
}

/**
 * The member-side rule checks the table UI needs, from the same `:core` classifier and tribute
 * rules the host uses (so a pre-check can never disagree with the host). Pure; holds no table state.
 */
class TableRules(val profiles: RuleProfiles) {
    private val settings = RuleSettings.of(profiles.base)
    val matchSettings: MatchSettings = MatchSettings.of(profiles.match)
    private val classifiers = Ranks.NATURAL.associateWith { lazy { Classifier(settings, it) } }

    fun classifier(level: Int): Classifier = classifiers.getValue(level).value

    /** Every reading of [cards] at [level] (each wildcard declaration and type). */
    fun readings(cards: List<Card>, level: Int): List<Reading> = classifier(level).readings(cards)

    /**
     * The combination of the play on top of the trick, read from its stated type and declaration
     * exactly as the host judged it, each play against the one before (R7.7, D23, [topReading]); null when leading. Throws when
     * the public play cannot be read unambiguously, which would mean the host accepted something this
     * client cannot interpret.
     */
    fun topCombination(trick: List<PublicPlay>, level: Int): Combination? {
        if (trick.isEmpty()) return null
        return trick.topReading(classifier(level)) ?: error("the play on the table has no single reading")
    }

    fun tributeCards(hand: List<ViewCard>, level: Int): Set<String> = TributeRules.legalTributeCards(hand.map { it.id }, level).toSet()

    fun returnCards(hand: List<ViewCard>, level: Int): Set<String> = TributeRules.legalReturnCards(hand.map { it.id }, level, matchSettings).toSet()

    companion object {
        fun card(view: ViewCard): Card = card(view.id)

        fun card(id: String): Card = checkNotNull(StandardDeck[id]) { "unknown card id $id" }
    }
}

/**
 * What the table's rules sheet shows (T13): both profiles by id, version and a short hash, whether
 * they are provisional (unsigned), and the match settings a player meets at the table.
 */
data class RulesSummary(
    val baseId: String,
    val baseVersion: String,
    val baseHash: String,
    val matchId: String,
    val matchVersion: String,
    val matchHash: String,
    val provisional: Boolean,
    val maxAFailures: Int?,
    val resistanceClaimed: Boolean,
) {
    companion object {
        private const val HASH_CHARS = 12

        fun of(rules: TableRules): RulesSummary {
            val p = rules.profiles
            return RulesSummary(
                baseId = p.base.id,
                baseVersion = p.base.version,
                baseHash = p.base.hash.take(HASH_CHARS),
                matchId = p.match.id,
                matchVersion = p.match.version,
                matchHash = p.match.hash.take(HASH_CHARS),
                provisional = p.base.provisional || p.match.provisional,
                maxAFailures = rules.matchSettings.maxAFailures,
                resistanceClaimed = rules.matchSettings.resistanceMode == ResistanceMode.CLAIMED,
            )
        }
    }
}
