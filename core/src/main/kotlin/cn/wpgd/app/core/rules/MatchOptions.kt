package cn.wpgd.app.core.rules

/**
 * Every match-level rule a match profile may vary (`rules/match-v1.md`, gate G1b). A match profile
 * (`rules/profiles/M*.json`) is loaded with [registry]; base profiles keep [RuleOptions.registry],
 * so adding a match option never changes a base profile's hash.
 *
 * [RuleOption.source] cites the match-v1 row and its source lines; [RuleOption.decision] is the
 * match-v1 section-10 decision the default depends on. A [ChoiceOption] lists only implemented values.
 */
object MatchOptions {
    // Level gains (match-v1 section 2). Sourced: no decision.
    val GainPartnerSecond = IntOption(
        "level.gainPartnerSecond", default = 3, range = 1..12,
        source = "M2.2 [JS17:98, HA17:123, CG22a:197, WG25:207]", decision = null,
    )
    val GainPartnerThird = IntOption(
        "level.gainPartnerThird", default = 2, range = 1..12,
        source = "M2.2 [JS17:98, HA17:123, CG22a:197, WG25:207]", decision = null,
    )
    val GainPartnerFourth = IntOption(
        "level.gainPartnerFourth", default = 1, range = 1..12,
        source = "M2.2 [JS17:98, HA17:123, CG22a:197, WG25:207]", decision = null,
    )

    // Passing A (match-v1 section 3).
    /** D33 (audit A04): on, a team at A passes only in a deal played at level A, never in the other team's deal. */
    val PassingARequireDealAtA = BoolOption(
        "passingA.requireDealAtA", default = true,
        source = "M3.1 [DERIVED from JS17:98, HA17:294, CG22a:198]", decision = Decision.D33,
    )
    /** D34: the smallest gain that passes A. 2 means the partner finished second or third. */
    val PassingAMinGain = IntOption(
        "passingA.minGain", default = 2, range = 1..3,
        source = "M3.2 [JS17:98, CG22a:198, WG25:207, HA17:294; SEU:46 ambiguous]", decision = Decision.D34,
    )
    /** D35 (audit A03): failed attempts allowed before the reset; null means no limit. */
    val PassingAMaxFailures = LimitOption(
        "passingA.maxFailures", default = null, range = 1..12,
        source = "M3.7 [HA17:294 no limit; CUP:14 three; NJMU:18 two]", decision = Decision.D35,
    )
    /** D35: the level a team returns to when it reaches [PassingAMaxFailures]. */
    val PassingAResetLevel = IntOption(
        "passingA.resetLevel", default = 2, range = 2..13,
        source = "M3.8 [CUP:14, NJMU:18]", decision = Decision.D35,
    )
    /** D36 (audit A03): on, an A deal won by the opponents is a failed attempt for the team at A. */
    val PassingAOpponentWinCounts = BoolOption(
        "passingA.opponentWinCounts", default = true,
        source = "M3.5 [CUP:14, NJMU:18; UNSOURCED detail]", decision = Decision.D36,
    )
    /** D37 (audit A03): on, a deal at another level while a team is at A resets that team's failures. */
    val PassingAInterruptionResets = BoolOption(
        "passingA.interruptionResets", default = false,
        source = "M3.6 [UNSOURCED]", decision = Decision.D37,
    )

    // Tribute and return (match-v1 sections 4 and 5).
    /** D25: where a rule names a rank and several cards share it, the player picks the card. */
    val TributeEqualRankChoice = ChoiceOption(
        "tribute.equalRankChoice", default = "player", choices = setOf("player"),
        source = "M4.4, M5.4 [UNSOURCED]", decision = Decision.D25,
    )
    /** D26: routing of a double tribute whose two cards rank equal. */
    val TributeTieRouting = ChoiceOption(
        "tribute.tieRouting", default = "clockwise", choices = setOf("clockwise"),
        source = "M4.7 [JS17:70, CG22a:291, WG25:162, HA17:268, CUP:78; SEU:35, YZU:21, NJMU:12 differ]", decision = Decision.D26,
    )
    /** D28: highest rank a return may have; null means any card. */
    val ReturnMaxRank = LimitOption(
        "returnTribute.maxRank", default = 10, range = 2..14,
        source = "M5.2 [JS17:69, CG22a:289, WG25:157, CUP:68; HA17:265, YZU:21 differ]", decision = Decision.D28,
    )
    /** D29: on, a level card counts at its face rank for the return limit; off, at its R1.5 place above A. */
    val ReturnLevelCardsByFace = BoolOption(
        "returnTribute.levelCardsByFace", default = false,
        source = "M5.3 [DERIVED from JS17:79]", decision = Decision.D29,
    )

    // Resistance (match-v1 section 6).
    /** D30: on, a double-down pair resists when it holds both big jokers between its two hands. */
    val ResistanceSplitBetweenPartners = BoolOption(
        "resistance.splitBetweenPartners", default = true,
        source = "M6.2 [HA17:271, SEU:36, YZU:21, NJMU:12; JS17:71, CG22a:293 silent]", decision = Decision.D30,
    )
    /** D31: "claimed" waits for a giver's Resist command; "automatic" resists as soon as tribute starts. */
    val ResistanceMode = ChoiceOption(
        "resistance.mode", default = "claimed", choices = setOf("claimed", "automatic"),
        source = "M6.3 [JS17:71, SEU:36]", decision = Decision.D31,
    )

    // Opening lead (match-v1 section 7).
    /** D32: who leads the first deal. "cutCard" per JS17:56; "east" per CG22a:248 (duplicate events). */
    val LeadFirstDeal = ChoiceOption(
        "lead.firstDeal", default = "cutCard", choices = setOf("cutCard", "east"),
        source = "M7.1 [JS17:56, CG22a:243, WG25:143; CG22a:248]", decision = Decision.D32,
    )
    /** D27: who leads after a double tribute. */
    val LeadAfterDoubleTribute = ChoiceOption(
        "lead.afterDoubleTribute", default = "giverToFirst", choices = setOf("giverToFirst"),
        source = "M7.3 [JS17:70, CG22a:291, WG25:162, CUP:80; YZU:21, NJMU:12 differ]", decision = Decision.D27,
    )

    // Deal completion (match-v1 section 1).
    /** D38 (audit A36): "endEarly" ends the deal at a double-up and marks the other two places as inferred. */
    val DoubleUpCompletion = ChoiceOption(
        "deal.doubleUpCompletion", default = "endEarly", choices = setOf("endEarly"),
        source = "M1.4, M1.5 [CG22a:266, HA17:108]", decision = Decision.D38,
    )

    // Card-count reporting (match-v1 section 8).
    /** D39: a seat left with this many cards or fewer after a play reports once; null turns reporting off. */
    val ReportingThreshold = LimitOption(
        "reporting.threshold", default = 10, range = 1..26,
        source = "M8.1 [JS17:95, HA17:281, CG22a:301, WG25:203, CUP:86; SEU:27 differs]", decision = Decision.D39,
    )
    /** D40: on, an opponent may ask a seat's card count and gets the exact number. */
    val ReportingAnswerWhenAsked = BoolOption(
        "reporting.answerWhenAsked", default = false,
        source = "M8.4 [JS17:95, HA17:284, CG22a:304; CUP:90, SEU:27 differ]", decision = Decision.D40,
    )
    /** D40: with [ReportingAnswerWhenAsked] on, a seat must answer while it holds this many cards or fewer. */
    val ReportingAskThreshold = IntOption(
        "reporting.askThreshold", default = 10, range = 1..26,
        source = "M8.4 [CUP:90, SEU:27]", decision = Decision.D40,
    )

    val registry = OptionRegistry(
        listOf(
            GainPartnerSecond, GainPartnerThird, GainPartnerFourth,
            PassingARequireDealAtA, PassingAMinGain, PassingAMaxFailures, PassingAResetLevel,
            PassingAOpponentWinCounts, PassingAInterruptionResets,
            TributeEqualRankChoice, TributeTieRouting, ReturnMaxRank, ReturnLevelCardsByFace,
            ResistanceSplitBetweenPartners, ResistanceMode,
            LeadFirstDeal, LeadAfterDoubleTribute,
            DoubleUpCompletion,
            ReportingThreshold, ReportingAnswerWhenAsked, ReportingAskThreshold,
        ),
    )
}
