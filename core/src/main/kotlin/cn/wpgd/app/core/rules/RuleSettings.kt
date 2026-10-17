package cn.wpgd.app.core.rules

/**
 * The effective value of every registered option, read once from a [RuleProfile] (profile
 * setting, else registry default). The classifier and generator read options only from here,
 * so no option value is hard-coded in engine code.
 */
@ConsistentCopyVisibility
data class RuleSettings private constructor(
    val bombMinSize: Int,
    /** Null means no limit (the card set itself caps a bomb at 10, R4.6). */
    val bombMaxSize: Int?,
    val sequenceWrapAroundA: Boolean,
    val sequenceJokersAllowed: Boolean,
    val jokerBigSmallIsPair: Boolean,
    val jokerPairInTripleWithPair: Boolean,
    val tripleWithPairSameRankPair: Boolean,
    val straightFlushPlayableAsStraight: Boolean,
    val wildcardFlushRunAsStraight: Boolean,
    val wildcardAlwaysDeclared: Boolean,
    val wildcardAloneMayStandIn: Boolean,
    val sequenceAceLowRanksLowest: Boolean,
    val sequenceWrapRanksByLastCard: Boolean,
    val sequenceJokersWrap: Boolean,
    val jokerBigSmallPairRanksAsSmall: Boolean,
    val wildcardAmbiguousNeedsDeclaration: Boolean,
    val wildcardStraySuitRejected: Boolean,
) {
    fun allowsBombSize(size: Int): Boolean = size >= bombMinSize && (bombMaxSize == null || size <= bombMaxSize)

    companion object {
        fun of(profile: RuleProfile): RuleSettings = RuleSettings(
            bombMinSize = profile[RuleOptions.BombMinSize],
            bombMaxSize = profile[RuleOptions.BombMaxSize],
            sequenceWrapAroundA = profile[RuleOptions.SequenceWrapAroundA],
            sequenceJokersAllowed = profile[RuleOptions.SequenceJokersAllowed],
            jokerBigSmallIsPair = profile[RuleOptions.JokerBigSmallIsPair],
            jokerPairInTripleWithPair = profile[RuleOptions.JokerPairInTripleWithPair],
            tripleWithPairSameRankPair = profile[RuleOptions.TripleWithPairSameRank],
            straightFlushPlayableAsStraight = profile[RuleOptions.StraightFlushPlayableAsStraight],
            wildcardFlushRunAsStraight = profile[RuleOptions.WildcardFlushRunAsStraight],
            wildcardAlwaysDeclared = profile[RuleOptions.WildcardAlwaysDeclared],
            wildcardAloneMayStandIn = profile[RuleOptions.WildcardAloneMayStandIn],
            sequenceAceLowRanksLowest = profile[RuleOptions.SequenceAceLowRanksLowest],
            sequenceWrapRanksByLastCard = profile[RuleOptions.SequenceWrapRanksByLastCard],
            sequenceJokersWrap = profile[RuleOptions.SequenceJokersWrap],
            jokerBigSmallPairRanksAsSmall = profile[RuleOptions.JokerBigSmallPairRanksAsSmall],
            wildcardAmbiguousNeedsDeclaration = profile[RuleOptions.WildcardAmbiguousNeedsDeclaration],
            wildcardStraySuitRejected = profile[RuleOptions.WildcardStraySuitRejected],
        )
    }
}
