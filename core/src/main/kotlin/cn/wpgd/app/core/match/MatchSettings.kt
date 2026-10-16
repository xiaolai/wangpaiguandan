package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.state.Seat
import kotlinx.serialization.Serializable

/** The two partnerships (match-v1 M1.1): partners sit opposite. */
@Serializable
enum class Team {
    NORTH_SOUTH, EAST_WEST;

    val other: Team get() = if (this == NORTH_SOUTH) EAST_WEST else NORTH_SOUTH

    /** The team's two seats, in play order. */
    val seats: List<Seat> get() = Seat.entries.filter { it.team == this }
}

val Seat.team: Team get() = if (this == Seat.SOUTH || this == Seat.NORTH) Team.NORTH_SOUTH else Team.EAST_WEST

/** 上家: the opponent on this seat's left, who plays just before it (JS17:27). Clockwise from this seat. */
val Seat.upper: Seat get() = Seat.entries.first { it.next == this }

enum class ResistanceMode { CLAIMED, AUTOMATIC }

enum class FirstDealLead { CUT_CARD, EAST }

/**
 * The effective value of every match option, read once from a match profile. Match code reads
 * options only from here. Options that list a single implemented value are checked, not read.
 */
@ConsistentCopyVisibility
data class MatchSettings private constructor(
    val gainPartnerSecond: Int,
    val gainPartnerThird: Int,
    val gainPartnerFourth: Int,
    val requireDealAtA: Boolean,
    val minGainToPassA: Int,
    /** Null: no limit (match-v1 D35). */
    val maxAFailures: Int?,
    val resetLevel: Int,
    val opponentWinCounts: Boolean,
    val interruptionResets: Boolean,
    /** Null: any card may be returned (match-v1 D28). */
    val returnMaxRank: Int?,
    val returnLevelCardsByFace: Boolean,
    val resistanceSplitBetweenPartners: Boolean,
    val resistanceMode: ResistanceMode,
    val firstDealLead: FirstDealLead,
    /** The `deal.doubleUpCompletion` value, recorded in every settlement as its policy label (A36). */
    val completionPolicy: String,
    /** Null: reporting is off. */
    val reportingThreshold: Int?,
    val answerWhenAsked: Boolean,
    val askThreshold: Int,
) {
    fun gainFor(partnerPlace: Int): Int = when (partnerPlace) {
        2 -> gainPartnerSecond
        3 -> gainPartnerThird
        4 -> gainPartnerFourth
        else -> error("partner place must be 2..4, got $partnerPlace")
    }

    companion object {
        fun of(profile: RuleProfile): MatchSettings {
            require(profile.registry === MatchOptions.registry) { "profile ${profile.id} is not a match profile" }
            check(profile[MatchOptions.TributeEqualRankChoice] == "player")
            check(profile[MatchOptions.TributeTieRouting] == "clockwise")
            check(profile[MatchOptions.LeadAfterDoubleTribute] == "giverToFirst")
            val completion = profile[MatchOptions.DoubleUpCompletion]
            check(completion == "endEarly")
            return MatchSettings(
                gainPartnerSecond = profile[MatchOptions.GainPartnerSecond],
                gainPartnerThird = profile[MatchOptions.GainPartnerThird],
                gainPartnerFourth = profile[MatchOptions.GainPartnerFourth],
                requireDealAtA = profile[MatchOptions.PassingARequireDealAtA],
                minGainToPassA = profile[MatchOptions.PassingAMinGain],
                maxAFailures = profile[MatchOptions.PassingAMaxFailures],
                resetLevel = profile[MatchOptions.PassingAResetLevel],
                opponentWinCounts = profile[MatchOptions.PassingAOpponentWinCounts],
                interruptionResets = profile[MatchOptions.PassingAInterruptionResets],
                returnMaxRank = profile[MatchOptions.ReturnMaxRank],
                returnLevelCardsByFace = profile[MatchOptions.ReturnLevelCardsByFace],
                resistanceSplitBetweenPartners = profile[MatchOptions.ResistanceSplitBetweenPartners],
                resistanceMode = when (val m = profile[MatchOptions.ResistanceMode]) {
                    "claimed" -> ResistanceMode.CLAIMED
                    "automatic" -> ResistanceMode.AUTOMATIC
                    else -> error("unimplemented resistance.mode $m")
                },
                firstDealLead = when (val f = profile[MatchOptions.LeadFirstDeal]) {
                    "cutCard" -> FirstDealLead.CUT_CARD
                    "east" -> FirstDealLead.EAST
                    else -> error("unimplemented lead.firstDeal $f")
                },
                completionPolicy = completion,
                reportingThreshold = profile[MatchOptions.ReportingThreshold],
                answerWhenAsked = profile[MatchOptions.ReportingAnswerWhenAsked],
                askThreshold = profile[MatchOptions.ReportingAskThreshold],
            )
        }
    }
}
