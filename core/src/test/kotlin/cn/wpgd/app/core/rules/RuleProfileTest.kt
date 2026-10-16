package cn.wpgd.app.core.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P1 is read from the repository's `rules/profiles/`, never from a copy (Gradle runs JVM tests in `core/`). */
internal fun p1Json(): String {
    val file = File("../rules/profiles/P1-base-v1.json")
    check(file.isFile) { "P1 not found at ${file.absolutePath}" }
    return file.readText()
}

internal fun loadOk(json: String): RuleProfile = RuleProfileLoader.parse(json).getOrThrow()

internal inline fun <reified E : RuleProfileError> assertRejects(json: String): E {
    val failure = RuleProfileLoader.parse(json).exceptionOrNull()
    return assertIs<E>(failure, "expected ${E::class.simpleName}, got $failure")
}

class RuleProfileTest {
    private val minimal = """{"id":"t","version":"1.0.0","displayName":"T","provisional":true}"""

    @Test fun p1Loads() {
        val p1 = loadOk(p1Json())
        assertEquals("P1-base-v1", p1.id)
        assertEquals(emptyList(), p1.warnings)
        assertTrue(p1.sourceRefs.isNotEmpty())
    }

    @Test fun everyProfileFileLoadsAndItsIdMatchesItsFileName() {
        val files = File("../rules/profiles").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no profiles found in rules/profiles")
        // rules/README.md: match profiles (M*) load against the match registry, base profiles against the base registry.
        for (file in files) {
            val registry = if (file.name.startsWith("M")) MatchOptions.registry else RuleOptions.registry
            assertEquals(file.nameWithoutExtension, RuleProfileLoader.parse(file.readText(), registry).getOrThrow().id, file.name)
        }
        assertTrue(files.any { it.name.startsWith("M") }, "no match profile found in rules/profiles")
    }

    @Test fun p1IsSignedAndTheProvisionalFlagIsExposed() {
        // G1a signed 2026-10-16 (dev-docs/decisions/G1a-base-rules.md): P1 is the released base profile.
        assertEquals(false, loadOk(p1Json()).provisional)
        assertEquals(true, loadOk(minimal).provisional)
        assertEquals(false, loadOk(minimal.replace("true", "false")).provisional)
    }

    @Test fun p1EffectiveOptionsEqualRegistryDefaultsWhereNotOverridden() {
        val p1 = loadOk(p1Json())
        val notOverridden = RuleOptions.registry.active.filter { it !in p1.options }
        assertTrue(notOverridden.isNotEmpty(), "P1 should leave at least one sourced option to the registry default")
        for (option in notOverridden) assertEquals(option.default, p1[option], option.id)
    }

    @Test fun p1ExplicitValuesAreTheBaseV1Defaults() {
        val p1 = loadOk(p1Json())
        assertTrue(p1.options.isNotEmpty())
        for ((option, value) in p1.options) assertEquals(option.default, value, option.id)
        assertEquals(10, p1[RuleOptions.BombMaxSize])
        assertEquals(false, p1[RuleOptions.SequenceWrapAroundA])
    }

    @Test fun everyDecisionDependentOptionIsSetByP1() {
        val p1 = loadOk(p1Json())
        val decided = RuleOptions.registry.active.filter { it.decision != null }
        assertEquals(decided.toSet(), p1.options.keys.filter { it.decision != null }.toSet())
    }

    @Test fun hashIsStableAcrossParses() {
        assertEquals(loadOk(p1Json()).hash, loadOk(p1Json()).hash)
        assertTrue(Regex("[0-9a-f]{64}").matches(loadOk(p1Json()).hash))
    }

    /**
     * Pins the canonical form and its hash. A change to key order, separators, a field name or any
     * registry default must fail here; update these literals only together with a profile version
     * or `CANONICAL_FORMAT` bump.
     */
    @Test fun p1CanonicalFormAndHashArePinned() {
        val p1 = loadOk(p1Json())
        assertEquals(P1_CANONICAL, p1.canonicalJson())
        assertEquals("72aac50223ea0feb844027d8c99e24554a1510c1b3d41dcc4c55db621de7de31", p1.hash)
    }

    @Test fun hashIsStableAcrossReorderedKeysAndWhitespace() {
        val a = """{"id":"t","version":"1.0.0","displayName":"T","provisional":true,
            "options":{"bomb.maxSize":9,"sequence.wrapAroundA":true}}"""
        val b = """{ "options" : { "sequence.wrapAroundA" : true , "bomb.maxSize" : 9 },
            "provisional" : true, "displayName" : "T", "version" : "1.0.0", "id" : "t" }"""
        assertEquals(loadOk(a).hash, loadOk(b).hash)
    }

    @Test fun hashChangesWhenOneOptionChanges() {
        val p1 = p1Json()
        val changed = p1.replace("\"bomb.maxSize\": 10", "\"bomb.maxSize\": 8")
        assertNotEquals(p1, changed, "test edit did not apply")
        assertNotEquals(loadOk(p1).hash, loadOk(changed).hash)
    }

    @Test fun hashChangesWhenAnOptionIsSetToAnyValue() {
        val base = loadOk(minimal)
        val noLimit = loadOk(minimal.replace("}", ""","options":{"bomb.maxSize":null}}"""))
        assertEquals(null, noLimit[RuleOptions.BombMaxSize])
        assertNotEquals(base.hash, noLimit.hash)
    }

    @Test fun unknownOptionKeyRejects() {
        // Same key as golden case PR01.
        val e = assertRejects<RuleProfileError.UnknownOption>(minimal.replace("}", ""","options":{"allowWrapAround":true}}"""))
        assertEquals("allowWrapAround", e.key)
    }

    @Test fun unknownTopLevelFieldRejects() {
        assertRejects<RuleProfileError.UnknownField>(minimal.replace("}", ""","signedBy":"x"}"""))
    }

    @Test fun wrongOptionValueTypeRejects() {
        assertRejects<RuleProfileError.WrongType>(minimal.replace("}", ""","options":{"bomb.maxSize":"10"}}"""))
        assertRejects<RuleProfileError.WrongType>(minimal.replace("}", ""","options":{"bomb.maxSize":9.5}}"""))
        assertRejects<RuleProfileError.WrongType>(minimal.replace("}", ""","options":{"bomb.minSize":null}}"""))
        assertRejects<RuleProfileError.WrongType>(minimal.replace("}", ""","options":{"sequence.wrapAroundA":"true"}}"""))
        assertRejects<RuleProfileError.WrongType>(minimal.replace("}", ""","options":{"sequence.wrapAroundA":0}}"""))
    }

    @Test fun wrongTopLevelTypeRejects() {
        assertRejects<RuleProfileError.WrongType>(minimal.replace("\"provisional\":true", "\"provisional\":\"yes\""))
        assertRejects<RuleProfileError.WrongType>(minimal.replace("\"version\":\"1.0.0\"", "\"version\":1"))
    }

    @Test fun outOfRangeOptionValueRejects() {
        assertRejects<RuleProfileError.OutOfRange>(minimal.replace("}", ""","options":{"bomb.maxSize":11}}"""))
        assertRejects<RuleProfileError.OutOfRange>(minimal.replace("}", ""","options":{"bomb.minSize":3}}"""))
    }

    @Test fun bombMinSizeAboveMaxSizeRejects() {
        val e = assertRejects<RuleProfileError.OutOfRange>(minimal.replace("}", ""","options":{"bomb.minSize":7,"bomb.maxSize":6}}"""))
        assertEquals("bomb.minSize", e.key)
        // Equal bounds, and a minimum with no maximum, both load.
        assertTrue(RuleProfileLoader.parse(minimal.replace("}", ""","options":{"bomb.minSize":6,"bomb.maxSize":6}}""")).isSuccess)
        assertTrue(RuleProfileLoader.parse(minimal.replace("}", ""","options":{"bomb.minSize":10,"bomb.maxSize":null}}""")).isSuccess)
        assertRejects<RuleProfileError.OutOfRange>(minimal.replace("}", ""","options":{"bomb.maxSize":5,"bomb.minSize":6}}"""))
    }

    @Test fun missingRequiredFieldRejects() {
        val noId = assertRejects<RuleProfileError.MissingField>(minimal.replace("\"id\":\"t\",", ""))
        assertEquals("id", noId.field)
        val noVersion = assertRejects<RuleProfileError.MissingField>(minimal.replace("\"version\":\"1.0.0\",", ""))
        assertEquals("version", noVersion.field)
        assertRejects<RuleProfileError.MissingField>(minimal.replace(",\"provisional\":true", ""))
    }

    @Test fun badVersionRejects() {
        assertRejects<RuleProfileError.BadVersion>(minimal.replace("1.0.0", "v1"))
        assertRejects<RuleProfileError.BadVersion>(minimal.replace("1.0.0", "01.0.0"))
    }

    @Test fun badIdRejects() {
        assertRejects<RuleProfileError.BadId>(minimal.replace("\"id\":\"t\"", "\"id\":\"../x\""))
    }

    @Test fun malformedJsonRejects() {
        assertRejects<RuleProfileError.Malformed>("not json")
        assertRejects<RuleProfileError.Malformed>("[]")
        assertRejects<RuleProfileError.Malformed>("""{"id":"t","id":"u","version":"1.0.0","displayName":"T","provisional":true}""")
    }

    @Test fun duplicateOptionKeyRejectsInsteadOfLastOneWinning() {
        assertRejects<RuleProfileError.Malformed>(minimal.replace("}", ""","options":{"bomb.maxSize":10,"bomb.maxSize":8}}"""))
        // The same key spelled with a JSON escape ("\u002e" is ".") is still a duplicate.
        assertRejects<RuleProfileError.Malformed>(minimal.replace("}", ""","options":{"bomb.maxSize":10,"bomb\u002emaxSize":8}}"""))
    }

    @Test fun overDeepNestingIsATypedFailureNotAStackOverflow() {
        // 100,000 levels overflowed the recursive tree parser before the depth limit existed.
        val deep = minimal.replace("}", ""","sourceRefs":""" + "[".repeat(100_000) + "]".repeat(100_000) + "}")
        assertTrue(assertRejects<RuleProfileError.Malformed>(deep).detail.startsWith("nested deeper than"))
        // Unbalanced and unterminated input is still a typed failure.
        assertRejects<RuleProfileError.Malformed>("{".repeat(100_000))
        assertRejects<RuleProfileError.Malformed>("""{"id":"t\""")
        // Brackets inside strings do not count as nesting.
        assertEquals(listOf("[".repeat(100)), loadOk(minimal.replace("}", ""","sourceRefs":["${"[".repeat(100)}"]}""")).sourceRefs)
    }

    @Test fun hashCoversARetiredOptionTheProfileStillSets() {
        val retired = BoolOption("test.retired", default = false, source = "test", decision = null, deprecatedSince = "0.2.0")
        val registry = OptionRegistry(RuleOptions.registry.all + retired)
        val without = RuleProfileLoader.parse(minimal, registry).getOrThrow()
        val with = RuleProfileLoader.parse(minimal.replace("}", ""","options":{"test.retired":false}}"""), registry).getOrThrow()
        assertNotEquals(without.hash, with.hash)
    }

    @Test fun deprecatedOptionLoadsWithWarning() {
        val retired = BoolOption("test.retired", default = false, source = "test", decision = null, deprecatedSince = "0.2.0")
        val registry = OptionRegistry(RuleOptions.registry.all + retired)
        val profile = RuleProfileLoader.parse(minimal.replace("}", ""","options":{"test.retired":true}}"""), registry).getOrThrow()
        assertEquals(listOf<RuleProfileWarning>(RuleProfileWarning.DeprecatedOption("test.retired", "0.2.0")), profile.warnings)
        assertTrue(retired !in registry.active)
    }

    private companion object {
        const val P1_CANONICAL = """{"displayName":"P1 (base-v1, signed 2026-10-16)","format":1,"id":"P1-base-v1","options":{"bomb.maxSize":10,"bomb.minSize":4,"joker.bigSmallIsPair":false,"joker.bigSmallPairRanksAsSmall":true,"joker.pairInTripleWithPair":true,"sequence.aceLowRanksLowest":true,"sequence.jokersAllowed":false,"sequence.jokersWrap":false,"sequence.wrapAroundA":false,"sequence.wrapRanksByLastCard":true,"straightFlush.playableAsStraight":false,"tripleWithPair.sameRankPair":false,"wildcard.aloneMayStandIn":false,"wildcard.alwaysDeclared":true,"wildcard.ambiguousNeedsDeclaration":true,"wildcard.flushRunAsStraight":true,"wildcard.straySuitRejected":true},"provisional":false,"sourceRefs":["rules/base-v1.md (signed 2026-10-16 by the project owner; gate G1a)","dev-docs/decisions/G1a-base-rules.md"],"version":"1.0.0"}"""
    }

    @Test fun registryIsWellFormed() {
        val all = RuleOptions.registry.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (option in all) assertTrue(option.source.isNotBlank(), option.id)
        assertTrue(RuleOptions.BombMaxSize.pending, "D2 is pending in base-v1")
    }
}
