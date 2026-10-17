package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** A variant is a data file, not engine code: it overrides one option and shares the registry with P1. */
class RuleProfileVariantTest {
    private fun variantJson(): String =
        requireNotNull(javaClass.getResource("/fixtures/profiles/test-variant-bomb-max-8.json")) {
            "Missing fixture: fixtures/profiles/test-variant-bomb-max-8.json"
        }.readText()

    @Test fun variantLoadsAndOverridesOneOption() {
        val p1 = loadOk(p1Json())
        val variant = loadOk(variantJson())
        assertEquals(8, variant[RuleOptions.BombMaxSize])
        assertEquals(setOf<RuleOption<*>>(RuleOptions.BombMaxSize), variant.options.keys)
        for (option in RuleOptions.registry.active.filter { it != RuleOptions.BombMaxSize }) {
            assertEquals(p1[option], variant[option], option.id)
        }
    }

    @Test fun variantSharesRegistryAndHasDifferentHash() {
        val p1 = loadOk(p1Json())
        val variant = loadOk(variantJson())
        assertSame(p1.registry, variant.registry)
        assertNotEquals(p1.hash, variant.hash)
    }

    /** Pinned like P1's hash in [RuleProfileTest]; change only with a version or canonical-format bump. */
    @Test fun variantHashIsPinned() {
        assertEquals("59aedc226c5b5dd93562b7f708797ca3d289726c3c64e2af3fbecd0aac47e1e6", loadOk(variantJson()).hash)
    }
}
