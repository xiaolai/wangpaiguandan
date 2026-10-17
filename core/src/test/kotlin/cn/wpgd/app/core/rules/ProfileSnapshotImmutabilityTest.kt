package cn.wpgd.app.core.rules

import cn.wpgd.app.core.refusesJavaClear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 3 #40, #41: a profile and its registry are immutable even to Java callers. A mutable backing
 * map or list would let a caller change an option (or the active set) after the hash was taken, so
 * two profiles with one hash could play different rules.
 */
class ProfileSnapshotImmutabilityTest {
    @Test fun profileCollectionsRefuseJavaMutation() {
        // Two or more entries: a one-entry Kotlin toMap()/toList() happens to be a Java singleton, which already refuses.
        val json = """{"id":"test-variant","version":"1.0.0","displayName":"T","provisional":true,""" +
            """"sourceRefs":["a","b"],"options":{"bomb.minSize":5,"sequence.wrapAroundA":true}}"""
        val profile = loadOk(json)
        val hash = profile.hash
        assertTrue(refusesJavaClear(profile.options), "options")
        assertTrue(refusesJavaClear(profile.sourceRefs), "sourceRefs")
        assertTrue(refusesJavaClear(profile.warnings), "warnings")
        assertEquals(5, profile[RuleOptions.BombMinSize])
        assertEquals(hash, loadOk(json).hash)
    }

    @Test fun registrySnapshotsRefuseJavaMutation() {
        val registry = OptionRegistry(listOf(RuleOptions.BombMinSize, RuleOptions.BombMaxSize))
        assertTrue(refusesJavaClear(registry.all), "all")
        assertTrue(refusesJavaClear(registry.active), "active")
        assertTrue(refusesJavaClear(RuleOptions.registry.active), "the shipped registry's active list")
        assertEquals(2, registry.active.size)
    }
}
