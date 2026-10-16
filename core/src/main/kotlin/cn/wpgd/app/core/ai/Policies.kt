package cn.wpgd.app.core.ai

import cn.wpgd.app.core.state.nextInt

/**
 * Chooses uniformly among every legal action (passing counts as one), using only the seeded
 * tie-break source. The opponent of the paired evaluation (GDN-064); not a playing strength.
 */
object RandomLegalPolicy : AiPolicy {
    override val id = PolicyId("random-legal", "1.0.0")

    override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
        context.spend()
        val all = input.choices.all
        return all[context.tieBreak.nextInt(all.size)]
    }
}

/** The policies a seat may be set to, by [PolicyId]. Rolling back is choosing the previous version (WI-07 rollback). */
object AiPolicies {
    val BASELINE: PolicyId = BaselineHeuristicPolicy.id

    private val byId: Map<PolicyId, AiPolicy> = listOf(BaselineHeuristicPolicy, RandomLegalPolicy).associateBy { it.id }

    val ids: Set<PolicyId> get() = byId.keys

    /** The policy with [id]. Fails loudly on an unknown id: a seat never silently plays another policy. */
    operator fun get(id: PolicyId): AiPolicy = byId[id] ?: throw IllegalArgumentException("unknown AI policy $id")

    fun isKnown(id: PolicyId): Boolean = id in byId

    /** The settings of a seat nobody has configured: the baseline at its default style. */
    val DEFAULT_SETTINGS = AiSettings(BASELINE)
}
