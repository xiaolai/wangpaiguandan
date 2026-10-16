package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.nextInt
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * GDN-058, section 8 P2: a policy's input is the seat's own projection plus its own legal options,
 * by type; changing hidden opponent cards while the observation stays the same leaves the policy's
 * input and output byte-identical.
 */
class InformationBoundaryTest {
    /** Host-internal types that hold another seat's cards. None may be reachable from a policy's arguments. */
    private val forbidden = setOf(DealState::class.java, MatchState::class.java, cn.wpgd.app.core.match.TributeState::class.java, cn.wpgd.app.core.state.Trick::class.java)

    @Test fun policySignatureTakesOnlyTheObservationAndOwnChoices() {
        val decide = AiPolicy::class.java.declaredMethods.single { it.name == "decide" }
        assertEquals(listOf(PolicyInput::class.java, DecisionContext::class.java), decide.parameterTypes.toList())
        val inputFields = instanceFields(PolicyInput::class.java).map { it.type }.toSet()
        assertTrue(inputFields.containsAll(setOf(cn.wpgd.app.core.match.MatchMemberView::class.java, SeatChoices::class.java)), "fields: $inputFields")
        val reached = reachableTypes(listOf(PolicyInput::class.java, DecisionContext::class.java))
        val leaks = reached.filter { it in forbidden }
        assertEquals(emptyList(), leaks, "a policy argument reaches host state")
        // Sanity: the walk does see deep types (so an empty result means something).
        assertTrue(cn.wpgd.app.core.state.ViewCard::class.java in reached && cn.wpgd.app.core.rules.Combination::class.java in reached, "walk too shallow: $reached")
        // The walker would catch a leak: the authoritative state reaches a deal.
        assertTrue(DealState::class.java in reachableTypes(listOf(MatchState::class.java)))
    }

    @Test fun optionsNamingAnotherSeatsCardAreRefused() {
        val state = playing(2, mapOf(S to listOf("0S3", "0S4"), E to listOf("0S5"), N to listOf("0S6"), W to listOf("0S7")), turn = S)
        val observation = AiProfiles.projector.aiObservation(state, S)
        val eastPlays = AiProfiles.reducer.dealReducer.legalActions(state.deal!!.copy(turn = E), E)
        val refused = runCatching { PolicyInput.of(observation, eastPlays, false, emptyList(), emptyList(), false) }
        assertTrue(refused.isFailure, "a play of EAST's cards was accepted as SOUTH's option")
        assertTrue(runCatching { PolicyInput.of(observation, emptyList(), false, listOf("0S5"), emptyList(), false) }.isFailure)
    }

    /**
     * P2 over seeded matches: at every AI decision (lead, follow, tribute, return) the hidden cards of
     * the other three seats are reshuffled among them (same counts). The observation bytes, the choice
     * set, and the decision of each policy must not change.
     */
    @Test fun hiddenCardsDoNotChangeInputOrOutput() {
        val reducer = AiProfiles.reducer
        var checked = 0
        val phases = HashSet<MatchPhase>()
        for (seed in 1L..30L) {
            var state = reducer.newMatch()
            val choosers = Seat.entries.associateWith { aiChooser(seed) }
            val stats = RunStats()
            var guard = 0
            while (state.phase != MatchPhase.OVER && state.dealNumber <= 2 && guard++ < 2_000) {
                val acting = actingSeats(state).firstOrNull()
                if (acting != null && guard % 3 == 0) {
                    val variant = reshuffleHidden(state, acting, SplitMix64(seed * 1_000 + guard))
                    if (variant != null) {
                        assertSameForSeat(state, variant, acting, seed)
                        checked++
                        phases += state.phase
                    }
                }
                state = step(reducer, state, { SplitMix64(seed * 7 + it) }, choosers, stats)
            }
        }
        println("INFORMATION_BOUNDARY decisions_checked=$checked phases=$phases")
        assertTrue(checked >= 300, "checked only $checked decisions")
        assertTrue(phases.containsAll(listOf(MatchPhase.PLAYING, MatchPhase.TRIBUTE, MatchPhase.RETURN)), "covered $phases")
    }

    private fun assertSameForSeat(a: MatchState, b: MatchState, seat: Seat, seed: Long) {
        val ia = hostInput(a, seat)
        val ib = hostInput(b, seat)
        val where = "seed $seed r${a.revision} $seat ${a.phase}"
        assertTrue(MatchProjectionCodec.encode(ia.observation).contentEquals(MatchProjectionCodec.encode(ib.observation)), "$where: observation bytes differ")
        assertEquals(ia.choices.canonical(), ib.choices.canonical(), "$where: choices differ")
        for (policy in listOf(BaselineHeuristicPolicy, RandomLegalPolicy)) for (style in AiStyle.entries) {
            val oa = DecisionRunner.decide(policy, ia, SplitMix64(seed), style)
            val ob = DecisionRunner.decide(policy, ib, SplitMix64(seed), style)
            assertEquals(oa.action.canonical(), ob.action.canonical(), "$where: ${policy.id} $style decision differs")
            assertEquals(oa.steps, ob.steps, "$where: work differs")
        }
    }

    /** The state with the other seats' unplayed cards dealt again among them (same counts); null if nothing changes. */
    private fun reshuffleHidden(state: MatchState, seat: Seat, random: SplitMix64): MatchState? {
        val deal = state.deal ?: return null
        val others = Seat.entries.filter { it != seat && deal.hands.getValue(it).isNotEmpty() }
        if (others.size < 2) return null
        val pool = others.flatMap { deal.hands.getValue(it) }.toMutableList()
        for (i in pool.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val t = pool[i]; pool[i] = pool[j]; pool[j] = t
        }
        var k = 0
        val hands = deal.hands.toMutableMap()
        for (o in others) {
            val n = deal.hands.getValue(o).size
            hands[o] = pool.subList(k, k + n).toSet()
            k += n
        }
        if (hands == deal.hands) return null
        val variant = state.copy(deal = deal.copy(hands = hands))
        check(MatchStateCodec.hash(variant) != MatchStateCodec.hash(state)) { "variant must differ in authoritative state" }
        return variant
    }

    private fun instanceFields(c: Class<*>): List<Field> =
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.toList()

    /** Every project type reachable through instance fields (including generic arguments) of [roots]. */
    private fun reachableTypes(roots: List<Class<*>>): Set<Class<*>> {
        val seen = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque(roots)
        fun enqueue(t: Type) {
            when (t) {
                is Class<*> -> if (t.isArray) enqueue(t.componentType) else if (t.name.startsWith("cn.wpgd.")) queue += t
                is ParameterizedType -> { enqueue(t.rawType); t.actualTypeArguments.forEach(::enqueue) }
                is java.lang.reflect.WildcardType -> (t.upperBounds + t.lowerBounds).forEach(::enqueue)
                else -> Unit
            }
        }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            for (f in instanceFields(c)) enqueue(f.genericType)
            // Sealed hierarchies: follow the subclasses too (AiAction.Play holds a LegalAction).
            c.permittedSubclasses?.forEach { enqueue(it) }
            c.declaredClasses.filter { c.isAssignableFrom(it) }.forEach { enqueue(it) }
        }
        if (seen.isEmpty()) fail("nothing reached")
        return seen
    }
}
