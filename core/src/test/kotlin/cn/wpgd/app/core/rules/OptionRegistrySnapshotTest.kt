package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals

/** A registry is fixed at construction: the caller's list cannot change it afterwards. */
class OptionRegistrySnapshotTest {
    @Test fun registryDoesNotAliasTheCallersList() {
        val options = mutableListOf<RuleOption<*>>(RuleOptions.BombMinSize, RuleOptions.BombMaxSize)
        val registry = OptionRegistry(options)
        options.clear()
        assertEquals(2, registry.all.size)
        assertEquals(registry.active.size, registry.all.size)
        assertEquals(RuleOptions.BombMinSize, registry["bomb.minSize"])
    }
}
