package cn.wpgd.app.room

import cn.wpgd.app.core.match.ResistanceMode
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.RoomMode
import cn.wpgd.app.session.wire.RoomSettingsView
import cn.wpgd.app.table.RulesSummary
import cn.wpgd.app.table.TableRules

/**
 * What every player sees before saying ready (D1, GDN-007): the frozen profiles (ids, versions,
 * hashes, provisional mark), the tribute and return rules, resistance, the passing-A condition and
 * failure limit, card-count reporting, and this room's assistance and absence settings. Everything
 * comes from the profile the host's hello bound the room to, and from the room settings the host
 * sent, so no player readies for rules it has not seen.
 */
data class RulesSheet(
    val summary: RulesSummary,
    /** Return limit: the highest rank a return may have; null when any card may be returned. */
    val returnMaxRank: Int?,
    val resistanceClaimed: Boolean,
    val resistanceSplitBetweenPartners: Boolean,
    /** Passing A needs a gain of at least this (3: a double win, 双上). */
    val minGainToPassA: Int,
    val maxAFailures: Int?,
    val resetLevel: Int,
    /** Card counts are reported at this many cards or fewer; null when reporting is off. */
    val reportingThreshold: Int?,
    val mode: RoomMode,
    val hints: Boolean,
    val privateFollow: Boolean,
    val graceSeconds: Int,
    val absence: AbsenceMode,
    val settingsRevision: Long,
) {
    companion object {
        fun of(rules: TableRules, settings: RoomSettingsView, settingsRevision: Long): RulesSheet {
            val m = rules.matchSettings
            return RulesSheet(
                summary = RulesSummary.of(rules),
                returnMaxRank = m.returnMaxRank,
                resistanceClaimed = m.resistanceMode == ResistanceMode.CLAIMED,
                resistanceSplitBetweenPartners = m.resistanceSplitBetweenPartners,
                minGainToPassA = m.minGainToPassA,
                maxAFailures = m.maxAFailures,
                resetLevel = m.resetLevel,
                reportingThreshold = m.reportingThreshold,
                mode = settings.policy.mode,
                hints = settings.policy.hints,
                privateFollow = settings.policy.privateFollow,
                graceSeconds = (settings.graceMs / 1_000).toInt(),
                absence = settings.afterGrace,
                settingsRevision = settingsRevision,
            )
        }
    }
}
