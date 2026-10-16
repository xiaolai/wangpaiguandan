package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the golden set (core/src/test/resources/fixtures/rules/golden-v1.json, written by tools/golden/build.py) against P1. The golden set is the oracle: a disagreement is a
 * test failure listed by case id, never an edit of the case.
 * Runs every `sourced` case, and every `default-dependent` case whose decisions P1 leaves at the
 * registry default. Cases with no expected result (`unsourced`) are skipped with their reason.
 */
class GoldenCaseTest {
    private val cases: List<JsonObject> by lazy {
        val text = checkNotNull(GoldenCaseTest::class.java.getResource(GOLDEN)) { "golden set not on the test classpath at $GOLDEN" }.readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    @Test fun goldenSetUnderP1() {
        val p1 = Profiles.p1
        val skipped = LinkedHashMap<String, String>()
        val failures = LinkedHashMap<String, String>()
        var run = 0
        for (case in cases) {
            val id = case.str("id")
            val reason = skipReason(case, p1)
            if (reason != null) { skipped[id] = reason; continue }
            run++
            val problem = try {
                when (case.str("kind")) {
                    "play" -> checkPlay(case)
                    "generator" -> checkGenerator(case)
                    "profile" -> checkProfile(case)
                    else -> "unknown kind ${case.str("kind")}"
                }
            } catch (e: Throwable) {
                "threw $e"
            }
            if (problem != null) failures[id] = problem
        }
        println("GOLDEN total=${cases.size} run=$run passed=${run - failures.size} failed=${failures.size} skipped=${skipped.size}")
        skipped.forEach { (id, r) -> println("GOLDEN SKIP $id: $r") }
        failures.forEach { (id, p) -> println("GOLDEN FAIL $id: $p") }
        assertEquals(cases.size, run + skipped.size)
        assertTrue(run >= 151, "expected to run at least 151 cases, ran $run")
        assertTrue(failures.isEmpty(), "golden failures:\n" + failures.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }

    /** Null when the case runs under [p1]; otherwise why not. */
    private fun skipReason(case: JsonObject, p1: RuleProfile): String? {
        if (case["expected"] is JsonNull) return "status ${case.str("status")}: the case has no expected result (${case.str("needs")})"
        return when (case.str("status")) {
            "sourced" -> null
            "default-dependent" -> {
                val decisions = case.getValue("decisions").jsonArray.map { Decision.valueOf(it.jsonPrimitive.content) }
                val deviating = RuleOptions.registry.active.filter { it.decision in decisions && p1[it] != it.default }
                if (deviating.isEmpty()) null else "P1 deviates from the default of ${deviating.map { it.id }}"
            }
            else -> "status ${case.str("status")}"
        }
    }

    // ---------------------------------------------------------------- play cases

    private fun checkPlay(case: JsonObject): String? {
        val setup = case.obj("setup")
        val expected = case.obj("expected")
        val level = Notation.rank(setup.str("level"))
        val classifier = Classifier(RuleSettings.of(Profiles.p1), level)

        val table = setup["table"].takeUnless { it is JsonNull }?.jsonObject?.let { t ->
            val want = t.obj("classification")
            val j = classifier.judge(parseCards(t), parseDeclaration(t["declaration"]), null)
            (j as? Judgment.Readable)?.readings?.firstOrNull { matches(it, want) }
                ?: return "table ${t["cards"]} does not read as $want: $j"
        }
        val play = setup.obj("play")
        val cards = parseCards(play)
        val judgment = classifier.judge(cards, parseDeclaration(play["declaration"]), table)
        val wantLegal = expected.getValue("legal").jsonPrimitive.boolean
        val wantClass = expected["classification"].takeUnless { it is JsonNull }?.jsonObject

        if (wantClass == null) {
            val got = (judgment as? Judgment.Rejected)?.reason?.code
            val wantReason = expected["rejected_reason"].takeUnless { it is JsonNull }?.jsonPrimitive?.content
            if (wantLegal) return "golden says legal without a classification"
            return if (got == wantReason) null else "expected rejection '$wantReason', got $judgment"
        }
        if (judgment !is Judgment.Readable) return "expected classification $wantClass, got $judgment"
        val pool = if (wantLegal) judgment.beating else judgment.readings
        val match = pool.firstOrNull { matches(it, wantClass) }
            ?: return "expected ${if (wantLegal) "a legal" else "a"} reading $wantClass; readings=${judgment.readings.map { describe(it) }} beating=${judgment.beating.map { describe(it) }}"
        if (judgment.legal != wantLegal) return "expected legal=$wantLegal, got ${judgment.legal} (beating=${judgment.beating.map { describe(it) }})"

        val wantBombSize = expected["bomb_size"].takeUnless { it is JsonNull }?.jsonPrimitive?.int
        if (match.bombSize != wantBombSize) return "expected bomb_size $wantBombSize, got ${match.bombSize}"
        val wantSf = expected.getValue("straight_flush").jsonPrimitive.boolean
        if ((match.type == CombinationType.STRAIGHT_FLUSH) != wantSf) return "expected straight_flush=$wantSf"
        val wantBeats = expected["beats_table"].takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
        val gotBeats = table?.let { match.beats(it) }
        if (gotBeats != wantBeats) return "expected beats_table=$wantBeats, got $gotBeats"
        if (expected.getValue("exclusive").jsonPrimitive.boolean) {
            val all = classifier.readings(cards).map { it.combination }.distinct()
            if (all != listOf(match)) return "expected exclusive reading, classifier offers ${all.map { describe(it) }}"
        }
        return null
    }

    // ---------------------------------------------------------------- generator cases

    private fun checkGenerator(case: JsonObject): String? {
        val setup = case.obj("setup")
        val expected = case.obj("expected")
        val level = Notation.rank(setup.str("level"))
        val generator = LegalActionGenerator(RuleSettings.of(Profiles.p1), level)
        val core = setup.getValue("hand").jsonArray.map { Notation.card(it.jsonPrimitive.content) }
        val wildCode = "H" + Notation.label(level)

        return when (case.str("id")) {
            "GE01" -> {
                val bombRank = Notation.rank(expected.str("bomb_rank"))
                val hand = padTo27(core) { it.rank == bombRank || Notation.code(it) == wildCode }
                val bombs = generator.generate(hand, null).filter { it.combination.type == CombinationType.BOMB && it.combination.rank == bombRank }
                val sizes = bombs.map { it.combination.size }.toSortedSet().toList()
                val wantSizes = expected.getValue("bomb_sizes_offered").jsonArray.map { it.jsonPrimitive.int }
                if (sizes != wantSizes) return "bomb sizes $sizes, expected $wantSizes"
                for (m in expected.getValue("must_include").jsonArray.map { it.jsonObject }) {
                    val phys = m.getValue("physical").jsonPrimitive.int
                    val wild = m.getValue("wildcards").jsonPrimitive.int
                    val size = m.getValue("size").jsonPrimitive.int
                    val found = bombs.any { a ->
                        val w = a.cards.count { it.isWildcard(level) }
                        a.combination.size == size && w == wild && a.cards.size - w == phys &&
                            a.declaration == List(wild) { WildcardAssignment(bombRank) }
                    }
                    if (!found) return "missing bomb physical=$phys wildcards=$wild size=$size"
                }
                null
            }
            "GE02" -> {
                val hand = padTo27(core) { Notation.code(it) == wildCode || it.suit == Suit.JOKER }
                if (generator.generate(hand, null).any { it.combination.type == CombinationType.FOUR_JOKERS }) null
                else "no four-joker action"
            }
            "GE03" -> {
                val full = generator.generate(core, null).filter { it.cards.size == core.size }
                val got = full.map { it.declaration to it.combination }.toSet()
                val want = expected.getValue("readings_using_all_cards").jsonArray.map { it.jsonObject }
                val wantSet = want.map { r ->
                    val decl = parseDeclaration(r["declaration"])!!.canonical()
                    val combo = full.firstOrNull { it.declaration == decl && matches(it.combination, r.obj("classification")) }
                        ?: return "missing reading $r; generator offers ${full.map { it.declaration to describe(it.combination) }}"
                    decl to combo.combination
                }.toSet()
                val complete = expected.getValue("complete").jsonPrimitive.boolean
                if (complete && got != wantSet) "generator offers extra readings: ${(got - wantSet).map { it.first to describe(it.second) }}" else null
            }
            else -> "no generator check for ${case.str("id")}"
        }
    }

    // ---------------------------------------------------------------- profile cases

    private fun checkProfile(case: JsonObject): String? {
        val setup = case.obj("setup")
        val expected = case.obj("expected")
        val json = """{"id":"${setup.str("profile")}","version":"1.0.0","displayName":"golden","provisional":true,"options":${setup.getValue("options")}}"""
        val result = RuleProfileLoader.parse(json)
        val accepted = expected.getValue("accepted").jsonPrimitive.boolean
        if (result.isSuccess != accepted) return "expected accepted=$accepted, got $result"
        val reason = expected["rejected_reason"].takeUnless { it is JsonNull }?.jsonPrimitive?.content
        if (reason == "unknown_option" && result.exceptionOrNull() !is RuleProfileError.UnknownOption) return "expected UnknownOption, got ${result.exceptionOrNull()}"
        return null
    }

    // ---------------------------------------------------------------- notation

    private fun parseCards(o: JsonObject): List<Card> = o.getValue("cards").jsonArray.map { Notation.card(it.jsonPrimitive.content) }

    private fun parseDeclaration(e: JsonElement?): List<WildcardAssignment>? =
        if (e == null || e is JsonNull) null else (e as JsonArray).map { d ->
            val o = d.jsonObject
            WildcardAssignment(Notation.rank(o.str("as_rank")), o["as_suit"]?.jsonPrimitive?.content?.let(Notation::suit))
        }

    /** Golden classification: type, then `rank` or low-to-high `ranks`, plus `suit` for a straight flush. */
    private fun matches(c: Combination, want: JsonObject): Boolean {
        if (TYPE_NAMES[c.type] != want.str("type")) return false
        val rank = want["rank"]?.jsonPrimitive?.content
        val ranks = want["ranks"]?.jsonArray?.map { it.jsonPrimitive.content }
        val suit = want["suit"]?.jsonPrimitive?.content
        return rank == c.rank?.let(Notation::label) &&
            ranks == c.sequence.takeIf { it.isNotEmpty() }?.map(Notation::label) &&
            suit == c.suit?.let(Notation::letter)
    }

    private fun describe(c: Combination) =
        "${TYPE_NAMES[c.type]}:${c.rank?.let(Notation::label) ?: c.sequence.joinToString(",", transform = Notation::label)}${c.suit?.let { "/" + Notation.letter(it) } ?: ""}"

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.obj(k: String) = getValue(k).jsonObject

    private companion object {
        const val GOLDEN = "/fixtures/rules/golden-v1.json"
        val TYPE_NAMES = mapOf(
            CombinationType.SINGLE to "single", CombinationType.PAIR to "pair", CombinationType.TRIPLE to "triple",
            CombinationType.TRIPLE_WITH_PAIR to "triple_with_pair", CombinationType.STRAIGHT to "straight",
            CombinationType.CONSECUTIVE_PAIRS to "consecutive_pairs", CombinationType.CONSECUTIVE_TRIPLES to "consecutive_triples",
            CombinationType.STRAIGHT_FLUSH to "straight_flush", CombinationType.BOMB to "bomb", CombinationType.FOUR_JOKERS to "four_jokers",
        )
    }
}
