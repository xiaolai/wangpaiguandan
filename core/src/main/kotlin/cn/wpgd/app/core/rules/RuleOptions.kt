package cn.wpgd.app.core.rules

/** A fixed set of known options. Construction fails fast on a duplicate or malformed id. */
class OptionRegistry(options: List<RuleOption<*>>) {
    /** A snapshot of the constructor's list, so the caller cannot change the registry afterwards. */
    val all: List<RuleOption<*>> = options.toList()

    init {
        val ids = all.map { it.id }
        require(ids.size == ids.toSet().size) { "Duplicate option ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        all.forEach { require(ID.matches(it.id)) { "Malformed option id: ${it.id}" } }
        all.forEach { require(it.source.isNotBlank()) { "Option ${it.id} must cite its base-v1 source" } }
    }

    private val byId: Map<String, RuleOption<*>> = all.associateBy { it.id }

    /** Options that are not retired. Their effective values define a profile's rules. */
    val active: List<RuleOption<*>> = all.filter { it.deprecatedSince == null }

    operator fun get(id: String): RuleOption<*>? = byId[id]

    private companion object {
        val ID = Regex("[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)+")
    }
}

/**
 * Every rule that a profile may vary. To add an option: add one entry here and list it in [registry].
 * To retire one: set `deprecatedSince`; delete the entry only when no supported profile sets it.
 * Defaults are the base-v1 draft defaults (section 9); [RuleOption.source] cites the row.
 */
object RuleOptions {
    val BombMinSize = IntOption(
        "bomb.minSize", default = 4, range = 4..10,
        source = "R4.1 [E33, E34, E61]", decision = null,
    )
    val BombMaxSize = LimitOption(
        "bomb.maxSize", default = 10, range = 4..10,
        source = "R4.4, R4.5, R4.6", decision = Decision.D2, pending = true,
    )
    /**
     * D1 variant switch. Off (the default) conforms to R3.3 and R3.4: no run passes through A into 2.
     * On, it deliberately overrides sourced R3.3 as well as the R3.4 default: J-Q-K-A-2, Q-K-A-2-3,
     * K-A-2-3-4 and KK-AA-22 become legal at every level, including level 2.
     */
    val SequenceWrapAroundA = BoolOption(
        "sequence.wrapAroundA", default = false,
        source = "R3.4 [D1]; when on, overrides sourced R3.3 [E28, E29]", decision = Decision.D1,
    )
    val SequenceJokersAllowed = BoolOption(
        "sequence.jokersAllowed", default = false, source = "R2.11", decision = Decision.D4,
    )
    val JokerBigSmallIsPair = BoolOption(
        "joker.bigSmallIsPair", default = false, source = "R2.3, R6.4", decision = Decision.D3,
    )
    val JokerPairInTripleWithPair = BoolOption(
        "joker.pairInTripleWithPair", default = true, source = "R2.7, R6.4", decision = Decision.D3,
    )
    val TripleWithPairSameRank = BoolOption(
        "tripleWithPair.sameRankPair", default = false, source = "R2.6", decision = Decision.D8,
    )
    val StraightFlushPlayableAsStraight = BoolOption(
        "straightFlush.playableAsStraight", default = false, source = "R5.5", decision = Decision.D9,
    )
    val WildcardFlushRunAsStraight = BoolOption(
        "wildcard.flushRunAsStraight", default = true, source = "R7.11", decision = Decision.D9,
    )
    val WildcardAlwaysDeclared = BoolOption(
        "wildcard.alwaysDeclared", default = true, source = "R7.8, R7.10", decision = Decision.D6,
    )
    val WildcardAloneMayStandIn = BoolOption(
        "wildcard.aloneMayStandIn", default = false, source = "R7.9", decision = Decision.D7,
    )

    /** D5: on, an A-low run ranks by its top non-A card (A-2-3-4-5 is 5-high); off, it ranks as A-high. */
    val SequenceAceLowRanksLowest = BoolOption(
        "sequence.aceLowRanksLowest", default = true, source = "R2.16, R5.4 [E24]", decision = Decision.D5,
    )
    /** D12: on, a wrap run (`sequence.wrapAroundA`) ranks by its last card (K-A-2-3-4 is 4-high); off, as A-high. */
    val SequenceWrapRanksByLastCard = BoolOption(
        "sequence.wrapRanksByLastCard", default = true, source = "R3.4 [D1]", decision = Decision.D12,
    )
    /** D13: with `sequence.jokersAllowed`, jokers follow A (small, then big); on, a run may go on from the big joker into 2. */
    val SequenceJokersWrap = BoolOption(
        "sequence.jokersWrap", default = false, source = "R2.11 [D4]", decision = Decision.D13,
    )
    /** D14: with `joker.bigSmallIsPair`, a big+small pair ranks as a small-joker pair when on, as a big-joker pair when off. */
    val JokerBigSmallPairRanksAsSmall = BoolOption(
        "joker.bigSmallPairRanksAsSmall", default = true, source = "R2.3, R6.4 [D3]", decision = Decision.D14,
    )
    /** D15: with `wildcard.alwaysDeclared` off, a play with more than one reading still needs a declaration when on. */
    val WildcardAmbiguousNeedsDeclaration = BoolOption(
        "wildcard.ambiguousNeedsDeclaration", default = true, source = "R7.7, R7.8 [D6]", decision = Decision.D15,
    )
    /** D16: on, a declared suit outside a straight flush rejects the play; off, the suit is ignored. */
    val WildcardStraySuitRejected = BoolOption(
        "wildcard.straySuitRejected", default = true, source = "R7.10 [D6]", decision = Decision.D16,
    )

    val registry = OptionRegistry(
        listOf(
            BombMinSize, BombMaxSize, SequenceWrapAroundA, SequenceJokersAllowed,
            JokerBigSmallIsPair, JokerPairInTripleWithPair, TripleWithPairSameRank,
            StraightFlushPlayableAsStraight, WildcardFlushRunAsStraight,
            WildcardAlwaysDeclared, WildcardAloneMayStandIn,
            SequenceAceLowRanksLowest, SequenceWrapRanksByLastCard, SequenceJokersWrap,
            JokerBigSmallPairRanksAsSmall, WildcardAmbiguousNeedsDeclaration, WildcardStraySuitRejected,
        ),
    )
}
