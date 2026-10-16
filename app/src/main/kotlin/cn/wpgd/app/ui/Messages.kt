package cn.wpgd.app.ui

import androidx.annotation.StringRes
import cn.wpgd.app.R

/**
 * The table logic speaks in message keys (`turn.you_lead`, `table.reject.not_your_turn` …); each
 * key has the string resource of the same name with dots as underscores. `MessageResourcesTest`
 * checks that every key the logic can emit is here and in `strings.xml`.
 */
object Messages {
    private val byKey: Map<String, Int> = mapOf(
        "cue.deal_started" to R.string.cue_deal_started,
        "cue.tribute_started" to R.string.cue_tribute_started,
        "cue.play" to R.string.cue_play,
        "cue.bomb" to R.string.cue_bomb,
        "cue.pass" to R.string.cue_pass,
        "cue.your_turn" to R.string.cue_your_turn,
        "cue.seat_finished" to R.string.cue_seat_finished,
        "cue.count_reported" to R.string.cue_count_reported,
        "cue.deal_settled" to R.string.cue_deal_settled,
        "cue.match_over" to R.string.cue_match_over,
        "cue.command_rejected" to R.string.cue_command_rejected,
        "table.preview.not_a_combination" to R.string.table_preview_not_a_combination,
        "table.preview.does_not_beat" to R.string.table_preview_does_not_beat,
        "table.reject.table_moved_on" to R.string.table_reject_table_moved_on,
        "table.reject.not_your_turn" to R.string.table_reject_not_your_turn,
        "table.reject.already_finished" to R.string.table_reject_already_finished,
        "table.reject.wrong_phase" to R.string.table_reject_wrong_phase,
        "table.reject.nothing_selected" to R.string.table_reject_nothing_selected,
        "table.reject.cards_not_in_hand" to R.string.table_reject_cards_not_in_hand,
        "table.reject.not_a_combination" to R.string.table_reject_not_a_combination,
        "table.reject.does_not_beat" to R.string.table_reject_does_not_beat,
        "table.reject.leader_must_play" to R.string.table_reject_leader_must_play,
        "table.reject.choose_a_reading" to R.string.table_reject_choose_a_reading,
        "table.reject.tribute_card_not_allowed" to R.string.table_reject_tribute_card_not_allowed,
        "table.reject.return_card_not_allowed" to R.string.table_reject_return_card_not_allowed,
        "table.reject.resistance_not_available" to R.string.table_reject_resistance_not_available,
        "table.reject.asking_not_allowed" to R.string.table_reject_asking_not_allowed,
        "table.reject.room_paused" to R.string.table_reject_room_paused,
        "table.reject.room_quarantined" to R.string.table_reject_room_quarantined,
        "table.reject.not_connected" to R.string.table_reject_not_connected,
        "table.reject.outcome_unknown" to R.string.table_reject_outcome_unknown,
        "table.reject.refused_by_host" to R.string.table_reject_refused_by_host,
        "table.reject.settings_changed" to R.string.table_reject_settings_changed,
        "turn.no_deal" to R.string.turn_no_deal,
        "turn.you_lead" to R.string.turn_you_lead,
        "turn.you_beat_or_pass" to R.string.turn_you_beat_or_pass,
        "turn.other_leads" to R.string.turn_other_leads,
        "turn.other_follows" to R.string.turn_other_follows,
        "turn.you_give_tribute" to R.string.turn_you_give_tribute,
        "turn.you_return_tribute" to R.string.turn_you_return_tribute,
        "turn.waiting_for_tribute" to R.string.turn_waiting_for_tribute,
        "turn.waiting_for_return" to R.string.turn_waiting_for_return,
        "turn.between_steps" to R.string.turn_between_steps,
        "turn.deal_over" to R.string.turn_deal_over,
        "turn.match_over" to R.string.turn_match_over,
        "result.own_team_won_deal" to R.string.result_own_team_won_deal,
        "result.own_team_lost_deal" to R.string.result_own_team_lost_deal,
        "result.own_team_won_match" to R.string.result_own_team_won_match,
        "result.own_team_lost_match" to R.string.result_own_team_lost_match,
        "result.team_won_deal" to R.string.result_team_won_deal,
        "result.team_won_match" to R.string.result_team_won_match,
        "result.placing" to R.string.result_placing,
        "result.places_inferred" to R.string.result_places_inferred,
        "result.level_change" to R.string.result_level_change,
        "result.a_passed" to R.string.result_a_passed,
        "result.a_failed" to R.string.result_a_failed,
        "result.a_reset" to R.string.result_a_reset,
        "result.a_interrupted" to R.string.result_a_interrupted,
        "result.tribute_next" to R.string.result_tribute_next,
    )

    @StringRes
    fun resId(key: String): Int? = byKey[key]

    /** The resource for [key]; an unknown key is a programming error and fails loudly. */
    @StringRes
    fun of(key: String): Int = checkNotNull(byKey[key]) { "no string resource for message key $key" }
}
